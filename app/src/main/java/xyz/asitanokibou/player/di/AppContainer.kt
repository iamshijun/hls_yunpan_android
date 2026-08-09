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
import xyz.asitanokibou.player.cache.ContentCache
import xyz.asitanokibou.player.cache.FsidStore
import xyz.asitanokibou.player.cache.MemoryFsidStore
import xyz.asitanokibou.player.config.AppConfig
import xyz.asitanokibou.player.config.AppSettings
import xyz.asitanokibou.player.proxy.FsidDirectoryLoader
import xyz.asitanokibou.player.proxy.HlsChunkHandler
import xyz.asitanokibou.player.proxy.HlsM3u8Handler
import xyz.asitanokibou.player.proxy.HlsProxyHandler
import xyz.asitanokibou.player.proxy.ProxyServer
import xyz.asitanokibou.player.proxy.YunPathMapper
import java.io.File

class AppContainer(context: Context) {

    val appSettings = AppSettings(context)
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _token = MutableStateFlow<String?>(null)
    val token: StateFlow<String?> = _token

    val baiduClient: BaiduClient = BaiduYunClient(tokenProvider = { _token.value })

    init {
        appScope.launch {
            appSettings.configFlow.map { it.accessToken }.collect { _token.value = it }
        }
    }

    fun createProxyGraph(config: AppConfig, cacheDir: File): ProxyGraph {
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
