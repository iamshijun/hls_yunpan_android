package xyz.asitanokibou.player.ui

import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    /** 播放指定目录(番号);自动 setMediaItem + prepare + play */
    fun play(fanCode: String) {
        _state.value = _state.value.copy(error = null)
        val item = MediaItem.Builder().setMediaId(fanCode.trim()).build()
        player.setMediaItem(item)
        player.prepare()
        player.play()
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
