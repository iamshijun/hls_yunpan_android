package xyz.asitanokibou.player.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import xyz.asitanokibou.player.baidu.model.BaiduFile
import xyz.asitanokibou.player.core.HlsPaths
import xyz.asitanokibou.player.data.MovieInfo
import xyz.asitanokibou.player.data.MovieRepository

/** 列表条目:网盘目录 + 影片详情(详情异步填充,可能为 null) */
data class MovieListItem(
    val dir: BaiduFile,
    val info: MovieInfo? = null,
) {
    /** 目录名即番号 */
    val fanCode: String get() = HlsPaths.toRelativePath(dir.path)
}

class MovieListState(
    private val repository: MovieRepository,
    private val scope: CoroutineScope,
) {
    var loading by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var loadingMore by mutableStateOf(false)
        private set
    var loadMoreError by mutableStateOf<String?>(null)
        private set
    var items by mutableStateOf<List<MovieListItem>>(emptyList())
        private set
    var hasMore by mutableStateOf(true)
        private set
    var sortOrder by mutableStateOf("time")
        private set
    var sortDesc by mutableStateOf(true)
        private set

    /** 正在删除的目录 fsId（用于 UI 显示 loading）；null 表示无删除进行中 */
    var deleting by mutableStateOf<Long?>(null)
        private set
    /** 最近一次删除失败的原因，UI 消费后调用 [clearDeleteError] 清除 */
    var deleteError by mutableStateOf<String?>(null)
        private set

    private var page = 1

    fun loadFirstPage() {
        loading = true
        error = null
        loadMoreError = null
        items = emptyList()
        page = 1
        hasMore = true
        scope.launch {
            try {
                items = fetchPage(1)
            } catch (e: Exception) {
                error = e.message ?: e::class.java.simpleName
            } finally {
                loading = false
            }
        }
    }

    fun loadMore() {
        if (loadingMore || !hasMore) return
        loadingMore = true
        loadMoreError = null
        scope.launch {
            try {
                items = items + fetchPage(page)
            } catch (e: Exception) {
                loadMoreError = e.message ?: e::class.java.simpleName
            } finally {
                loadingMore = false
            }
        }
    }

    fun refresh(onComplete: (() -> Unit)? = null) {
        scope.launch {
            try {
                // 刷新即重新开始分页:重置页码与 hasMore,否则上一轮"已经到底"后无法再加载
                hasMore = true
                items = fetchPage(1)
                loadMoreError = null
                error = null
            } catch (e: Exception) {
                error = e.message ?: e::class.java.simpleName
            } finally {
                onComplete?.invoke()
            }
        }
    }

    /** 取第 [pageNo] 页并推进页码;详情异步回填,不阻塞列表显示 */
    private suspend fun fetchPage(pageNo: Int): List<MovieListItem> {
        val files = repository.directories(
            page = pageNo,
            limit = MovieRepository.DEFAULT_PAGE_SIZE,
            order = sortOrder,
            desc = sortDesc,
        )
        page = pageNo + 1
        if (files.size < MovieRepository.DEFAULT_PAGE_SIZE) hasMore = false
        fetchDetails(files.map { HlsPaths.toRelativePath(it.path) })
        return files.map { MovieListItem(it) }
    }

    /** 按番号批量查详情并原地回填;不阻塞列表,失败静默回退显示番号 */
    private fun fetchDetails(fanCodes: List<String>) {
        if (fanCodes.isEmpty()) return
        scope.launch {
            val infoMap = repository.movieInfo(fanCodes)
            if (infoMap.isEmpty()) return@launch
            // 按 fsId 匹配当前列表原地更新;列表已刷新/重排时,过期结果自然被丢弃
            items = items.map { item ->
                val info = infoMap[item.fanCode]
                if (info != null) item.copy(info = info) else item
            }
        }
    }

    fun changeSort(order: String) {
        if (sortOrder != order) {
            sortOrder = order
            loadFirstPage()
        }
    }

    fun toggleSortDirection() {
        sortDesc = !sortDesc
        loadFirstPage()
    }

    fun clearDeleteError() {
        deleteError = null
    }

    /**
     * 删除一个目录：先调网盘接口（失败仅记录 [deleteError]），成功后同步删除影片服务端记录，
     * 再从列表移除并重拉已加载页。影片服务未配置/删除失败不影响网盘删除结果。
     * [onDone] 在接口返回后回调（true=成功），供 UI 做收尾。
     */
    fun delete(item: MovieListItem, onDone: (Boolean) -> Unit = {}) {
        if (deleting != null) return
        deleting = item.dir.fsId
        deleteError = null
        scope.launch {
            try {
                repository.deleteDirectory(item.fanCode)
                // 网盘删除成功后,同步删除影片服务端记录(未配置/失败静默,不阻塞列表)
                repository.deleteRemoteInfo(item.fanCode)
                items = items.filterNot { it.dir.fsId == item.dir.fsId }
                reloadLoadedPages()
                onDone(true)
            } catch (e: Exception) {
                deleteError = e.message ?: e::class.java.simpleName
                onDone(false)
            } finally {
                deleting = null
            }
        }
    }

    /**
     * 删除后重拉当前已加载的各页：服务器端少了一项，若沿用旧分页偏移会错位漏项，
     * 重新按原页码拉取即可自动补位。已加载项保留旧详情避免闪烁，新顶上的项由 fetchDetails 回填。
     */
    private suspend fun reloadLoadedPages() {
        val loadedPages = page - 1
        if (loadedPages <= 0) return
        val old = items
        var reloaded = emptyList<MovieListItem>()
        var lastBatchSize = 0
        for (p in 1..loadedPages) {
            val files = repository.directories(
                page = p,
                limit = MovieRepository.DEFAULT_PAGE_SIZE,
                order = sortOrder,
                desc = sortDesc,
            )
            lastBatchSize = files.size
            reloaded += files.map { f ->
                old.firstOrNull { it.dir.fsId == f.fsId }?.copy(dir = f) ?: MovieListItem(f)
            }
        }
        hasMore = lastBatchSize >= MovieRepository.DEFAULT_PAGE_SIZE
        fetchDetails(reloaded.map { it.fanCode })
        items = reloaded
    }
}
