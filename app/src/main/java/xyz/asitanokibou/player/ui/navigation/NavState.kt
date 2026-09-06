package xyz.asitanokibou.player.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

class NavState {
    private val _history = ArrayDeque<Screen>()
    var current: Screen by mutableStateOf(Screen.Index)
        private set

    val canPop: Boolean get() = _history.isNotEmpty()

    fun push(screen: Screen) {
        _history.addLast(current)
        current = screen
    }

    fun pop() {
        if (_history.isNotEmpty()) {
            current = _history.removeLast()
        }
    }

    fun reset(screen: Screen) {
        _history.clear()
        current = screen
    }
}

sealed class Screen {
    data object Index : Screen()
    data object MovieList : Screen()
    data class Play(val initialPath: String) : Screen()
    data object Settings : Screen()
    /** WebView 页:加载影片信息服务网页(设置中的地址 + /index.html) */
    data class Web(val url: String) : Screen()
    data object DownloadManager : Screen()
}

@Composable
fun rememberNavState(): NavState = remember { NavState() }
