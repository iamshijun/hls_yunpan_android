package xyz.asitanokibou.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import xyz.asitanokibou.player.config.AppConfig
import xyz.asitanokibou.player.config.AppSettings
import xyz.asitanokibou.player.data.MovieRepository
import xyz.asitanokibou.player.download.DownloadManager
import xyz.asitanokibou.player.ui.navigation.Screen
import xyz.asitanokibou.player.ui.navigation.rememberNavState

@Composable
fun AppRoot(
    playback: PlaybackController? = null,
    settings: AppSettings,
    movieRepository: MovieRepository? = null,
    downloadManager: DownloadManager? = null,
    onFullscreenChanged: (Boolean) -> Unit = {},
    deepLinkPath: String? = null,
    deepLinkSeq: Int = 0,
) {
    val nav = rememberNavState()
    val scope = rememberCoroutineScope()
    val config by settings.configFlow.collectAsState(initial = AppConfig())
    val hasToken = !config.accessToken.isNullOrBlank()
    val colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()

    val movieListState = remember(movieRepository) {
        movieRepository?.let { MovieListState(it, scope) }
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
        if (nav.canPop) {
            nav.pop()
        } else {
            nav.reset(Screen.Index)
        }
    }

    // 播放页(含深链直达)与有历史栈的页面拦截返回;首页(Index,无历史)不拦截 → 系统默认退出
    BackHandler(enabled = nav.current is Screen.Play || nav.canPop) {
        if (nav.current is Screen.Play) exitPlay() else nav.pop()
    }

    MaterialTheme(colorScheme = colorScheme) {
        when (val screen = nav.current) {
            is Screen.Index -> {
                // 网页入口地址:由设置中的「影片信息服务地址」+ /index.html 拼出,未配置时为 null
                val webUrl = movieIndexUrl(config.movieApiBaseUrl)
                IndexScreen(
                    onOpenList = { nav.push(Screen.MovieList) },
                    onOpenDirect = { nav.push(Screen.Play(initialPath = "")) },
                    onOpenSettings = { nav.push(Screen.Settings) },
                    movieWebUrl = webUrl,
                    onOpenMovieWeb = {
                        if (webUrl != null) {
                            nav.push(Screen.Web(webUrl))
                        } else {
                            // 未配置服务地址:直接引导去「设置」填写
                            nav.push(Screen.Settings)
                        }
                    },
                )
            }
            is Screen.MovieList -> {
                if (movieListState != null) {
                    MovieListScreen(
                        hasToken = hasToken,
                        state = movieListState,
                        onBack = { nav.pop() },
                        onPick = { relativePath -> nav.push(Screen.Play(initialPath = relativePath)) },
                        onOpenDownloads = { nav.push(Screen.DownloadManager) },
                    )
                }
            }
            is Screen.Play -> HomeScreen(
                playback = playback,
                hasToken = hasToken,
                initialPath = screen.initialPath,
                movieRepository = movieRepository,
                downloadManager = downloadManager,
                doubleTapToSeek = config.doubleTapToSeek,
                onBack = { exitPlay() },
                onOpenSettings = { nav.push(Screen.Settings) },
                onOpenDownloads = { nav.push(Screen.DownloadManager) },
                onFullscreenChanged = onFullscreenChanged,
            )
            is Screen.Web -> MovieWebScreen(
                url = screen.url,
                onBack = { nav.pop() },
            )
            is Screen.DownloadManager -> DownloadManagerScreen(
                downloadManager = downloadManager,
                onBack = { nav.pop() },
            )
            is Screen.Settings -> SettingsScreen(
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
}
