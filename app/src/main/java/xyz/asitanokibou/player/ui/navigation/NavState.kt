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

    /** 栈顶页(当前页的上一页);分屏模式下即左栏页面。无历史时为 null */
    val previous: Screen? get() = _history.lastOrNull()

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

    /**
     * 不推栈地替换当前页(分屏时左栏选新条目 → 右栏切换新视频),
     * 历史栈保持不变,返回仍回上一页。
     */
    fun replaceCurrent(screen: Screen) {
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
    /** 稍后再看列表页:由播放页 toggle 加入的影片 */
    data object WatchLater : Screen()
}

@Composable
fun rememberNavState(): NavState = remember { NavState() }
