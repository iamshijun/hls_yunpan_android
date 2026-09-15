package xyz.asitanokibou.player.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import xyz.asitanokibou.player.core.HlsPaths

@Composable
fun IndexScreen(
    onOpenList: () -> Unit,
    onOpenDirect: () -> Unit,
    onOpenSettings: () -> Unit,
    /** 网页入口目标地址(影片信息服务地址 + /index.html);设置中未配置时为 null */
    movieWebUrl: String?,
    onOpenMovieWeb: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenWatchLater: () -> Unit,
) {
    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onOpenWatchLater) { Text("稍后再看") }
                TextButton(onClick = onOpenDownloads) { Text("下载管理") }
                TextButton(onClick = onOpenSettings) { Text("设置") }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "选择进入方式",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            EntryCard(
                title = "从列表选择",
                subtitle = "浏览 ${HlsPaths.BAIDU_MEDIA_ROOT} 下的所有目录",
                onClick = onOpenList,
            )
            EntryCard(
                title = "从网页浏览影片",
                subtitle = movieWebUrl?.let { "通过 WebView 打开影片信息服务首页：$it" }
                    ?: "未配置影片信息服务地址，点击前往「设置」填写",
                onClick = onOpenMovieWeb,
            )
            EntryCard(
                title = "直接输入媒体名",
                subtitle = "手动输入目录名后播放",
                onClick = onOpenDirect,
            )
        }
    }
}

@Composable
private fun EntryCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
