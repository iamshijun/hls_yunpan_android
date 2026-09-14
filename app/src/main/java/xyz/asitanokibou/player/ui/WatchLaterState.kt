package xyz.asitanokibou.player.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import xyz.asitanokibou.player.data.MovieInfo
import xyz.asitanokibou.player.data.MovieRepository
import xyz.asitanokibou.player.watchlater.WatchLaterEntry
import xyz.asitanokibou.player.watchlater.WatchLaterStore

/**
 * 稍后再看列表页的状态:本地只存 {fanCode, addedAt},进页面时按番号批量向影片服务
 * 拉详情(标题/演员)实时回填 —— 逻辑镜像列表页(MovieListState.fetchDetails)。
 */
class WatchLaterState(
    private val store: WatchLaterStore,
    private val repository: MovieRepository,
    private val scope: CoroutineScope,
) {
    /** 稍后再看条目,按加入时间倒序(新加的排最上面) */
    var items by mutableStateOf<List<WatchLaterEntry>>(emptyList())
        private set

    /** fanCode -> 影片详情;异步回填,未配置服务/失败时静默留空,UI 回退显示番号 */
    var details by mutableStateOf<Map<String, MovieInfo>>(emptyMap())
        private set

    /** 正在移除的 fanCode(行内 loading);null 表示无移除进行中 */
    var removing by mutableStateOf<String?>(null)
        private set

    /** 最近一次移除失败原因,UI 消费后调用 [clearRemoveError] 清除 */
    var removeError by mutableStateOf<String?>(null)
        private set

    /** 进入页面时加载:读本地列表 + 批量拉详情 */
    fun load() {
        scope.launch {
            items = store.list().sortedByDescending { it.addedAt }
            fetchDetails(items.map { it.fanCode })
        }
    }

    /**
     * 移除一条稍后再看:先改本地(立即消失),再落库;落库失败重读本地回滚并记错误。
     */
    fun remove(entry: WatchLaterEntry) {
        if (removing != null) return
        removing = entry.fanCode
        removeError = null
        scope.launch {
            try {
                store.remove(entry.fanCode)
                items = items.filterNot { it.fanCode == entry.fanCode }
                details = details - entry.fanCode
            } catch (e: Exception) {
                removeError = e.message ?: e::class.java.simpleName
                load() // 回滚:以本地真实状态为准
            } finally {
                removing = null
            }
        }
    }

    fun clearRemoveError() {
        removeError = null
    }

    /** 按番号批量查详情并回填;不阻塞列表,失败静默回退显示番号 */
    private fun fetchDetails(fanCodes: List<String>) {
        if (fanCodes.isEmpty()) return
        scope.launch {
            val infoMap = repository.movieInfo(fanCodes)
            if (infoMap.isEmpty()) return@launch
            details = details + infoMap
        }
    }
}
