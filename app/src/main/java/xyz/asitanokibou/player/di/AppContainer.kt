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
import xyz.asitanokibou.player.cache.FsidStore
import xyz.asitanokibou.player.cache.MemoryFsidStore
import xyz.asitanokibou.player.config.AppConfig
import xyz.asitanokibou.player.config.AppSettings
import xyz.asitanokibou.player.data.MovieInfoClient
import xyz.asitanokibou.player.data.MovieRepository
import xyz.asitanokibou.player.proxy.FsidDirectoryLoader
import xyz.asitanokibou.player.proxy.HlsChunkHandler
import xyz.asitanokibou.player.proxy.HlsM3u8Handler
import xyz.asitanokibou.player.proxy.HlsProxyHandler
import xyz.asitanokibou.player.proxy.ProxyServer
import xyz.asitanokibou.player.proxy.YunPathMapper

class AppContainer(context: Context) {

    val appSettings = AppSettings(context)
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _token = MutableStateFlow<String?>(null)
    val token: StateFlow<String?> = _token

    val baiduClient: BaiduClient = BaiduYunClient(tokenProvider = { _token.value })

    private val _movieApiBaseUrl = MutableStateFlow(AppConfig.DEFAULT_MOVIE_API_BASE_URL)

    val movieInfoClient: MovieInfoClient =
        MovieInfoClient(baseUrlProvider = { _movieApiBaseUrl.value })

    /** 影片库统一接缝:列表分页 + 单部详情都经过这里 */
    val movieRepository: MovieRepository = MovieRepository(baiduClient, movieInfoClient)

    init {
        appScope.launch {
            appSettings.configFlow.map { it.accessToken }.collect { _token.value = it }
        }
        appScope.launch {
            appSettings.configFlow.map { it.movieApiBaseUrl }.collect { _movieApiBaseUrl.value = it }
        }
    }

    fun createProxyGraph(config: AppConfig): ProxyGraph {
        val ttlMillis = config.cacheTtlSec * 1000L
        val fsidStore: FsidStore = MemoryFsidStore(ttlMillis = ttlMillis)

        val pathMapper = YunPathMapper()
        val directoryLoader = FsidDirectoryLoader(baiduClient, fsidStore)
        val m3u8Handler = HlsM3u8Handler(baiduClient, fsidStore, directoryLoader, pathMapper)
        val chunkHandler = HlsChunkHandler(baiduClient, fsidStore, directoryLoader, pathMapper)
        val handler = HlsProxyHandler(m3u8Handler, chunkHandler)
        val server = ProxyServer(handler, preferredPort = config.port)
        val port = server.start()
        return ProxyGraph(server, port)
    }
}
