package xyz.asitanokibou.player.core

import android.util.Log
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
    /**
     * 播放会话级放行:用户选了「本次继续」的那个番号。
     * 只对这部影片的这次播放会话有效 —— 换片、播放停止即作废(见 [PlaybackService])。
     */
    val allowedPlayback: String? = null,
    /** 任务级放行:番号集合,任务完成/取消即清 */
    val allowedDownloads: Set<String> = emptySet(),
) {
    /** 该番号此刻的播放是否被拦住:计费网络下,既没有设置开关、也没有针对它的会话放行 */
    fun playbackBlocked(fanCode: String?): Boolean =
        metered && !allowPlaybackOnMetered && normalizeFanCode(fanCode) != allowedPlayback

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
 *
 * 放行令牌的生命周期跟着**被放行的那个东西**走,不跟着谁点了按钮走:
 * 播放按番号记(播放器换片/停止时撤销),下载按番号集合记(任务完成/取消时撤销)。
 * 曾经把播放放行挂在播放页 Composable 上,结果锁屏、切后台回来都会重建组合,
 * 令牌被无关的 UI 生命周期清掉 —— 表现为「点过本次继续,播放还是被 403 掐断」。
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

    /** 此刻能否取流播放该番号(自动播放、手动播放、代理兜底、播放中切网都问它) */
    fun canUseMeteredPlayback(fanCode: String?): Boolean = !_state.value.playbackBlocked(fanCode)

    /** 此刻能否操作该番号的下载(入队、恢复都问它) */
    fun canUseMeteredDownload(fanCode: String): Boolean = _state.value.downloadAllowed(fanCode)

    // ---- 放行令牌 ----

    /**
     * 「本次继续」:只对这部影片的这次播放会话有效。
     * @param fanCode 被放行的番号;之后只有这个番号的取流才被放行,别的影片照样要问
     */
    fun allowPlaybackForSession(fanCode: String) {
        val code = normalizeFanCode(fanCode)
        _state.update { it.copy(allowedPlayback = code) }
        Log.i(TAG, "本次继续: 放行播放会话 [$code]")
    }

    /** 播放会话结束(播放器停止/清空/换片)时撤销 */
    fun clearPlaybackSession() {
        if (_state.value.allowedPlayback == null) return
        _state.update { it.copy(allowedPlayback = null) }
        Log.i(TAG, "播放会话结束, 撤销本次继续的放行")
    }

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

private const val TAG = "NetworkGate"

/** 番号归一化:空串保持空串,便于和「未放行」(null)区分开 */
private fun normalizeFanCode(fanCode: String?): String = fanCode?.trim()?.trim('/') ?: ""
