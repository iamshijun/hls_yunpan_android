package xyz.asitanokibou.player.ui

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.clickable
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
import xyz.asitanokibou.player.core.NetworkGate
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
 * 页面进入/任务完成/删除后刷新。缺片数来自 [DownloadedMetaStore] 侧车(MediaStore
 * 里没有这个字段),无记录的条目就不显示。速度由 totalBytes 每秒差分采样得到。
 */
@Composable
internal fun DownloadManagerScreen(
    downloadManager: DownloadManager?,
    /** 移动数据闸门:null 表示不装闸门(行为与旧版一致) */
    networkGate: NetworkGate?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    // 计费网络下待确认的恢复(存番号,不存 Bool)
    var pendingMeteredResume by remember { mutableStateOf<String?>(null) }
    val tasks by (downloadManager?.tasks
        ?.collectAsState()
        ?: remember { mutableStateOf(emptyList<DownloadTask>()) })

    fun resumeTask(fanCode: String) {
        val rejected = downloadManager?.resume(fanCode)
        // 兜底拒绝(不该发生:UI 已经拦在前面了),给用户一句实在话
        if (rejected != null) Toast.makeText(context, rejected, Toast.LENGTH_SHORT).show()
    }

    var downloaded by remember { mutableStateOf<List<DownloadedItem>>(emptyList()) }
    var pendingDelete by remember { mutableStateOf<DownloadedItem?>(null) }
    // 待确认的取消/移除:取消会连工作目录(可能是几 GB 的 .part)一起删,误触代价太大
    var pendingCancel by remember { mutableStateOf<DownloadTask?>(null) }
    // 速度采样:fanCode -> (上次字节数, 上次时间戳, 当前速度)
    var speeds by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var lastSnapshot by remember { mutableStateOf<Map<String, Pair<Long, Long>>>(emptyMap()) }

    // 已完成列表:进入页面时查询;有任务新落到 COMPLETED 时再查一次 —— 否则挂在本页等下载
    // 跑完的话,Mediastore 里已经发布的新条目不会出现在列表里(必须退出重进才看得到)。
    // 键取「已完成番号的集合」而不是 tasks:后者每下完一片就变一次,会把查询打成每秒几十次。
    val completedCodes = tasks.filter { it.status == DownloadStatus.COMPLETED }.map { it.fanCode }
    LaunchedEffect(completedCodes) {
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
                            onResume = {
                                // 恢复入口:计费网络且未放行 → 先问「本次继续 / 取消」
                                if (networkGate != null && !networkGate.canUseMeteredDownload(task.fanCode)) {
                                    pendingMeteredResume = task.fanCode
                                } else {
                                    resumeTask(task.fanCode)
                                }
                            },
                            onRetry = { downloadManager.retry(task.fanCode) },
                            onSkipSegment = { downloadManager.skipMissingSegment(task.fanCode) },
                            onCancel = {
                                // 有进度才值得拦一道;0 片的任务误触也没损失
                                if (task.completedCount > 0 || task.totalBytes > 0) {
                                    pendingCancel = task
                                } else {
                                    downloadManager.cancel(task.fanCode)
                                }
                            },
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
                            onPlay = { playDownloaded(context, item) },
                            onDelete = { pendingDelete = item },
                        )
                    }
                }
            }
        }
    }

    pendingMeteredResume?.let { code ->
        MeteredNetworkDialog(
            action = "下载",
            onContinue = {
                pendingMeteredResume = null
                networkGate?.allowDownload(code)
                resumeTask(code)
            },
            onDismiss = { pendingMeteredResume = null },
        )
    }

    // 对话框开着的时候任务自己走了(下完了 / 被别的入口取消) → 关掉,别对着幽灵任务确认
    LaunchedEffect(pendingCancel, tasks) {
        val code = pendingCancel?.fanCode ?: return@LaunchedEffect
        if (tasks.none { it.fanCode == code }) pendingCancel = null
    }

    pendingCancel?.let { task ->
        val isRemove = task.status == DownloadStatus.FAILED
        val progressText = "已下载 ${task.completedCount}/${task.totalSegments} 片" +
            if (task.totalBytes > 0) "(${formatBytes(task.totalBytes)})" else ""
        val body = if (isRemove) {
            "「${task.fanCode}」$progressText。\n移除后已下载的进度会被一并删除,且无法恢复。"
        } else {
            "「${task.fanCode}」$progressText。\n取消后已下载的进度会被一并删除,且无法恢复。\n" +
                "想稍后再下,请用「暂停」。"
        }
        AlertDialog(
            onDismissRequest = { pendingCancel = null },
            title = { Text(if (isRemove) "移除任务" else "取消下载") },
            text = { Text(body) },
            confirmButton = {
                TextButton(onClick = {
                    pendingCancel = null
                    downloadManager?.cancel(task.fanCode)
                }) {
                    Text(
                        if (isRemove) "移除" else "仍要取消",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingCancel = null }) {
                    Text(if (isRemove) "保留" else "继续下载")
                }
            },
        )
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
    onSkipSegment: () -> Unit,
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
                        DownloadStatus.RUNNING -> "下载中 ${task.resolvedCount}/${task.totalSegments} 片(${(task.progress * 100).toInt()}%)" +
                            // 挂机几小时下大文件时,「到底跳了多少片」不该只能等到结束才知道
                            (if (task.skippedCount > 0) " · 已跳过 ${task.skippedCount} 片" else "")
                        DownloadStatus.PAUSED ->
                            (if (task.pausedByGate) "已暂停(移动数据) " else "已暂停 ") +
                                "${task.resolvedCount}/${task.totalSegments} 片"
                        DownloadStatus.FAILED -> "失败:${task.error ?: "未知错误"}"
                        DownloadStatus.MISSING_SEGMENT ->
                            "缺片待处理:${task.error ?: "分片不存在"}(${task.resolvedCount}/${task.totalSegments} 片)"
                        DownloadStatus.COMPLETED -> "已完成"
                    }
                    Text(
                        statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (task.status == DownloadStatus.FAILED ||
                            task.status == DownloadStatus.MISSING_SEGMENT
                        ) {
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
                    // 处理入口在下方(跳过分片 / 重试),这里只留「取消」
                    DownloadStatus.MISSING_SEGMENT, DownloadStatus.COMPLETED -> {}
                }
                TextButton(onClick = onCancel) {
                    Text(
                        if (task.status == DownloadStatus.FAILED) "移除" else "取消",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (task.status == DownloadStatus.RUNNING || task.status == DownloadStatus.QUEUED ||
                task.status == DownloadStatus.MISSING_SEGMENT
            ) {
                LinearProgressIndicator(
                    progress = { task.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (task.status == DownloadStatus.MISSING_SEGMENT) {
                Text(
                    "跳过后继续下载剩余分片:缺失的分片只是让播放画面不连续,不影响整体播放。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(onClick = onRetry) { Text("重试") }
                    TextButton(onClick = onSkipSegment) { Text("跳过分片") }
                }
            }
        }
    }
}

@Composable
private fun DownloadedItemRow(
    item: DownloadedItem,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay),
        shape = RoundedCornerShape(8.dp),
    ) {
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
                // 有缺片才提到;无记录(包括功能上线前下载的老文件)= 什么都不说
                if (item.skippedCount > 0) {
                    Text(
                        "缺 ${item.skippedCount} 片" +
                            (if (item.totalSegments > 0) "(共 ${item.totalSegments} 片)" else ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            TextButton(onClick = onPlay) {
                Text("播放")
            }
            TextButton(onClick = onDelete) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/**
 * 通过系统应用播放已下载文件:ACTION_VIEW + MediaStore content URI。
 * 先按 video/mp2t 精确匹配,无应用可处理时回退 video 通配;
 * 都找不到可处理的应用时弹 Toast 提示。
 */
private fun playDownloaded(context: Context, item: DownloadedItem) {
    val uri = ContentUris.withAppendedId(
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        item.id,
    )
    fun launch(mime: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (intent.resolveActivity(context.packageManager) == null) return false
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Toast.makeText(context, "无法打开播放器: ${e.message}", Toast.LENGTH_SHORT).show()
            true
        }
    }
    if (!launch(DownloadedStore.MIME_TS) && !launch("video/*")) {
        Toast.makeText(context, "未找到可播放该文件的系统应用", Toast.LENGTH_SHORT).show()
    }
}
