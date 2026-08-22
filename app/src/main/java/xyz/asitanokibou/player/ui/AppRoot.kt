package xyz.asitanokibou.player.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.media3.common.Player
import kotlinx.coroutines.launch
import xyz.asitanokibou.player.baidu.BaiduClient
import xyz.asitanokibou.player.config.AppConfig
import xyz.asitanokibou.player.config.AppSettings
import xyz.asitanokibou.player.data.MovieInfoClient
import xyz.asitanokibou.player.data.MovieRepository
import xyz.asitanokibou.player.ui.navigation.Screen
import xyz.asitanokibou.player.ui.navigation.rememberNavState

@Composable
fun AppRoot(
    controller: Player?,
    settings: AppSettings,
    baidu: BaiduClient? = null,
    movieInfo: MovieInfoClient? = null,
    onFullscreenChanged: (Boolean) -> Unit = {},
) {
    val nav = rememberNavState()
    val scope = rememberCoroutineScope()
    val config by settings.configFlow.collectAsState(initial = AppConfig())
    val hasToken = !config.accessToken.isNullOrBlank()
    val colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()

    val movieListState = remember(baidu, movieInfo) {
        val repo = baidu?.let { MovieRepository(it, movieInfo) }
        repo?.let { MovieListState(it, scope) }
    }

    BackHandler(enabled = nav.canPop) {
        nav.pop()
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
                controller = controller,
                hasToken = hasToken,
                initialPath = screen.initialPath,
                movieInfoClient = movieInfo,
                onBack = { nav.pop() },
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
