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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import xyz.asitanokibou.player.data.MovieInfo
import xyz.asitanokibou.player.data.MovieRepository

@OptIn(UnstableApi::class)
@Composable
internal fun HomeScreen(
    playback: PlaybackController?,
    hasToken: Boolean,
    initialPath: String,
    movieRepository: MovieRepository?,
    doubleTapToSeek: Boolean,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onFullscreenChanged: (Boolean) -> Unit,
) {
    var path by remember(initialPath) { mutableStateOf(initialPath) }
    // 深链/列表进入即自动播放(initialPath 非空);playback 异步就绪后触发,防重入。
    // 离开播放页(composable 退出组合)后状态自然重置。
    var autoPlayed by remember(initialPath) { mutableStateOf(false) }
    LaunchedEffect(playback, initialPath) {
        val p = initialPath.trim()
        if (playback != null && p.isNotEmpty() && !autoPlayed) {
            autoPlayed = true
            playback.play(p)
        }
    }

    val uiState = playback?.let { p -> p.state.collectAsState(initial = p.state.value) }
        ?: remember { mutableStateOf(PlaybackUiState()) }
    val playbackState = uiState.value
    val status = playbackState.status
    val error = playbackState.error
    var isFullscreen by remember { mutableStateOf(false) }

    // 从列表进入(initialPath 非空)时按目录名(番号)拉取详情;失败静默降级为 null
    var detail by remember(initialPath) { mutableStateOf<MovieInfo?>(null) }
    LaunchedEffect(initialPath, movieRepository) {
        detail = null
        val fanCode = initialPath.trim().trim('/')
        if (fanCode.isNotEmpty()) {
            detail = movieRepository?.detail(fanCode)
        }
    }

    // 播放器空闲(未 prepare)时显示封面海报
    val idleCoverUrl = detail?.cover?.takeIf { playbackState.playbackState == Player.STATE_IDLE }
    // 标题栏背景封面:不受 player 状态限制(标题栏在播放期间弹出时也希望有封面)
    val titleBarCoverUrl = detail?.cover
    val fanCode = path.trim().ifBlank { null }
    // 播放器顶部标题
    val playerTitle = if (detail?.title.isNullOrBlank()) {
        fanCode
    } else {
        "$fanCode ${detail?.title}"
    }

    LaunchedEffect(isFullscreen) {
        onFullscreenChanged(isFullscreen)
    }

    BackHandler(enabled = isFullscreen) {
        isFullscreen = false
    }

    // 组合退出(推入设置页等)时暂停保留续播;离开播放页的 stop+clear 由 AppRoot 的 release() 负责
    DisposableEffect(playback) {
        onDispose {
            playback?.pause()
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
                controller = playback?.player,
                onToggleFullscreen = { isFullscreen = !isFullscreen },
                modifier = Modifier.fillMaxSize(),
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT,
                idleCoverUrl = idleCoverUrl,
                title = playerTitle,
                doubleTapToSeek = doubleTapToSeek,
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
                                onPlay = { playback?.play(path) },
                                onBack = onBack,
                                onOpenSettings = onOpenSettings,
                                hasToken = hasToken,
                                playback = playback,
                                status = status,
                                error = error,
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
                                controller = playback?.player,
                                onToggleFullscreen = { isFullscreen = !isFullscreen },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(16f / 9f),
                                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT,
                                idleCoverUrl = idleCoverUrl,
                                title = playerTitle,
                                doubleTapToSeek = doubleTapToSeek,
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
                            onPlay = { playback?.play(path) },
                            onBack = onBack,
                            onOpenSettings = onOpenSettings,
                            hasToken = hasToken,
                            playback = playback,
                            status = status,
                            error = error,
                        )
                        detail?.let {
                            Spacer(Modifier.height(12.dp))
                            MovieDetailCard(detail = it)
                        }
                        Spacer(Modifier.padding(4.dp))
                        HlsPlayerView(
                            controller = playback?.player,
                            onToggleFullscreen = { isFullscreen = !isFullscreen },
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f),
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT,
                            idleCoverUrl = idleCoverUrl,
                            title = playerTitle,
                            doubleTapToSeek = doubleTapToSeek,
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
    playback: PlaybackController?,
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
            enabled = playback != null && path.isNotBlank(),
        ) {
            Text("播放")
        }

        if (!hasToken) {
            Text(
                "请先在「设置」中填写百度网盘 access_token",
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (playback == null) {
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
