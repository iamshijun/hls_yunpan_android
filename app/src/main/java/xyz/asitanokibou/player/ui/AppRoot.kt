package xyz.asitanokibou.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import xyz.asitanokibou.player.config.AppConfig
import xyz.asitanokibou.player.config.AppSettings
import xyz.asitanokibou.player.core.MovieWebUrls
import xyz.asitanokibou.player.data.MovieRepository
import xyz.asitanokibou.player.download.DownloadManager
import xyz.asitanokibou.player.ui.navigation.Screen
import xyz.asitanokibou.player.ui.navigation.rememberNavState
import xyz.asitanokibou.player.watchlater.WatchLaterStore

@Composable
fun AppRoot(
    playback: PlaybackController? = null,
    settings: AppSettings,
    movieRepository: MovieRepository? = null,
    downloadManager: DownloadManager? = null,
    watchLaterStore: WatchLaterStore? = null,
    onFullscreenChanged: (Boolean) -> Unit = {},
    deepLinkPath: String? = null,
    deepLinkSeq: Int = 0,
) {
    val nav = rememberNavState()
    val scope = rememberCoroutineScope()
    val config by settings.configFlow.collectAsState(initial = AppConfig())
    val hasToken = !config.accessToken.isNullOrBlank()
    val colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()

    // 播放页全屏状态:提升到 AppRoot 持有,分屏时全屏要整屏盖住左栏;
    // 同时避免播放页随全屏分支切换组合槽位导致内部状态(路径/详情)丢失。
    var playFullscreen by remember { mutableStateOf(false) }

    // 左栏页(首页/列表/网页/稍后再看)的常驻槽内容。
    // 左栏槽在组合树中位置固定、整个 App 会话不销毁:进出分屏/全屏/设置页都不重建,
    // 网页永不重载、列表滚动位置永久保留;被播放页/设置页覆盖时只是不可见。
    // 记录"最后访问的左栏页":current 是左栏页时同步为 current,否则保持上次值。
    var paneScreen by remember { mutableStateOf<Screen>(Screen.Index) }
    LaunchedEffect(nav.current) {
        val c = nav.current
        if (c is Screen.Index || c is Screen.MovieList || c is Screen.Web || c is Screen.WatchLater) {
            paneScreen = c
        }
    }

    val movieListState = remember(movieRepository) {
        movieRepository?.let { MovieListState(it, watchLaterStore, scope) }
    }

    // 稍后再看列表页状态:进页面时按番号实时拉详情
    val watchLaterState = remember(watchLaterStore, movieRepository) {
        if (watchLaterStore != null && movieRepository != null) {
            WatchLaterState(watchLaterStore, movieRepository, scope)
        } else {
            null
        }
    }

    // 深链进入:每次收到深链 intent 都重置导航栈直达播放页(无应用内上一页;返回时回首页,栈不被污染)。
    // key 必须含 deepLinkSeq:重复调起同一 code 时 deepLinkPath 值不变,单独作 key 会漏触发。
    LaunchedEffect(deepLinkPath, deepLinkSeq) {
        val p = deepLinkPath?.trim()?.trim('/')
        if (!p.isNullOrEmpty()) nav.reset(Screen.Play(initialPath = p))
    }

    // 离开播放页:有应用内历史则 pop 回上一页;深链直达(无历史)时回 App 首页(Index),
    // 避免按返回直接退出到桌面。
    // 清空 player 是为了避免上一部影片的帧与状态残留(推入设置页不清,保留续播)。
    fun exitPlay() {
        playback?.release()
        playFullscreen = false
        if (nav.canPop) {
            nav.pop()
        } else {
            nav.reset(Screen.Index)
        }
    }

    // 「从网页浏览影片」入口的打开逻辑:地址可用就推入 Web 页,未配置(服务地址为空)时引导去「设置」
    fun openMovieWeb(url: String?) {
        if (url != null) nav.push(Screen.Web(url)) else nav.push(Screen.Settings)
    }

    // 播放页(含深链直达)与有历史栈的页面拦截返回;首页(Index,无历史)不拦截 → 系统默认退出
    BackHandler(enabled = nav.current is Screen.Play || nav.canPop) {
        if (nav.current is Screen.Play) exitPlay() else nav.pop()
    }

    // 播放页全屏时,返回键先退出全屏(后组合的 handler 优先,保证全屏态先于弹栈处理)
    BackHandler(enabled = playFullscreen) {
        playFullscreen = false
    }

    MaterialTheme(colorScheme = colorScheme) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // 平板横屏:横屏(宽>高)且短边 ≥ 600dp(等效 sw600dp 平板门槛,手机横屏不触发)
            val landscapeTablet = maxWidth > maxHeight && maxHeight >= 600.dp
            val current = nav.current
            val previous = nav.previous
            // 分屏(master-detail):平板横屏 + 当前为播放页 + 有可保留的上一页
            // (首页/列表/网页/稍后再看)。设置/下载管理页不会出现在播放页下方,防御性排除。
            val split = landscapeTablet && current is Screen.Play && previous != null &&
                (previous is Screen.Index || previous is Screen.MovieList ||
                    previous is Screen.Web || previous is Screen.WatchLater)

            // 左栏槽是否处于前台(可交互):单屏显示左栏页,或分屏作为左栏
            val paneTop = split || current is Screen.Index || current is Screen.MovieList ||
                current is Screen.Web || current is Screen.WatchLater

            // 播放页:单屏(全窗)/分屏右栏共用
            @Composable
            fun PlayPage() {
                val play = current as Screen.Play
                HomeScreen(
                    playback = playback,
                    hasToken = hasToken,
                    initialPath = play.initialPath,
                    movieApiBaseUrl = config.movieApiBaseUrl,
                    movieRepository = movieRepository,
                    downloadManager = downloadManager,
                    watchLaterStore = watchLaterStore,
                    doubleTapToSeek = config.doubleTapToSeek,
                    isFullscreen = playFullscreen,
                    onToggleFullscreen = { playFullscreen = !playFullscreen },
                    onBack = { exitPlay() },
                    onOpenSettings = { nav.push(Screen.Settings) },
                    onOpenDownloads = { nav.push(Screen.DownloadManager) },
                    // 详情卡里的演员名:走与「从网页浏览影片」同一个入口,只多带一个 ?cast= 参数
                    onOpenCastWeb = { cast ->
                        openMovieWeb(MovieWebUrls.castFilter(config.movieApiBaseUrl, cast))
                    },
                    onFullscreenChanged = onFullscreenChanged,
                )
            }

            // 左栏页渲染(常驻槽):单屏显示时 splitMode=false;分屏左栏时 splitMode=true。
            // 分屏时左栏的 onPick/onOpenPlay 改为 replaceCurrent:不推栈,直接切换右栏播放。
            @Composable
            fun PaneScreen(screen: Screen, splitMode: Boolean, isTop: Boolean) {
                when (screen) {
                    is Screen.Index -> {
                        // 网页入口地址:由设置中的「影片信息服务地址」+ /index.html 拼出,未配置时为 null
                        val webUrl = MovieWebUrls.index(config.movieApiBaseUrl)
                        IndexScreen(
                            onOpenList = { nav.push(Screen.MovieList) },
                            onOpenDirect = { nav.push(Screen.Play(initialPath = "")) },
                            onOpenSettings = { nav.push(Screen.Settings) },
                            movieWebUrl = webUrl,
                            onOpenMovieWeb = { openMovieWeb(webUrl) },
                            onOpenDownloads = { nav.push(Screen.DownloadManager) },
                            onOpenWatchLater = { nav.push(Screen.WatchLater) },
                        )
                    }
                    is Screen.MovieList -> {
                        if (movieListState != null) {
                            MovieListScreen(
                                hasToken = hasToken,
                                state = movieListState,
                                onBack = { nav.pop() },
                                onPick = { relativePath ->
                                    if (splitMode) {
                                        nav.replaceCurrent(Screen.Play(initialPath = relativePath))
                                    } else {
                                        nav.push(Screen.Play(initialPath = relativePath))
                                    }
                                },
                                splitMode = splitMode,
                            )
                        }
                    }
                    is Screen.Web -> MovieWebScreen(
                        url = screen.url,
                        onBack = { nav.pop() },
                        // 网页内点 hlspan://play/<番号>:进程内进入播放页(分屏时替换右栏)
                        onOpenPlay = { code ->
                            if (splitMode) {
                                nav.replaceCurrent(Screen.Play(initialPath = code))
                            } else {
                                nav.push(Screen.Play(initialPath = code))
                            }
                        },
                        splitMode = splitMode,
                        isTop = isTop,
                    )
                    is Screen.WatchLater -> {
                        if (watchLaterState != null) {
                            WatchLaterScreen(
                                state = watchLaterState,
                                onBack = { nav.pop() },
                                onPick = { relativePath ->
                                    if (splitMode) {
                                        nav.replaceCurrent(Screen.Play(initialPath = relativePath))
                                    } else {
                                        nav.push(Screen.Play(initialPath = relativePath))
                                    }
                                },
                                splitMode = splitMode,
                            )
                        }
                    }
                    else -> Unit // 播放/设置/下载管理不经过左栏槽渲染
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                // 层1:左栏页常驻槽(永不销毁)。分屏时占左半,否则全屏(被上层覆盖时不可见但保持状态)
                Box(
                    modifier = if (split) {
                        Modifier
                            .fillMaxWidth(0.5f)
                            .fillMaxHeight()
                    } else {
                        Modifier.fillMaxSize()
                    },
                ) {
                    PaneScreen(screen = paneScreen, splitMode = split, isTop = paneTop)
                }

                // 层2:前台内容(播放页/设置/下载管理)
                when (current) {
                    is Screen.Play -> {
                        // 播放页:分屏时占右半(全屏态整屏盖住左栏),单屏时全屏
                        Box(
                            modifier = if (split && !playFullscreen) {
                                Modifier
                                    .fillMaxWidth(0.5f)
                                    .fillMaxHeight()
                                    .align(Alignment.CenterEnd)
                            } else {
                                Modifier.fillMaxSize()
                            },
                        ) {
                            PlayPage()
                        }
                    }
                    is Screen.Settings -> {
                        Box(modifier = Modifier.fillMaxSize()) {
                            SettingsScreen(
                                initialToken = config.accessToken ?: "",
                                initialMovieApiBaseUrl = config.movieApiBaseUrl,
                                initialBackgroundPlayback = config.backgroundPlayback,
                                initialDoubleTapToSeek = config.doubleTapToSeek,
                                onSave = { token, movieApiBaseUrl, backgroundPlayback, doubleTapToSeek ->
                                    scope.launch {
                                        settings.update(
                                            config.copy(
                                                accessToken = token,
                                                movieApiBaseUrl = movieApiBaseUrl,
                                                backgroundPlayback = backgroundPlayback,
                                                doubleTapToSeek = doubleTapToSeek,
                                            )
                                        )
                                    }
                                    nav.pop()
                                },
                                onBack = { nav.pop() },
                            )
                        }
                    }
                    is Screen.DownloadManager -> {
                        Box(modifier = Modifier.fillMaxSize()) {
                            DownloadManagerScreen(
                                downloadManager = downloadManager,
                                onBack = { nav.pop() },
                            )
                        }
                    }
                    else -> Unit // 左栏页由层1 显示
                }
            }
        }
    }
}
