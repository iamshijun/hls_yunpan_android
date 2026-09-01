package xyz.asitanokibou.player.ui

import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshContainer
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.drop
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
    onOpenDownloads: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val pullState = rememberPullToRefreshState()
    val snackbarHostState = remember { SnackbarHostState() }
    // 待确认删除的条目（确认框用）
    var pendingDelete by remember { mutableStateOf<MovieListItem?>(null) }
    // 滑动露出模式下当前展开删除按钮的条目 fsId，互斥展开
    var revealedFsId by remember { mutableStateOf<Long?>(null) }

    // 删除失败提示：仅提示，列表保持不动
    LaunchedEffect(state.deleteError) {
        state.deleteError?.let {
            snackbarHostState.showSnackbar("删除失败：$it")
            state.clearDeleteError()
        }
    }

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

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
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
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onOpenDownloads) { Text("下载管理") }
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
                        items(state.items, key = { it.dir.fsId }) { item ->
                            SwipeRevealDeleteBox(
                                item = item,
                                deleting = state.deleting == item.dir.fsId,
                                expanded = revealedFsId == item.dir.fsId,
                                onRevealChanged = { open ->
                                    revealedFsId = if (open) item.dir.fsId else null
                                },
                                onDeleteClick = {
                                    revealedFsId = null
                                    pendingDelete = item
                                },
                                onClick = { onPick(HlsPaths.toRelativePath(item.dir.path)) },
                            )
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
                            !state.hasMore && state.items.isEmpty() -> item {
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

    // 删除确认框：确认后调网盘接口，取消则什么都不做
    pendingDelete?.let { item ->
        DeleteConfirmDialog(
            fanCode = item.fanCode,
            onConfirm = {
                pendingDelete = null
                state.delete(item)
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

private enum class RevealAnchor { Closed, Open }

/**
 * 左滑露出删除按钮（anchoredDraggable，两个锚点：收起 / 露出 88dp）。
 * 左滑露出右侧红色「删除」按钮，点击按钮才请求确认；互斥展开（开一个自动收起其他）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SwipeRevealDeleteBox(
    item: MovieListItem,
    deleting: Boolean,
    expanded: Boolean,
    onRevealChanged: (Boolean) -> Unit,
    onDeleteClick: () -> Unit,
    onClick: () -> Unit,
) {
    val density = LocalDensity.current
    val actionWidth = 88.dp
    val actionWidthPx = with(density) { actionWidth.toPx() }
    val anchors = remember(actionWidthPx) {
        DraggableAnchors {
            RevealAnchor.Closed at 0f
            RevealAnchor.Open at -actionWidthPx
        }
    }
    val dragState = remember(item.dir.fsId, actionWidthPx) {
        AnchoredDraggableState(
            initialValue = if (expanded) RevealAnchor.Open else RevealAnchor.Closed,
            anchors = anchors,
            positionalThreshold = { it * 0.5f },
            velocityThreshold = { with(density) { 300.dp.toPx() } },
            animationSpec = tween(220),
        )
    }
    // 外部展开状态变化（互斥/收起）时同步手势状态
    LaunchedEffect(expanded, item.dir.fsId) {
        val target = if (expanded) RevealAnchor.Open else RevealAnchor.Closed
        if (dragState.currentValue != target) dragState.animateTo(target)
    }
    // 手势结束后把展开状态同步出去（互斥展开用）
    LaunchedEffect(dragState) {
        snapshotFlow { dragState.currentValue }
            .drop(1)
            .collect { value -> onRevealChanged(value == RevealAnchor.Open) }
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        // 背景层：右侧删除按钮（条目左移后露出）
        Box(
            modifier = Modifier
                .matchParentSize()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.error),
            contentAlignment = Alignment.CenterEnd,
        ) {
            TextButton(
                onClick = { if (!deleting) onDeleteClick() },
                modifier = Modifier
                    .width(actionWidth)
                    .fillMaxHeight(),
            ) {
                if (deleting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onError,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("删除", color = MaterialTheme.colorScheme.onError)
                }
            }
        }
        // 前景层：条目本体，随手势平移
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(dragState.requireOffset().roundToInt(), 0) }
                .anchoredDraggable(dragState, Orientation.Horizontal),
        ) {
            DirectoryItem(item = item, deleting = deleting, onClick = onClick)
        }
    }
}

/** 删除确认框：确认后调网盘接口，取消则什么都不做（列表页/播放页共用） */
@Composable
internal fun DeleteConfirmDialog(
    fanCode: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除") },
        text = { Text("确定删除「$fanCode」吗？") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

@Composable
private fun DirectoryItem(
    item: MovieListItem,
    deleting: Boolean,
    onClick: () -> Unit,
) {
    val fanCode = item.fanCode
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !deleting, onClick = onClick),
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
                Text(
                    item.info?.title?.takeIf { it.isNotBlank() } ?: fanCode,
                    style = MaterialTheme.typography.titleMedium,
                )
                val casts = item.info?.casts?.joinToString(", ")
                val subtitle = when {
                    item.info != null && !casts.isNullOrBlank() -> "$fanCode · $casts"
                    item.info != null -> fanCode
                    else -> null
                }
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (deleting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Text("播放", color = MaterialTheme.colorScheme.primary)
            }
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
