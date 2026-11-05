package xyz.asitanokibou.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun SettingsScreen(
    initialToken: String,
    initialMovieApiBaseUrl: String,
    initialBackgroundPlayback: Boolean,
    initialDoubleTapToSeek: Boolean,
    initialAutoPlay: Boolean,
    initialAllowPlaybackOnMetered: Boolean,
    initialAllowDownloadOnMetered: Boolean,
    progressCount: Int,
    onClearProgress: () -> Unit,
    onClearCaches: () -> Unit,
    onSave: (
        token: String,
        movieApiBaseUrl: String,
        backgroundPlayback: Boolean,
        doubleTapToSeek: Boolean,
        autoPlay: Boolean,
        allowPlaybackOnMetered: Boolean,
        allowDownloadOnMetered: Boolean,
    ) -> Unit,
    onBack: () -> Unit,
) {
    var token by remember { mutableStateOf(initialToken) }
    var movieApiBaseUrl by remember { mutableStateOf(initialMovieApiBaseUrl) }
    var backgroundPlayback by remember { mutableStateOf(initialBackgroundPlayback) }
    var doubleTapToSeek by remember { mutableStateOf(initialDoubleTapToSeek) }
    var autoPlay by remember { mutableStateOf(initialAutoPlay) }
    var allowPlaybackOnMetered by remember { mutableStateOf(initialAllowPlaybackOnMetered) }
    var allowDownloadOnMetered by remember { mutableStateOf(initialAllowDownloadOnMetered) }
    var confirmClearCaches by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    if (confirmClearCaches) {
        AlertDialog(
            onDismissRequest = { confirmClearCaches = false },
            title = { Text("清除缓存") },
            text = {
                Text(
                    buildString {
                        append("将立即清除当前内存中的缓存信息：")
                        append("\n• 已缓存的 fsid 信息(下次请求按需重新拉取网盘目录)")
                        if (progressCount > 0) {
                            append("\n• 本机保存的 $progressCount 部影片的观看进度")
                        }
                        append("\n\n稍后再看、下载任务与设置不受影响。")
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClearCaches = false
                        onClearCaches()
                        scope.launch { snackbarHostState.showSnackbar("缓存已清除") }
                    },
                ) { Text("清除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearCaches = false }) { Text("取消") }
            },
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("设置", style = MaterialTheme.typography.titleLarge)

            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("百度网盘 access_token") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = movieApiBaseUrl,
                onValueChange = { movieApiBaseUrl = it },
                label = { Text("影片信息服务地址") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("后台播放", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "关闭后，离开应用(按 Home 键/锁屏/切后台)将暂停播放；从最近任务划掉应用时停止播放。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = backgroundPlayback,
                    onCheckedChange = { backgroundPlayback = it },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("双击快进/后退", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "开启后，双击播放器左/右半屏为快退/快进 10 秒；关闭时双击为播放/暂停。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = doubleTapToSeek,
                    onCheckedChange = { doubleTapToSeek = it },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("自动播放", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "开启后，从列表点播或通过 hlspan:// 链接进入播放页时立即开始播放；" +
                            "关闭后停在封面，需手动点击播放。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = autoPlay,
                    onCheckedChange = { autoPlay = it },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("移动数据下允许播放", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "关闭时，移动数据(含计费 WiFi、热点共享、漫游)下不会开始播放；" +
                            "正在播放的会暂停，回到 WiFi 后自动续播。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = allowPlaybackOnMetered,
                    onCheckedChange = { allowPlaybackOnMetered = it },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("移动数据下允许下载", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "关闭时，移动数据(含计费 WiFi、热点共享、漫游)下不会开始下载；" +
                            "进行中的任务会暂停，回到 WiFi 后自动继续。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = allowDownloadOnMetered,
                    onCheckedChange = { allowDownloadOnMetered = it },
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("播放记录", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (progressCount > 0) {
                            "本机保存了 $progressCount 部影片的观看进度，再次播放会自动从上次位置继续。"
                        } else {
                            "暂无播放记录。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onClearProgress, enabled = progressCount > 0) {
                    Text("清除")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("清除缓存", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "立即清空内存中的缓存信息(fsid 缓存与播放进度)，下次请求会按需重新拉取；" +
                            "不会删除稍后再看、下载任务与设置。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { confirmClearCaches = true }) { Text("清除缓存") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        onSave(
                            token.trim(),
                            movieApiBaseUrl.trim(),
                            backgroundPlayback,
                            doubleTapToSeek,
                            autoPlay,
                            allowPlaybackOnMetered,
                            allowDownloadOnMetered,
                        )
                    },
                ) { Text("保存") }
                TextButton(onClick = onBack) { Text("返回") }
            }

            Text(
                "提示：修改缓存开关需重启播放服务生效；access_token、影片信息服务地址、后台播放、双击快进/后退、自动播放与两个移动数据开关修改后立即生效。留空服务地址则列表不显示影片详情。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
