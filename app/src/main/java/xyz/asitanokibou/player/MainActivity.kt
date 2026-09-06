package xyz.asitanokibou.player

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import xyz.asitanokibou.player.HlsPanApp
import xyz.asitanokibou.player.core.DeepLink
import xyz.asitanokibou.player.service.PlaybackService
import xyz.asitanokibou.player.ui.AppRoot
import xyz.asitanokibou.player.ui.PlaybackController

class MainActivity : ComponentActivity() {

    private var playback by mutableStateOf<PlaybackController?>(null)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var deepLinkPath by mutableStateOf<String?>(null)
    // 深链序号:每次收到深链 intent 递增。仅用 deepLinkPath 作 LaunchedEffect key 时,
    // 重复调起同一 code(值不变)不会触发重组 → App 已打开时网页再次跳转不达播放页。
    private var deepLinkSeq by mutableIntStateOf(0)

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 结果不影响播放 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        maybeRequestNotificationPermission()
        handleDeepLink(intent)

        val container = (application as HlsPanApp).container

        setContent {
            AppRoot(
                playback = playback,
                settings = container.appSettings,
                movieRepository = container.movieRepository,
                downloadManager = container.downloadManager,
                onFullscreenChanged = { fullscreen -> applyFullscreen(fullscreen) },
                deepLinkPath = deepLinkPath,
                deepLinkSeq = deepLinkSeq,
            )
        }
    }

    /** 统一处理深链(冷/热启动):每次收到含 fan_code 的 intent 都递增序号并更新路径,
     *  保证相同 code 的重复调起也能让 UI 侧重新导航到播放页。 */
    private fun handleDeepLink(intent: Intent) {
        DeepLink.fanCode(intent)?.let { code ->
            deepLinkPath = code
            deepLinkSeq++
        }
    }

    /** 热启动深链:App 已在运行(如后台)时由浏览器再次拉起。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    private fun maybeRequestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /**
     * 全屏切换:edge-to-edge 由 onCreate 里的 enableEdgeToEdge() 统一开启
     * (setDecorFitsSystemWindows=false,内容铺满整窗,Scaffold 通过 insets 自行让出状态栏),
     * 这里只负责系统栏的显示/隐藏与屏幕方向。
     *
     * 注意:不能在这里把 decorFitsSystemWindows 切回 true —— 那样系统会在状态栏下方重新为内容
     * 留白,而 Scaffold 默认 contentWindowInsets=systemBars 仍会再 pad 一次状态栏高度,
     * 导致页面顶部(返回/设置等标题行上方)出现约一栏状态栏高度的空隙。
     */
    private fun applyFullscreen(fullscreen: Boolean) {
        val insetsController = WindowInsetsControllerCompat(window, window.decorView)
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        requestedOrientation = if (fullscreen) {
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    override fun onStart() {
        super.onStart()
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                playback = try {
                    PlaybackController(future.get())
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "连接播放服务失败", e)
                    null
                }
            },
            MoreExecutors.directExecutor(),
        )
    }

    override fun onStop() {
        // 后台播放关闭时:离开应用(Home/锁屏/切后台)即暂停播放
        // (pause 经 MediaController 同步发出,dispose/release 前已送达 session)
        if (!(application as HlsPanApp).container.backgroundPlayback.value) {
            playback?.pause()
        }
        playback?.dispose()
        playback = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
