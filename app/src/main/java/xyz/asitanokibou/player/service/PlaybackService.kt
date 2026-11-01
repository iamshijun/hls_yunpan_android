package xyz.asitanokibou.player.service

import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import xyz.asitanokibou.player.HlsPanApp
import xyz.asitanokibou.player.MainActivity
import xyz.asitanokibou.player.core.HlsPaths
import xyz.asitanokibou.player.di.AppContainer
import xyz.asitanokibou.player.di.ProxyGraph
import xyz.asitanokibou.player.progress.PlayProgressStore

/**
 * 播放服务：统一托管 ExoPlayer + 本地 Ktor 代理。
 *
 * - onCreate 读取配置快照，通过 AppContainer 创建代理并启动
 * - MediaSession.Callback 将控制端传入的 mediaId(用户输入的目录路径)解析为
 *   `http://127.0.0.1:<port>/hls/<path>/playlist.m3u8`
 * - 以 mediaPlayback 前台服务运行（Media3 自动管理前台通知与保活）
 * - 后台播放:默认开启;关闭时 UI 层在离开应用时暂停,本服务在任务被划掉时停止
 *
 * 注意：access_token 通过 BaiduClient 的 tokenProvider 动态读取，改 token 立即生效；
 * fsid 缓存 TTL(及端口)在服务启动时快照，修改需重启服务生效（MVP）。
 * 后台播放开关通过 AppContainer 的 StateFlow 实时读取，修改立即生效。
 *
 * 观看进度的采集也归这里：UI 侧的 MediaController 在 MainActivity.onStop 就被释放了，
 * 而本服务在后台继续放，只有服务知道“现在真的在播”，所以采集权不下放到 UI。
 */
class PlaybackService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var appContainer: AppContainer
    private var proxyGraph: ProxyGraph? = null

    private lateinit var progressStore: PlayProgressStore
    private var progressTickerJob: Job? = null
    /** 上次落盘的时刻(开机制),仅由 [recordProgress] 使用;服务重启后自然归零 */
    private var lastFlushAt = 0L

    @Volatile private var port: Int = -1

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        val container = (application as HlsPanApp).container
        appContainer = container
        progressStore = container.playProgressStore
        val config = runBlocking { container.appSettings.get() }

        val graph = container.createProxyGraph(config)
        port = graph.port
        proxyGraph = graph

        player = ExoPlayer.Builder(this)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(this)
                    .setDataSourceFactory(
                        DefaultHttpDataSource.Factory()
                            .setConnectTimeoutMs(EXOPLAYER_CONNECT_TIMEOUT_MS)
                            .setReadTimeoutMs(EXOPLAYER_READ_TIMEOUT_MS)
                            .setAllowCrossProtocolRedirects(true),
                    ),
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .build()
        mediaSession = MediaSession.Builder(this, player)
            .setCallback(MediaSessionCallback())
            .setSessionActivity(buildSessionActivityPendingIntent())
            .build()
        player.addListener(progressListener)

        // 观看进度定时采集:每 [PROGRESS_TICK_MS] 醒一次,是否真正落盘由 recordProgress 内部节流
        progressTickerJob = serviceScope.launch {
            while (isActive) {
                delay(PROGRESS_TICK_MS)
                recordProgress(force = false)
            }
        }

        Log.i(TAG, "PlaybackService 已就绪，代理端口=$port")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        // 后台播放关闭时:从最近任务划掉应用一律停止服务;
        // 开启时保持原行为(播放中则继续后台播放,否则停止)
        if (!appContainer.backgroundPlayback.value || !player.playWhenReady || player.mediaItemCount == 0) {
            // 服务即将停止:先把最终位置落盘(此后不会再有机会采集)
            runBlocking { recordProgress(force = true) }
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // player.release() 之后读不到位置,且 serviceScope 随后被取消,所以这里同步落盘
        runBlocking { recordProgress(force = true) }
        progressTickerJob?.cancel()
        player.removeListener(progressListener)
        mediaSession.release()
        player.release()
        proxyGraph?.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * 暂停 / 播放结束这两个事件强制落盘:暂停后位置不再变化,晚一次定时采集会白丢一段进度;
     * 播放结束时位置已到片尾,写入会被 [PlayProgressStore] 判定为“看完”从而清掉记录。
     */
    private val progressListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) serviceScope.launch { recordProgress(force = true) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                serviceScope.launch { recordProgress(force = true) }
            }
        }
    }

    /**
     * 采集当前观看进度并落盘。
     *
     * 精度设计(需求是“不需要太精确”):
     * - 定时采集(ticker 每 [PROGRESS_TICK_MS] 一次)只在真的在播、且距上次落盘超过
     *   [FLUSH_INTERVAL_MS] 时才写。1x 播放时位置漂移与墙钟同步,所以“间隔足够”本身就
     *   隐含了“位置已经走了足够远”。
     * - 强制采集(暂停 / 结束 / 任务划掉 / 服务销毁)不受节流限制,用于收下最后的准确位置。
     * - 用户直接离开播放页(UI 侧 stop+clearMediaItems)时,控制端事件到达时 item 已被清空,
     *   这一次最多丢掉最后一个节流窗口(≤[FLUSH_INTERVAL_MS])的进度——按“不需要太精确”接受,
     *   不为它引入第二个写入者。
     */
    private suspend fun recordProgress(force: Boolean) {
        val item = player.currentMediaItem ?: return
        val fanCode = item.mediaId.trim().trim('/')
        if (fanCode.isEmpty()) return
        // 未 prepare / 已 stop 时没有可信位置
        if (player.playbackState == Player.STATE_IDLE) return
        if (!force) {
            if (!player.isPlaying) return
            if (SystemClock.elapsedRealtime() - lastFlushAt < FLUSH_INTERVAL_MS) return
        }
        val positionMs = player.currentPosition
        // duration 未知时为 C.TIME_UNSET(负数),统一折算成 0 → 不做看完判断
        val durationMs = player.duration.let { if (it > 0L) it else 0L }
        progressStore.record(fanCode, positionMs, durationMs)
        lastFlushAt = SystemClock.elapsedRealtime()
    }

    private inner class MediaSessionCallback : MediaSession.Callback {
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.map { item ->
                val uri = buildPlaylistUri(item.mediaId)
                item.buildUpon().setUri(uri).build()
            }.toMutableList()
            return Futures.immediateFuture(resolved)
        }
    }

    private fun buildPlaylistUri(mediaId: String): String =
        HlsPaths.playlistUrl(port, mediaId)

    /**
     * 点击系统媒体通知时触发的 PendingIntent:将 MainActivity 拉回前台。
     * MainActivity 在 manifest 中声明为 singleTask,SINGLE_TOP 即可复用现有实例
     * 并触发 onNewIntent,不会重建 Activity。
     */
    private fun buildSessionActivityPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        return PendingIntent.getActivity(
            this,
            /* requestCode = */ 0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    companion object {
        private const val TAG = "PlaybackService"

        private const val EXOPLAYER_CONNECT_TIMEOUT_MS = 15_000
        private const val EXOPLAYER_READ_TIMEOUT_MS = 90_000

        /** 观看进度定时采集的节拍:轻量(仅在内存里判断是否该落盘) */
        private const val PROGRESS_TICK_MS = 15_000L

        /** 两次落盘之间的最小间隔:据此把精度控制在“半分钟级” */
        private const val FLUSH_INTERVAL_MS = 30_000L
    }
}
