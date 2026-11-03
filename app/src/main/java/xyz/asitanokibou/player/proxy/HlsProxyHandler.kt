package xyz.asitanokibou.player.proxy

import android.util.Log
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.response.respondText
import xyz.asitanokibou.player.core.HlsPaths
import xyz.asitanokibou.player.core.NetworkGate

class HlsProxyHandler(
    private val m3u8Handler: HlsM3u8Handler,
    private val chunkHandler: HlsChunkHandler,
    /** 移动数据闸门;null 表示不装闸门(行为与旧版一致) */
    private val networkGate: NetworkGate? = null,
    private val hlsRootPath: String = HlsPaths.PROXY_ROOT_PATH,
) {
    suspend fun handle(call: ApplicationCall, path: String) {
        // 兜底闸门:拦在分发之前,一处覆盖 m3u8 与 .ts。UI 层已拦时请求根本到不了这里;
        // 走到这里是"后台播放切网"或"通知栏点播放"这类没有 UI 参与的场景。
        if (networkGate != null && !networkGate.canUseMeteredPlayback()) {
            Log.w(TAG, "移动数据下禁止代理取流: $path")
            call.respondText(METERED_BLOCKED_MESSAGE, status = HttpStatusCode.Forbidden)
            return
        }
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

    companion object {
        private const val TAG = "HlsProxyHandler"

        /** 兜底拦截的响应正文:只有代理会返 403,可据此还原用户可读文案 */
        const val METERED_BLOCKED_MESSAGE = "移动数据下禁止代理取流"
    }
}
