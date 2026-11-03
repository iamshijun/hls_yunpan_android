package xyz.asitanokibou.player.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 移动数据闸门的判定快照:网络 + 两个设置开关 + 两级内存放行令牌。
 * UI 只看它就能决定「要不要弹框 / 要不要显示提示」,不必自己拼条件。
 */
data class NetworkGateState(
    val metered: Boolean = false,
    /** 设置项「移动数据下允许播放」 */
    val allowPlaybackOnMetered: Boolean = false,
    /** 设置项「移动数据下允许下载」 */
    val allowDownloadOnMetered: Boolean = false,
    /** 播放会话级放行:用户选了「本次继续」,离开播放页即清 */
    val playbackSessionAllowed: Boolean = false,
    /** 任务级放行:番号集合,任务完成/取消即清 */
    val allowedDownloads: Set<String> = emptySet(),
) {
    val playbackBlocked: Boolean get() = metered && !allowPlaybackOnMetered && !playbackSessionAllowed

    /** 下载被拦住:计费网络且设置未放行(任务级放行另见 [downloadAllowed]) */
    val downloadBlocked: Boolean get() = metered && !allowDownloadOnMetered

    fun downloadAllowed(fanCode: String): Boolean =
        !downloadBlocked || normalizeFanCode(fanCode) in allowedDownloads
}

/**
 * 移动数据闸门:判定入口与放行令牌的唯一持有者,进程级单例(见 AppContainer)。
 *
 * 放行令牌是**纯内存**态,绝不落盘 —— 「以后都允许」要靠设置里的开关,不靠这里。
 * [canUseMeteredPlayback] / [canUseMeteredDownload] 由 UI、DownloadManager 与本地代理共用,
 * 交互层与兜底层因此不会各判一套。
 */
class NetworkGate {

    private val _state = MutableStateFlow(NetworkGateState())
    val state: StateFlow<NetworkGateState> = _state.asStateFlow()

    /** 由 AppContainer 接上 [NetworkMonitor] */
    fun setMetered(metered: Boolean) = _state.update { it.copy(metered = metered) }

    /** 由 AppContainer 接上设置项;改完立即生效 */
    fun applySettings(allowPlaybackOnMetered: Boolean, allowDownloadOnMetered: Boolean) =
        _state.update {
            it.copy(
                allowPlaybackOnMetered = allowPlaybackOnMetered,
                allowDownloadOnMetered = allowDownloadOnMetered,
            )
        }

    // ---- 唯一判定入口 ----

    /** 此刻能否开始/继续播放(自动播放、手动播放、播放中切网都问它) */
    fun canUseMeteredPlayback(): Boolean = !_state.value.playbackBlocked

    /** 此刻能否操作该番号的下载(入队、恢复都问它) */
    fun canUseMeteredDownload(fanCode: String): Boolean = _state.value.downloadAllowed(fanCode)

    // ---- 放行令牌 ----

    /** 「本次继续」:只对当前播放会话有效,离开播放页由 UI 清掉 */
    fun allowPlaybackForSession() = _state.update { it.copy(playbackSessionAllowed = true) }

    fun clearPlaybackSession() = _state.update { it.copy(playbackSessionAllowed = false) }

    /** 「本次继续」:只对当前下载任务有效,任务完成/取消时 [revokeDownload] */
    fun allowDownload(fanCode: String) {
        val code = normalizeFanCode(fanCode)
        _state.update { it.copy(allowedDownloads = it.allowedDownloads + code) }
    }

    fun revokeDownload(fanCode: String) {
        val code = normalizeFanCode(fanCode)
        _state.update { it.copy(allowedDownloads = it.allowedDownloads - code) }
    }

    /**
     * 只在「计费 ↔ 非计费」真正翻转时回调(启动时的当前值不回调):
     * 播放器与下载队列各自照这个决定暂停/续上,两边不各写一套。
     */
    fun observeMeteredTransitions(scope: CoroutineScope, onChange: (Boolean) -> Unit): Job =
        scope.launch {
            state.map { it.metered }.distinctUntilChanged().drop(1).collect(onChange)
        }
}

private fun normalizeFanCode(fanCode: String): String = fanCode.trim().trim('/')
