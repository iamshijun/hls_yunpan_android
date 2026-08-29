package xyz.asitanokibou.player.ui

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import xyz.asitanokibou.player.data.MovieInfo

/** 播放页 UI 状态:由 [PlaybackController] 维护,Compose 只读、不脚本化 Player */
data class PlaybackUiState(
    val playbackState: Int = Player.STATE_IDLE,
    val status: String? = null,
    val error: String? = null,
)

/**
 * 播放控制:把"开始播放 / 暂停续播 / 离开清理 / 状态观察"集中到一个模块,
 * 坐在 MediaController(Media3)与应用 UI 之间接缝的应用侧。
 *
 * UI 通过 [state] 观察、通过 play/pause/release 命令,不再直接脚本化 Player;
 * [player] 仅暴露给视图层用于渲染(PlayerView 绑定)。
 *
 * 生命周期与 MediaController 一致,由 MainActivity 创建并在其释放前 dispose。
 */
class PlaybackController(
    val player: Player,
) {
    private val _state = MutableStateFlow(PlaybackUiState(playbackState = player.playbackState))
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            _state.value = _state.value.copy(
                playbackState = playbackState,
                status = when (playbackState) {
                    Player.STATE_IDLE -> "空闲"
                    Player.STATE_BUFFERING -> "缓冲中…"
                    Player.STATE_READY -> "就绪"
                    Player.STATE_ENDED -> "已结束"
                    else -> null
                },
            )
        }

        override fun onPlayerError(error: PlaybackException) {
            _state.value = _state.value.copy(error = "${error.errorCodeName}: ${error.message ?: ""}")
        }
    }

    init {
        player.addListener(listener)
    }

    /**
     * 播放指定目录(番号);自动 setMediaItem + prepare + play。
     *
     * @param info 可选的影片元信息,用于填充 MediaMetadata(title / artworkUri),
     *             通知栏 MediaStyle 会据此显示标题与封面背景。
     */
    fun play(fanCode: String, info: MovieInfo? = null) {
        _state.value = _state.value.copy(error = null)
        val id = fanCode.trim()
        val metadata = buildMediaMetadata(id, info)
        val item = MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(metadata)
            .build()
        player.setMediaItem(item)
        player.prepare()
        player.play()
    }

    private fun buildMediaMetadata(fanCode: String, info: MovieInfo?): MediaMetadata {
        val title = info?.title?.takeIf { it.isNotBlank() } ?: fanCode
        // 番号 + 真实标题(若 title 与 fanCode 不同则拼接;相同时只取一份)
        val displayTitle = if (title == fanCode) fanCode else "$fanCode $title"
        val builder = MediaMetadata.Builder()
            .setTitle(displayTitle)
            .setArtist(fanCode)
            .setIsBrowsable(false)
            .setIsPlayable(true)
        // 仅 HTTPS(及 localhost)封面会被网络配置放行;失败时由系统静默回退占位图
        info?.cover
            ?.takeIf { it.isNotBlank() }
            ?.let { builder.setArtworkUri(Uri.parse(it)) }
        return builder.build()
    }

    /**
     * 回填当前 media item 的元数据,用于影片详情异步加载完成后通知/控制器同步封面。
     * 仅在 mediaId 与 [fanCode] 一致时生效(避免手动改路径后误覆盖),并保留播放位置。
     */
    fun updateMetadata(fanCode: String, info: MovieInfo?) {
        val id = fanCode.trim()
        val current = player.currentMediaItem ?: return
        if (current.mediaId != id) return
        val updated = current.buildUpon()
            .setMediaMetadata(buildMediaMetadata(id, info))
            .build()
        player.replaceMediaItem(player.currentMediaItemIndex, updated)
    }

    /** 暂停(离开播放页但保留续播,如推入设置页) */
    fun pause() {
        player.pause()
    }

    /** 停止并清空,避免旧影片画面/封面残留(离开播放页) */
    fun release() {
        player.stop()
        player.clearMediaItems()
    }

    /** 移除监听;由 MainActivity 在 MediaController 释放前调用 */
    fun dispose() {
        player.removeListener(listener)
    }
}
