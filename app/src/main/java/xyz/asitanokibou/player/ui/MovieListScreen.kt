package xyz.asitanokibou.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import xyz.asitanokibou.player.baidu.model.BaiduFile
import xyz.asitanokibou.player.core.HlsPaths

private const val SORT_TIME = "time"
private const val SORT_NAME = "name"
private const val SORT_SIZE = "size"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MovieListScreen(
    hasToken: Boolean,
    state: MovieListState,
    onBack: () -> Unit,
    onPick: (relativePath: String) -> Unit,
) {
    val listState = rememberLazyListState()
    val pullState = rememberPullToRefreshState()

    LaunchedEffect(hasToken, state.sortOrder, state.sortDesc) {
        if (hasToken) {
            state.loadFirstPage()
        }
    }

    LaunchedEffect(state.sortOrder, state.sortDesc) {
        listState.scrollToItem(0)
    }

    if (pullState.isRefreshing) {
        LaunchedEffect(true) {
            state.refresh(onComplete = { pullState.endRefresh() })
        }
    }

    val shouldLoadMore by remember {
        derivedStateOf {
            if (state.loading || state.loadingMore || !state.hasMore) return@derivedStateOf false
            val info = listState.layoutInfo
            val total = info.totalItemsCount
            if (total == 0) return@derivedStateOf true
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            lastVisible >= total - 3
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            state.loadMore()
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .nestedScroll(pullState.nestedScrollConnection),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("← 返回") }
                Spacer(Modifier.width(8.dp))
                Text("视频目录列表", style = MaterialTheme.typography.titleLarge)
            }
            SortBar(
                order = state.sortOrder,
                desc = state.sortDesc,
                onOrderChange = { state.changeSort(it) },
                onToggleDesc = { state.toggleSortDirection() },
            )
            Spacer(Modifier.height(8.dp))

            when {
                !hasToken -> Text(
                    "请先在「设置」中填写百度网盘 access_token",
                    color = MaterialTheme.colorScheme.error,
                )

                state.loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

                state.error != null -> Text(
                    "加载失败：${state.error}",
                    color = MaterialTheme.colorScheme.error,
                )

                else -> LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.dirs, key = { it.fsId }) { dir ->
                        DirectoryItem(dir = dir, onClick = {
                            onPick(HlsPaths.toRelativePath(dir.path))
                        })
                    }
                    when {
                        state.loadingMore -> item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator()
                            }
                        }
                        state.loadMoreError != null -> item {
                            TextButton(
                                onClick = { state.loadMore() },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    "加载失败：${state.loadMoreError}，点击重试",
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        !state.hasMore && state.dirs.isEmpty() -> item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "${HlsPaths.BAIDU_MEDIA_ROOT} 下没有找到任何目录",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        !state.hasMore -> item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "已经到底了",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            }
            PullToRefreshContainer(
                state = pullState,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

@Composable
private fun DirectoryItem(dir: BaiduFile, onClick: () -> Unit) {
    val displayName = HlsPaths.toRelativePath(dir.path)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(displayName, style = MaterialTheme.typography.titleMedium)
                Text(
                    dir.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("播放", color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun SortBar(
    order: String,
    desc: Boolean,
    onOrderChange: (String) -> Unit,
    onToggleDesc: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = order == SORT_TIME,
            onClick = { onOrderChange(SORT_TIME) },
            label = { Text("时间") },
        )
        FilterChip(
            selected = order == SORT_NAME,
            onClick = { onOrderChange(SORT_NAME) },
            label = { Text("名称") },
        )
        FilterChip(
            selected = order == SORT_SIZE,
            onClick = { onOrderChange(SORT_SIZE) },
            label = { Text("大小") },
        )
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onToggleDesc) {
            Text(if (desc) "↓ 降序" else "↑ 升序")
        }
    }
}
