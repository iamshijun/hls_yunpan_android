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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import xyz.asitanokibou.player.download.DownloadManager
import xyz.asitanokibou.player.download.DownloadStatus
import xyz.asitanokibou.player.download.DownloadTask
import xyz.asitanokibou.player.watchlater.WatchLaterEntry
import xyz.asitanokibou.player.watchlater.WatchLaterStore
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
@Composable
internal fun HomeScreen(
    playback: PlaybackController?,
    hasToken: Boolean,
    initialPath: String,
    movieRepository: MovieRepository?,
    downloadManager: DownloadManager?,
    watchLaterStore: WatchLaterStore?,
    doubleTapToSeek: Boolean,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDownloads: () -> Unit,
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

    // 删除：与列表页同一条链路（确认框 -> 网盘删除 -> 同步删影片服务端记录）
    var pendingDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun performDelete() {
        val code = fanCode ?: return
        val repo = movieRepository ?: return
        deleting = true
        deleteError = null
        scope.launch {
            try {
                // 与列表删除一致:网盘删除成功后再同步删影片服务端记录(失败静默,不影响结果)
                repo.deleteDirectory(code)
                repo.deleteRemoteInfo(code)
                // 删除即从稍后再看清掉,避免僵尸条目(失败静默)
                watchLaterStore?.let { runCatching { it.remove(code) } }
                deleting = false
                // 删除成功后离开播放页(视频已不存在,停留无意义)
                onBack()
            } catch (e: Exception) {
                deleting = false
                deleteError = e.message ?: e::class.java.simpleName
            }
        }
    }

    // 详情可删除标识:有番号、仓库可用且有 token 时才展示删除入口
    val deleteEnabled = fanCode != null && movieRepository != null && hasToken

    // 下载:任务状态来自 DownloadManager;入队后拉起前台服务
    val downloadTasks by (downloadManager?.tasks?.collectAsState()
        ?: remember { mutableStateOf(emptyList<DownloadTask>()) })
    val downloadTask = fanCode?.let { code -> downloadTasks.firstOrNull { it.fanCode == code } }
    var downloadError by remember { mutableStateOf<String?>(null) }
    fun enqueueDownload() {
        val code = fanCode ?: return
        val manager = downloadManager ?: return
        if (!hasToken) {
            downloadError = "请先在「设置」中填写 access_token"
            return
        }
        scope.launch {
            val rejected = manager.enqueue(code)
            if (rejected != null) {
                downloadError = rejected
            } else {
                downloadError = null
            }
        }
    }

    LaunchedEffect(isFullscreen) {
        onFullscreenChanged(isFullscreen)
    }

    // 删除/下载等页内错误提示统一走 Snackbar(菜单里没有放错误文案的地方)
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(deleteError) {
        deleteError?.let {
            snackbarHostState.showSnackbar("删除失败：$it")
            deleteError = null
        }
    }
    LaunchedEffect(downloadError) {
        downloadError?.let {
            snackbarHostState.showSnackbar("下载失败：$it")
            downloadError = null
        }
    }

    // 稍后再看:状态来自 WatchLaterStore(重启保留);菜单项 toggle 加/删,Snackbar 反馈
    val watchLaterEntries by (watchLaterStore?.entriesFlow?.collectAsState(initial = emptyList())
        ?: remember { mutableStateOf(emptyList<WatchLaterEntry>()) })
    val watchLaterAdded = fanCode != null && watchLaterEntries.any { it.fanCode == fanCode }
    val watchLaterEnabled = fanCode != null && watchLaterStore != null
    fun toggleWatchLater() {
        val code = fanCode ?: return
        val store = watchLaterStore ?: return
        scope.launch {
            if (watchLaterAdded) {
                store.remove(code)
                snackbarHostState.showSnackbar("已从稍后再看移除")
            } else {
                store.add(code)
                snackbarHostState.showSnackbar("已加入稍后再看")
            }
        }
    }

    // 下载菜单项文字:无任务→「下载」;有任务→按状态显示(不带百分比)
    val downloadMenuLabel = when (downloadTask?.status) {
        null -> "下载"
        DownloadStatus.QUEUED -> "排队中…"
        DownloadStatus.RUNNING -> "下载中"
        DownloadStatus.PAUSED -> "已暂停"
        DownloadStatus.FAILED -> "下载失败"
        DownloadStatus.COMPLETED -> "已下载"
    }
    // 菜单项启用与下载入口一致:有番号、播放服务已连接、路径非空才允许入队
    val downloadEnabled = fanCode != null && playback != null && path.isNotBlank()

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
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { innerPadding ->
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
                                watchLaterAdded = watchLaterAdded,
                                watchLaterEnabled = watchLaterEnabled,
                                onToggleWatchLater = { toggleWatchLater() },
                                downloadMenuLabel = downloadMenuLabel,
                                downloadEnabled = downloadEnabled,
                                downloadTask = downloadTask,
                                onDownloadClick = { enqueueDownload() },
                                onOpenDownloads = onOpenDownloads,
                                deleteEnabled = deleteEnabled,
                                deleting = deleting,
                                onDeleteClick = { pendingDelete = true },
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
                            watchLaterAdded = watchLaterAdded,
                            watchLaterEnabled = watchLaterEnabled,
                            onToggleWatchLater = { toggleWatchLater() },
                            downloadMenuLabel = downloadMenuLabel,
                            downloadEnabled = downloadEnabled,
                            downloadTask = downloadTask,
                            onDownloadClick = { enqueueDownload() },
                            onOpenDownloads = onOpenDownloads,
                            deleteEnabled = deleteEnabled,
                            deleting = deleting,
                            onDeleteClick = { pendingDelete = true },
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

    // 删除确认框:与列表页共用同一组件
    if (pendingDelete && fanCode != null) {
        DeleteConfirmDialog(
            fanCode = fanCode,
            onConfirm = {
                pendingDelete = false
                performDelete()
            },
            onDismiss = { pendingDelete = false },
        )
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
    watchLaterAdded: Boolean,
    watchLaterEnabled: Boolean,
    onToggleWatchLater: () -> Unit,
    downloadMenuLabel: String,
    downloadEnabled: Boolean,
    downloadTask: DownloadTask?,
    onDownloadClick: () -> Unit,
    onOpenDownloads: () -> Unit,
    deleteEnabled: Boolean,
    deleting: Boolean,
    onDeleteClick: () -> Unit,
) {
    // 右上角 ⋮ 菜单:稍后再看 / 下载 / 删除 / 设置(仅在非全屏页显示,全屏由 HomeScreen 分支处理)
    var menuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("← 返回") }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "更多操作")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(if (watchLaterAdded) "取消稍后再看" else "稍后再看") },
                        enabled = watchLaterEnabled,
                        onClick = {
                            menuExpanded = false
                            onToggleWatchLater()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(downloadMenuLabel) },
                        enabled = downloadEnabled,
                        onClick = {
                            menuExpanded = false
                            // 无任务 → 入队;有任务 → 跳下载管理页看进度/状态
                            if (downloadTask == null) onDownloadClick() else onOpenDownloads()
                        },
                    )
                    if (deleteEnabled) {
                        DropdownMenuItem(
                            text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                            enabled = !deleting,
                            onClick = {
                                menuExpanded = false
                                onDeleteClick()
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("设置") },
                        onClick = {
                            menuExpanded = false
                            onOpenSettings()
                        },
                    )
                }
            }
        }

        OutlinedTextField(
            value = path,
            onValueChange = onPathChange,
            label = { Text("媒体目录路径，例如 video1") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onPlay,
                enabled = playback != null && path.isNotBlank(),
            ) {
                Text("播放")
            }
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
