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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import xyz.asitanokibou.player.data.MovieInfo
import xyz.asitanokibou.player.watchlater.WatchLaterEntry

/**
 * 稍后再看列表页:条目行样式与视频目录列表页(MovieListScreen)一致——
 * 标题(详情未到/未配置时回退番号)+ "番号 · 演员" 副标题;点条目进播放页,
 * 右侧「移除」按钮直接移除(低风险操作,不加确认框)。空列表显示空态文案。
 */
@Composable
internal fun WatchLaterScreen(
    state: WatchLaterState,
    onBack: () -> Unit,
    onPick: (relativePath: String) -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { state.load() }

    // 移除失败提示:仅提示,列表已按本地真实状态回滚
    LaunchedEffect(state.removeError) {
        state.removeError?.let {
            snackbarHostState.showSnackbar("移除失败：$it")
            state.clearRemoveError()
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("← 返回") }
                Spacer(Modifier.width(8.dp))
                Text("稍后再看", style = MaterialTheme.typography.titleLarge)
            }
            Spacer(Modifier.height(8.dp))

            if (state.items.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "暂无稍后再看",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.items, key = { it.fanCode }) { entry ->
                        WatchLaterRow(
                            entry = entry,
                            info = state.details[entry.fanCode],
                            removing = state.removing == entry.fanCode,
                            onClick = { onPick(entry.fanCode) },
                            onRemove = { state.remove(entry) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WatchLaterRow(
    entry: WatchLaterEntry,
    info: MovieInfo?,
    removing: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !removing, onClick = onClick),
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
                    info?.title?.takeIf { it.isNotBlank() } ?: entry.fanCode,
                    style = MaterialTheme.typography.titleMedium,
                )
                val casts = info?.casts?.joinToString(", ")
                val subtitle = when {
                    info != null && !casts.isNullOrBlank() -> "${entry.fanCode} · $casts"
                    info != null -> entry.fanCode
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
            if (removing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                TextButton(onClick = onRemove) {
                    Text("移除", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
