package xyz.asitanokibou.player.proxy

import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import xyz.asitanokibou.player.baidu.BaiduClient
import xyz.asitanokibou.player.cache.ContentCache
import xyz.asitanokibou.player.cache.FsidStore
import xyz.asitanokibou.player.core.ProxyErrors

class HlsM3u8Handler(
    private val baidu: BaiduClient,
    private val fsidStore: FsidStore,
    private val directoryLoader: FsidDirectoryLoader,
    private val pathMapper: YunPathMapper,
) {
    suspend fun handle(call: ApplicationCall, requestPath: String) {
        try {
            val yunPath = pathMapper.toYunPath(requestPath)
            val dirPath = pathMapper.dirName(yunPath)
            Log.i(TAG, "处理m3u8请求: $requestPath -> $yunPath")

            directoryLoader.loadDirectory(dirPath)
            val fsid = fsidStore.get(yunPath)
            if (fsid == null) {
                Log.e(TAG, "未找到文件的fsid: $yunPath")
                call.respondText("File not found: $yunPath", status = HttpStatusCode.NotFound)
                return
            }
            val content = baidu.downloadBytes(fsid)
            val rewritten = M3u8Rewriter.rewrite(content, requestPath)

            call.response.headers.append(HttpHeaders.CacheControl, "public, max-age=3600")
            call.response.headers.append(HttpHeaders.AccessControlAllowOrigin, "*")

            call.respondBytes(rewritten, M3U8_CONTENT_TYPE, HttpStatusCode.OK)
        } catch (e: Exception) {
            Log.e(TAG, "处理m3u8请求失败: $e")
            call.respondText(ProxyErrors.messageFor(e), status = ProxyErrors.statusFor(e))
        }
    }

    companion object {
        private const val TAG = "HlsM3u8Handler"
        private val M3U8_CONTENT_TYPE = ContentType("application", "vnd.apple.mpegurl")
    }
}
