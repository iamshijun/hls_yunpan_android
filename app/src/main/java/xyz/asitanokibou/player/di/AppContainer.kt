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
import xyz.asitanokibou.player.data.MovieInfoClient
import xyz.asitanokibou.player.data.MovieRepository
import xyz.asitanokibou.player.download.DownloadManager
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

    private val _backgroundPlayback = MutableStateFlow(true)

    /** 后台播放开关的实时值;UI 与 PlaybackService 读取,改设置立即生效 */
    val backgroundPlayback: StateFlow<Boolean> = _backgroundPlayback

    /** 影片库统一接缝:列表分页 + 单部详情都经过这里 */
    val movieRepository: MovieRepository = MovieRepository(baiduClient, movieInfoClient)

    /** 下载调度器:最多同时下载 MAX_CONCURRENT 个 + 分片粒度续传(逻辑独立于任何 Service,Service 只是前台宿主) */
    val downloadManager: DownloadManager = DownloadManager(context, baiduClient)

    /** 稍后再看列表的本地持久化;播放页 toggle 写入,列表页读取 */
    val watchLaterStore: WatchLaterStore = WatchLaterStore(context)

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
    }

    fun createProxyGraph(config: AppConfig): ProxyGraph {
        val yunIndex = YunIndex(baiduClient, ttlMillis = config.cacheTtlSec * 1000L)

        val m3u8Handler = HlsM3u8Handler(baiduClient, yunIndex)
        val chunkHandler = HlsChunkHandler(baiduClient, yunIndex)
        val handler = HlsProxyHandler(m3u8Handler, chunkHandler)
        val server = ProxyServer(handler, preferredPort = config.port)
        val port = server.start()
        return ProxyGraph(server, port)
    }
}
