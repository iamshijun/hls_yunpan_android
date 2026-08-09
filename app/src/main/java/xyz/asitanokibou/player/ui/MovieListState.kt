package xyz.asitanokibou.player.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import xyz.asitanokibou.player.baidu.model.BaiduFile
import xyz.asitanokibou.player.data.MovieRepository

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
    var dirs by mutableStateOf<List<BaiduFile>>(emptyList())
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
        dirs = emptyList()
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
                dirs = page
                nextStart = page.size
                hasMore = page.size >= PAGE_SIZE
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
                dirs = dirs + page
                nextStart += page.size
                if (page.size < PAGE_SIZE) hasMore = false
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
                dirs = page
                nextStart = page.size
                hasMore = page.size >= PAGE_SIZE
                loadMoreError = null
                error = null
            } catch (e: Exception) {
                error = e.message ?: e::class.java.simpleName
            } finally {
                onComplete?.invoke()
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
