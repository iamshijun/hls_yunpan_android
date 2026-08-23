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
import xyz.asitanokibou.player.ui.navigation.Screen
import xyz.asitanokibou.player.ui.navigation.rememberNavState

@Composable
fun AppRoot(
    playback: PlaybackController? = null,
    settings: AppSettings,
    movieRepository: MovieRepository? = null,
    onFullscreenChanged: (Boolean) -> Unit = {},
    deepLinkPath: String? = null,
) {
    val nav = rememberNavState()
    val scope = rememberCoroutineScope()
    val config by settings.configFlow.collectAsState(initial = AppConfig())
    val hasToken = !config.accessToken.isNullOrBlank()
    val colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()

    val movieListState = remember(movieRepository) {
        movieRepository?.let { MovieListState(it, scope) }
    }

    // 深链进入:重置导航栈直达播放页(返回键回首页,栈不被污染)
    LaunchedEffect(deepLinkPath) {
        val p = deepLinkPath?.trim()?.trim('/')
        if (!p.isNullOrEmpty()) nav.reset(Screen.Play(initialPath = p))
    }

    // 离开播放页(pop 回列表/首页)时清空 player:否则上一部影片的帧与状态残留,
    // 新影片页的空闲封面海报无法显示(推入设置页不清,保留续播)
    fun exitPlay() {
        playback?.release()
        nav.pop()
    }

    BackHandler(enabled = nav.canPop) {
        if (nav.current is Screen.Play) exitPlay() else nav.pop()
    }

    MaterialTheme(colorScheme = colorScheme) {
        when (val screen = nav.current) {
            is Screen.Index -> IndexScreen(
                onOpenList = { nav.push(Screen.MovieList) },
                onOpenDirect = { nav.push(Screen.Play(initialPath = "")) },
                onOpenSettings = { nav.push(Screen.Settings) },
            )
            is Screen.MovieList -> {
                if (movieListState != null) {
                    MovieListScreen(
                        hasToken = hasToken,
                        state = movieListState,
                        onBack = { nav.pop() },
                        onPick = { relativePath -> nav.push(Screen.Play(initialPath = relativePath)) },
                    )
                }
            }
            is Screen.Play -> HomeScreen(
                playback = playback,
                hasToken = hasToken,
                initialPath = screen.initialPath,
                movieRepository = movieRepository,
                onBack = { exitPlay() },
                onOpenSettings = { nav.push(Screen.Settings) },
                onFullscreenChanged = onFullscreenChanged,
            )
            is Screen.Settings -> SettingsScreen(
                initialToken = config.accessToken ?: "",
                initialMovieApiBaseUrl = config.movieApiBaseUrl,
                onSave = { token, movieApiBaseUrl ->
                    scope.launch {
                        settings.update(
                            config.copy(
                                accessToken = token,
                                movieApiBaseUrl = movieApiBaseUrl,
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
