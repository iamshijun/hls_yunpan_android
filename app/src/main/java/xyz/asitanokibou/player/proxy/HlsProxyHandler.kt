package xyz.asitanokibou.player.proxy

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import xyz.asitanokibou.player.core.HlsPaths

class HlsProxyHandler(
    private val m3u8Handler: HlsM3u8Handler,
    private val chunkHandler: HlsChunkHandler,
    private val hlsRootPath: String = HlsPaths.PROXY_ROOT_PATH,
) {
    suspend fun handle(call: ApplicationCall, path: String) {
        val requestPath = "$hlsRootPath/$path"
        when {
            path.endsWith(".m3u8") -> m3u8Handler.handle(call, requestPath)
            path.endsWith(".ts") -> chunkHandler.handle(call, requestPath)
            else -> {
                val accept = call.request.header(HttpHeaders.Accept) ?: ""
                if (accept.contains("m3u8") || accept.contains("mpegurl")) {
                    m3u8Handler.handle(call, requestPath)
                } else {
                    chunkHandler.handle(call, requestPath)
                }
            }
        }
    }
}
