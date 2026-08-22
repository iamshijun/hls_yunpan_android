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

    private var nextStart = 0

    fun loadFirstPage() {
        loading = true
        error = null
        loadMoreError = null
        items = emptyList()
        nextStart = 0
        hasMore = true
        scope.launch {
            try {
                val page = repository.directories(
                    start = 0,
                    limit = PAGE_SIZE,
                    order = sortOrder,
                    desc = if (sortDesc) 1 else 0,
                )
                items = page.map { MovieListItem(it) }
                nextStart = page.size
                hasMore = page.size >= PAGE_SIZE
                fetchDetails(page.map { HlsPaths.toRelativePath(it.path) })
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
                val page = repository.directories(
                    start = nextStart,
                    limit = PAGE_SIZE,
                    order = sortOrder,
                    desc = if (sortDesc) 1 else 0,
                )
                items = items + page.map { MovieListItem(it) }
                nextStart += page.size
                if (page.size < PAGE_SIZE) hasMore = false
                fetchDetails(page.map { HlsPaths.toRelativePath(it.path) })
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
                val page = repository.directories(
                    start = 0,
                    limit = PAGE_SIZE,
                    order = sortOrder,
                    desc = if (sortDesc) 1 else 0,
                )
                items = page.map { MovieListItem(it) }
                nextStart = page.size
                hasMore = page.size >= PAGE_SIZE
                loadMoreError = null
                error = null
                fetchDetails(page.map { HlsPaths.toRelativePath(it.path) })
            } catch (e: Exception) {
                error = e.message ?: e::class.java.simpleName
            } finally {
                onComplete?.invoke()
            }
        }
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

    companion object {
        const val PAGE_SIZE = 20
    }
}
