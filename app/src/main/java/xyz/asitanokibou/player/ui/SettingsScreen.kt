package xyz.asitanokibou.player.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingsScreen(
    initialToken: String,
    initialMovieApiBaseUrl: String,
    onSave: (token: String, movieApiBaseUrl: String) -> Unit,
    onBack: () -> Unit,
) {
    var token by remember { mutableStateOf(initialToken) }
    var movieApiBaseUrl by remember { mutableStateOf(initialMovieApiBaseUrl) }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
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

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { onSave(token.trim(), movieApiBaseUrl.trim()) }) { Text("保存") }
                TextButton(onClick = onBack) { Text("返回") }
            }

            Text(
                "提示：修改缓存开关需重启播放服务生效；access_token 与影片信息服务地址修改后立即生效。留空服务地址则列表不显示影片详情。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
