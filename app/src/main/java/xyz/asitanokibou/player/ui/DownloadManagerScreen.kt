package xyz.asitanokibou.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import xyz.asitanokibou.player.download.DownloadManager
import xyz.asitanokibou.player.download.DownloadStatus
import xyz.asitanokibou.player.download.DownloadTask
import xyz.asitanokibou.player.download.DownloadedItem
import xyz.asitanokibou.player.download.DownloadedStore

/** 人类可读字节数 */
internal fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.2f GB".format(bytes / 1e9)
    bytes >= 1L shl 20 -> "%.1f MB".format(bytes / 1e6)
    bytes >= 1L shl 10 -> "%.1f KB".format(bytes / 1e3)
    else -> "$bytes B"
}

/**
 * 下载管理页:进行中(暂停/恢复/取消/重试)+ 已完成(删除)。
 * 进行中状态来自 DownloadManager;已完成列表查询 MediaStore(Movies/HlsPan/),
 * 页面进入/删除后刷新。速度由 totalBytes 每秒差分采样得到。
 */
@Composable
internal fun DownloadManagerScreen(
    downloadManager: DownloadManager?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val tasks by (downloadManager?.tasks
        ?.collectAsState()
        ?: remember { mutableStateOf(emptyList<DownloadTask>()) })

    var downloaded by remember { mutableStateOf<List<DownloadedItem>>(emptyList()) }
    var pendingDelete by remember { mutableStateOf<DownloadedItem?>(null) }
    // 速度采样:fanCode -> (上次字节数, 上次时间戳, 当前速度)
    var speeds by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var lastSnapshot by remember { mutableStateOf<Map<String, Pair<Long, Long>>>(emptyMap()) }

    // 已完成列表:进入页面时查询
    LaunchedEffect(Unit) {
        downloaded = runCatching { DownloadedStore.list(context) }.getOrDefault(emptyList())
    }

    // 速度:每秒对 totalBytes 差分
    LaunchedEffect(tasks) {
        while (true) {
            delay(1_000)
            val now = System.currentTimeMillis()
            val nextSpeeds = mutableMapOf<String, Long>()
            val nextSnapshot = mutableMapOf<String, Pair<Long, Long>>()
            for (t in tasks) {
                val prev = lastSnapshot[t.fanCode]
                if (prev != null && t.status == DownloadStatus.RUNNING) {
                    val elapsed = now - prev.second
                    if (elapsed > 0) nextSpeeds[t.fanCode] = (t.totalBytes - prev.first) * 1000L / elapsed
                }
                nextSnapshot[t.fanCode] = t.totalBytes to now
            }
            speeds = nextSpeeds
            lastSnapshot = nextSnapshot
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("← 返回") }
                Spacer(Modifier.width(8.dp))
                Text("下载管理", style = MaterialTheme.typography.titleLarge)
            }

            if (downloadManager == null) {
                Text("下载功能不可用", color = MaterialTheme.colorScheme.error)
                return@Column
            }

            val active = tasks.filter { it.status != DownloadStatus.COMPLETED }
            val done = tasks.filter { it.status == DownloadStatus.COMPLETED }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (active.isEmpty() && done.isEmpty() && downloaded.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(24.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "暂无下载任务",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (active.isNotEmpty()) {
                    item {
                        Text(
                            "进行中",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(active, key = { it.fanCode }) { task ->
                        ActiveTaskItem(
                            task = task,
                            speed = speeds[task.fanCode],
                            onPause = { downloadManager.pause(task.fanCode) },
                            onResume = { downloadManager.resume(task.fanCode) },
                            onRetry = { downloadManager.retry(task.fanCode) },
                            onCancel = { downloadManager.cancel(task.fanCode) },
                        )
                    }
                }
                if (downloaded.isNotEmpty()) {
                    item {
                        Text(
                            "已完成",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    items(downloaded, key = { it.id }) { item ->
                        DownloadedItemRow(
                            item = item,
                            onDelete = { pendingDelete = item },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除") },
            text = { Text("确定删除已下载的「${item.fanCode}」吗?") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    if (DownloadedStore.delete(context, item)) {
                        downloaded = downloaded.filterNot { it.id == item.id }
                    }
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ActiveTaskItem(
    task: DownloadTask,
    speed: Long?,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    Card(shape = RoundedCornerShape(8.dp)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(task.fanCode, style = MaterialTheme.typography.titleMedium)
                    val statusText = when (task.status) {
                        DownloadStatus.QUEUED -> "排队中"
                        DownloadStatus.RUNNING -> "下载中 ${task.completedCount}/${task.totalSegments} 片(${(task.progress * 100).toInt()}%)"
                        DownloadStatus.PAUSED -> "已暂停 ${task.completedCount}/${task.totalSegments} 片"
                        DownloadStatus.FAILED -> "失败:${task.error ?: "未知错误"}"
                        DownloadStatus.COMPLETED -> "已完成"
                    }
                    Text(
                        statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (task.status == DownloadStatus.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    if (task.status == DownloadStatus.RUNNING) {
                        val extra = listOfNotNull(
                            formatBytes(task.totalBytes).takeIf { task.totalBytes > 0 },
                            speed?.takeIf { it > 0 }?.let { "${formatBytes(it)}/s" },
                        ).joinToString(" · ")
                        if (extra.isNotEmpty()) {
                            Text(
                                extra,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                when (task.status) {
                    DownloadStatus.RUNNING, DownloadStatus.QUEUED -> {
                        TextButton(onClick = onPause) { Text("暂停") }
                    }
                    DownloadStatus.PAUSED -> {
                        TextButton(onClick = onResume) { Text("恢复") }
                    }
                    DownloadStatus.FAILED -> {
                        TextButton(onClick = onRetry) { Text("重试") }
                    }
                    DownloadStatus.COMPLETED -> {}
                }
                TextButton(onClick = onCancel) {
                    Text(
                        if (task.status == DownloadStatus.FAILED) "移除" else "取消",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (task.status == DownloadStatus.RUNNING || task.status == DownloadStatus.QUEUED) {
                LinearProgressIndicator(
                    progress = { task.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun DownloadedItemRow(
    item: DownloadedItem,
    onDelete: () -> Unit,
) {
    Card(shape = RoundedCornerShape(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(item.fanCode, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${formatBytes(item.sizeBytes)} · " +
                        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                            .format(Date(item.modifiedAtMillis)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onDelete) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
