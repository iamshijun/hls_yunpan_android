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
    // 影片详情(列表/深链进入时按番号拉取);声明提前以便自动播放的 effect 闭包能引用
    var detail by remember(initialPath) { mutableStateOf<MovieInfo?>(null) }
    LaunchedEffect(playback, initialPath) {
        val p = initialPath.trim()
        if (playback != null && p.isNotEmpty() && !autoPlayed) {
            autoPlayed = true
            // 自动播放时把已加载的影片详情一起传入,通知 MediaStyle 才能显示封面
            playback.play(p, detail)
        }
    }

    val uiState = playback?.let { p -> p.state.collectAsState(initial = p.state.value) }
        ?: remember { mutableStateOf(PlaybackUiState()) }
    val playbackState = uiState.value
    val status = playbackState.status
    val error = playbackState.error
    var isFullscreen by remember { mutableStateOf(false) }

    // 按当前输入的目录名(番号)拉取详情;initialPath 之外,用户手动改路径时也会重拉。
    // 失败静默降级为 null。注意:与 onPlay 解耦——此处只负责把详情写入 detail,
    // MediaItem 的初始元数据由 onPlay 显式传入,详情异步到达时由下方的 updateMetadata 兜底。
    LaunchedEffect(path, movieRepository) {
        val fanCode = path.trim().trim('/')
        detail = null
        if (fanCode.isNotEmpty()) {
            detail = movieRepository?.detail(fanCode)
        }
    }

    // 详情异步就绪后回填到当前 media item:深链/列表进入时,自动播放的 effect 往往先于详情
    // HTTP 响应触发,此时 metadata 还没拿到,通知就出不了封面;直接输入模式下,用户也可能
    // 在详情到达前先点了播放。这里用 replaceMediaItem 保留播放位置地把 metadata 补上。
    // 仅在 mediaId 与当前路径一致时回填(手动改路径后旧 detail 不应污染新视频)。
    LaunchedEffect(playback, detail, path) {
        val p = path.trim()
        if (playback != null && p.isNotEmpty() && detail != null &&
            playback.player.currentMediaItem?.mediaId == p
        ) {
            playback.updateMetadata(p, detail)
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
                                onPlay = { playback?.play(path, detail) },
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
                            onPlay = { playback?.play(path, detail) },
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
