package xyz.asitanokibou.player.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import xyz.asitanokibou.player.data.MovieInfo
import xyz.asitanokibou.player.data.MovieInfoClient

@OptIn(UnstableApi::class)
@Composable
internal fun HomeScreen(
    controller: Player?,
    hasToken: Boolean,
    initialPath: String,
    movieInfoClient: MovieInfoClient?,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onFullscreenChanged: (Boolean) -> Unit,
) {
    var path by remember(initialPath) { mutableStateOf(initialPath) }
    // 深链/列表进入即自动播放(initialPath 非空);controller 异步就绪后触发,防重入。
    // 离开播放页(composable 退出组合)后状态自然重置。
    var autoPlayed by remember(initialPath) { mutableStateOf(false) }
    LaunchedEffect(controller, initialPath) {
        val p = initialPath.trim()
        if (controller != null && p.isNotEmpty() && !autoPlayed) {
            autoPlayed = true
            playPath(controller!!, p)
        }
    }
    val status = remember { mutableStateOf<String?>(null) }
    val error = remember { mutableStateOf<String?>(null) }
    var isFullscreen by remember { mutableStateOf(false) }

    // 从列表进入(initialPath 非空)时按目录名(番号)拉取详情;失败静默降级为 null
    var detail by remember(initialPath) { mutableStateOf<MovieInfo?>(null) }
    LaunchedEffect(initialPath, movieInfoClient) {
        detail = null
        val fanCode = initialPath.trim().trim('/')
        if (fanCode.isNotEmpty()) {
            detail = movieInfoClient?.findDetail(fanCode)
        }
    }

    // 播放器状态:空闲(未 prepare)时用于显示封面海报
    var playbackState by remember(controller) {
        mutableStateOf(controller?.playbackState ?: Player.STATE_IDLE)
    }
    val idleCoverUrl = detail?.cover?.takeIf { playbackState == Player.STATE_IDLE }

    LaunchedEffect(isFullscreen) {
        onFullscreenChanged(isFullscreen)
    }

    BackHandler(enabled = isFullscreen) {
        isFullscreen = false
    }

    DisposableEffect(controller) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                playbackState = state
                status.value = when (state) {
                    Player.STATE_IDLE -> "空闲"
                    Player.STATE_BUFFERING -> "缓冲中…"
                    Player.STATE_READY -> "就绪"
                    Player.STATE_ENDED -> "已结束"
                    else -> null
                }
            }
            override fun onPlayerError(error0: androidx.media3.common.PlaybackException) {
                error.value = "${error0.errorCodeName}: ${error0.message ?: ""}"
            }
        }
        controller?.addListener(listener)
        onDispose {
            controller?.removeListener(listener)
            controller?.pause()
        }
    }

    if (isFullscreen) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            HlsPlayerView(
                controller = controller,
                onToggleFullscreen = { isFullscreen = !isFullscreen },
                modifier = Modifier.fillMaxSize(),
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT,
                idleCoverUrl = idleCoverUrl,
            )
        }
    } else {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                val isWide = maxWidth > maxHeight

                if (isWide) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        ) {
                            ControlsPanel(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState()),
                                path = path,
                                onPathChange = { path = it },
                                onPlay = {
                                    error.value = null
                                    controller?.let { playPath(it, path) }
                                },
                                onBack = onBack,
                                onOpenSettings = onOpenSettings,
                                hasToken = hasToken,
                                controller = controller,
                                status = status.value,
                                error = error.value,
                            )
                            detail?.let {
                                Spacer(Modifier.height(12.dp))
                                MovieDetailCard(detail = it)
                            }
                        }
                        Box(
                            modifier = Modifier
                                .weight(1.4f)
                                .fillMaxHeight(),
                            contentAlignment = Alignment.Center,
                        ) {
                            HlsPlayerView(
                                controller = controller,
                                onToggleFullscreen = { isFullscreen = !isFullscreen },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(16f / 9f),
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT,
                                idleCoverUrl = idleCoverUrl,
                            )
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ControlsPanel(
                            modifier = Modifier.fillMaxWidth(),
                            path = path,
                            onPathChange = { path = it },
                            onPlay = {
                                error.value = null
                                controller?.let { playPath(it, path) }
                            },
                            onBack = onBack,
                            onOpenSettings = onOpenSettings,
                            hasToken = hasToken,
                            controller = controller,
                            status = status.value,
                            error = error.value,
                        )
                        detail?.let {
                            Spacer(Modifier.height(12.dp))
                            MovieDetailCard(detail = it)
                        }
                        Spacer(Modifier.padding(4.dp))
                        HlsPlayerView(
                            controller = controller,
                            onToggleFullscreen = { isFullscreen = !isFullscreen },
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f),
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT,
                            idleCoverUrl = idleCoverUrl,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ControlsPanel(
    modifier: Modifier = Modifier,
    path: String,
    onPathChange: (String) -> Unit,
    onPlay: () -> Unit,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    hasToken: Boolean,
    controller: Player?,
    status: String?,
    error: String?,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← 返回") }
                Spacer(Modifier.width(8.dp))
                Text("hls_pan_player", style = MaterialTheme.typography.titleLarge)
            }
            TextButton(onClick = onOpenSettings) { Text("设置") }
        }

        OutlinedTextField(
            value = path,
            onValueChange = onPathChange,
            label = { Text("媒体目录路径，例如 video1") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = onPlay,
            enabled = controller != null && path.isNotBlank(),
        ) {
            Text("播放")
        }

        if (!hasToken) {
            Text(
                "请先在「设置」中填写百度网盘 access_token",
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (controller == null) {
            Text("正在连接播放服务...", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        status?.let {
            Text("状态：$it", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        error?.let {
            Text("播放错误：$it", color = MaterialTheme.colorScheme.error)
        }
    }
}

internal fun playPath(controller: Player, path: String) {
    val item = MediaItem.Builder().setMediaId(path.trim()).build()
    controller.setMediaItem(item)
    controller.prepare()
    controller.play()
}
