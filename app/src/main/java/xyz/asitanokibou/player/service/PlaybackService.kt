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
import xyz.asitanokibou.player.progress.PlaybackServiceOnly

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
 */
class PlaybackService : MediaSessionService() {

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var appContainer: AppContainer
    private var proxyGraph: ProxyGraph? = null

    private lateinit var progressStore: PlayProgressStore
    private var progressTickerJob: Job? = null
    /** 时钟域是 elapsedRealtime(开机起算),不是墙钟 */
    private var lastFlushAt = 0L

    /** 一次待落盘的进度快照 */
    private data class ProgressSnapshot(val fanCode: String, val positionMs: Long, val durationMs: Long)

    /** 最近一次可信快照;item 被清空后 player 上已读不到位置,靠它兜底 */
    private var lastSnapshot: ProgressSnapshot? = null

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

        progressTickerJob = serviceScope.launch {
            while (isActive) {
                delay(PROGRESS_TICK_MS)
                flushProgress(force = false)
            }
        }

        Log.i(TAG, "PlaybackService 已就绪，代理端口=$port")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        // 后台播放关闭时:从最近任务划掉应用一律停止服务;
        // 开启时保持原行为(播放中则继续后台播放,否则停止)
        if (!appContainer.backgroundPlayback.value || !player.playWhenReady || player.mediaItemCount == 0) {
            runBlocking { flushProgressAndAwait() }
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // player.release() 之后读不到位置,且 serviceScope 随后被取消,所以这里同步落盘
        runBlocking { flushProgressAndAwait() }
        progressTickerJob?.cancel()
        player.removeListener(progressListener)
        mediaSession.release()
        player.release()
        proxyGraph?.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * STATE_ENDED 时的位置会落进 [PlayProgressStore] 的“看完”分支(记录被删);
     * 暂停后位置不再变化,晚一次定时采集会白丢一段进度。
     */
    private val progressListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) flushProgress(force = true)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) flushProgress(force = true)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // 节流窗口属于上一部影片,清掉:换片后的第一个 tick 就能落盘
            lastFlushAt = 0L
        }
    }

    private fun flushProgress(force: Boolean) {
        val snapshot = takeSnapshot(force) ?: return
        serviceScope.launch { writeProgress(snapshot) }
    }

    private suspend fun flushProgressAndAwait() {
        val snapshot = takeSnapshot(force = true) ?: return
        writeProgress(snapshot)
    }

    /**
     * 快照必须在这里同步取:UI 离开播放页会 stop + clearMediaItems,item 和位置一起消失,
     * 等到 launch 之后再读就只能拿到空的。
     */
    private fun takeSnapshot(force: Boolean): ProgressSnapshot? {
        if (!force) {
            if (player.playbackState == Player.STATE_IDLE) return null
            if (!player.isPlaying) return null
            if (SystemClock.elapsedRealtime() - lastFlushAt < FLUSH_INTERVAL_MS) return null
        }
        val live = snapshotPlayer()
        val cached = lastSnapshot
        val chosen = when {
            live == null -> cached
            cached == null || cached.fanCode != live.fanCode -> live
            cached.positionMs >= live.positionMs -> cached
            else -> live
        } ?: return null
        if (chosen === live) lastSnapshot = chosen
        lastFlushAt = SystemClock.elapsedRealtime()
        return chosen
    }

    private fun snapshotPlayer(): ProgressSnapshot? {
        val item = player.currentMediaItem ?: return null
        val fanCode = item.mediaId.trim().trim('/')
        if (fanCode.isEmpty()) return null
        // duration 未知时为 C.TIME_UNSET(负数),统一折算成 0 → 不做看完判断
        val durationMs = player.duration.let { if (it > 0L) it else 0L }
        return ProgressSnapshot(fanCode, player.currentPosition, durationMs)
    }

    @kotlin.OptIn(PlaybackServiceOnly::class)
    private suspend fun writeProgress(snapshot: ProgressSnapshot) {
        progressStore.record(snapshot.fanCode, snapshot.positionMs, snapshot.durationMs)
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

        private const val PROGRESS_TICK_MS = 15_000L

        private const val FLUSH_INTERVAL_MS = 30_000L
    }
}
