package xyz.asitanokibou.player.di

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import xyz.asitanokibou.player.baidu.BaiduClient
import xyz.asitanokibou.player.baidu.BaiduYunClient
import xyz.asitanokibou.player.config.AppConfig
import xyz.asitanokibou.player.config.AppSettings
import xyz.asitanokibou.player.core.ConnectivityNetworkMonitor
import xyz.asitanokibou.player.core.NetworkGate
import xyz.asitanokibou.player.core.NetworkMonitor
import xyz.asitanokibou.player.data.MovieInfoClient
import xyz.asitanokibou.player.data.MovieRepository
import xyz.asitanokibou.player.download.DownloadManager
import xyz.asitanokibou.player.progress.PlayProgressStore
import xyz.asitanokibou.player.proxy.HlsChunkHandler
import xyz.asitanokibou.player.proxy.HlsM3u8Handler
import xyz.asitanokibou.player.proxy.HlsProxyHandler
import xyz.asitanokibou.player.proxy.ProxyServer
import xyz.asitanokibou.player.proxy.YunIndex
import xyz.asitanokibou.player.watchlater.WatchLaterStore

class AppContainer(context: Context) {

    val appSettings = AppSettings(context)
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _token = MutableStateFlow<String?>(null)
    val token: StateFlow<String?> = _token

    val baiduClient: BaiduClient = BaiduYunClient(tokenProvider = { _token.value })

    private val _movieApiBaseUrl = MutableStateFlow(AppConfig.DEFAULT_MOVIE_API_BASE_URL)

    val movieInfoClient: MovieInfoClient =
        MovieInfoClient(baseUrlProvider = { _movieApiBaseUrl.value })

    /** 计费网络判定:跟随系统默认网络(移动数据/计费 WiFi/热点共享/漫游) */
    val networkMonitor: NetworkMonitor = ConnectivityNetworkMonitor(context)

    /** 移动数据闸门:判定入口与放行令牌的唯一持有者,UI / DownloadManager / 本地代理共用 */
    val networkGate: NetworkGate = NetworkGate()

    private val _backgroundPlayback = MutableStateFlow(true)

    /** 后台播放开关的实时值;UI 与 PlaybackService 读取,改设置立即生效 */
    val backgroundPlayback: StateFlow<Boolean> = _backgroundPlayback

    /** 影片库统一接缝:列表分页 + 单部详情都经过这里 */
    val movieRepository: MovieRepository = MovieRepository(baiduClient, movieInfoClient)

    /** 下载调度器:最多同时下载 MAX_CONCURRENT 个 + 分片粒度续传(逻辑独立于任何 Service,Service 只是前台宿主) */
    val downloadManager: DownloadManager = DownloadManager(context, baiduClient, networkGate = networkGate)

    /** 稍后再看列表的本地持久化;播放页 toggle 写入,列表页读取 */
    val watchLaterStore: WatchLaterStore = WatchLaterStore(context)

    val playProgressStore: PlayProgressStore = PlayProgressStore(context)

    init {
        appScope.launch {
            appSettings.configFlow.map { it.accessToken }.collect { _token.value = it }
        }
        appScope.launch {
            appSettings.configFlow.map { it.movieApiBaseUrl }.collect { _movieApiBaseUrl.value = it }
        }
        appScope.launch {
            appSettings.configFlow.map { it.backgroundPlayback }.collect { _backgroundPlayback.value = it }
        }
        appScope.launch {
            networkMonitor.isMetered.collect { networkGate.setMetered(it) }
        }
        appScope.launch {
            appSettings.configFlow.collect { config ->
                networkGate.applySettings(config.allowPlaybackOnMetered, config.allowDownloadOnMetered)
            }
        }
    }

    /**
     * 当前运行中的代理图(由 PlaybackService 创建并登记);null 表示代理未运行,
     * 此时「清除缓存」无事可做。
     */
    @Volatile
    private var activeProxyGraph: ProxyGraph? = null

    fun createProxyGraph(config: AppConfig): ProxyGraph {
        val yunIndex = YunIndex(baiduClient, ttlMillis = config.cacheTtlSec * 1000L)

        val m3u8Handler = HlsM3u8Handler(baiduClient, yunIndex)
        val chunkHandler = HlsChunkHandler(baiduClient, yunIndex)
        // 闸门判定源注入代理图:代理是取流的唯一汇聚点,也是 UI 不存在时的兜底
        val handler = HlsProxyHandler(m3u8Handler, chunkHandler, networkGate = networkGate)
        val server = ProxyServer(handler, preferredPort = config.port)
        val port = server.start()
        return ProxyGraph(server, yunIndex, port).also { activeProxyGraph = it }
    }

    /** 代理停止时解绑,避免设置页清到已废弃实例的缓存 */
    fun releaseProxyGraph(graph: ProxyGraph) {
        if (activeProxyGraph === graph) activeProxyGraph = null
    }

    /** 立即清空内存中的缓存信息(fsid 缓存);UI「设置 → 清除缓存」的唯一入口 */
    fun clearCaches() {
        activeProxyGraph?.clearCaches()
    }
}
