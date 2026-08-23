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
}
