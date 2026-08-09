package xyz.asitanokibou.player.di

import xyz.asitanokibou.player.proxy.ProxyServer

class ProxyGraph(
    val proxyServer: ProxyServer,
    val port: Int,
) {
    fun start(): Int = proxyServer.start()
    fun stop() = proxyServer.stop()
}
