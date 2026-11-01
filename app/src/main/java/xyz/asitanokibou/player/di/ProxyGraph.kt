package xyz.asitanokibou.player.di

import xyz.asitanokibou.player.proxy.ProxyServer
import xyz.asitanokibou.player.proxy.YunIndex

class ProxyGraph(
    val proxyServer: ProxyServer,
    val yunIndex: YunIndex,
    val port: Int,
) {
    fun stop() = proxyServer.stop()

    /** 清空内存中的 fsid 缓存(播放不受影响,后续请求按需重载目录) */
    fun clearCaches() = yunIndex.clearCache()
}
