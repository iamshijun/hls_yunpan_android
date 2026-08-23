package xyz.asitanokibou.player.proxy

import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.utils.io.writeFully
import xyz.asitanokibou.player.baidu.BaiduClient
import xyz.asitanokibou.player.cache.FsidStore
import xyz.asitanokibou.player.core.ProxyErrors
import java.io.ByteArrayOutputStream

class HlsChunkHandler(
    private val baidu: BaiduClient,
    private val fsidStore: FsidStore,
    private val directoryLoader: FsidDirectoryLoader,
    private val pathMapper: YunPathMapper
) {
    suspend fun handle(call: ApplicationCall, requestPath: String) {
        val yunPath = pathMapper.toYunPath(requestPath)
        val dirPath = pathMapper.dirName(yunPath)
        Log.i(TAG, "处理分片请求: $requestPath -> $yunPath")

        val fsid: Long
        try {
            var id = fsidStore.get(yunPath)
            if (id == null) {
                Log.w(TAG, "分片文件fsid未缓存，尝试加载目录: $dirPath")
                directoryLoader.loadDirectory(dirPath)
                id = fsidStore.get(yunPath)
            }
            if (id == null) {
                Log.e(TAG, "未找到分片文件的fsid: $yunPath")
                call.respondText("File not found: $yunPath", status = HttpStatusCode.NotFound)
                return
            }
            fsid = id
        } catch (e: Exception) {
            Log.e(TAG, "分片请求预处理失败: $e")
            call.respondText(ProxyErrors.messageFor(e), status = ProxyErrors.statusFor(e))
            return
        }

        appendChunkHeaders(call)
        call.respondBytesWriter(contentType = TS_CONTENT_TYPE) {
            try {
                baidu.openDownloadStream(fsid) { source ->
                    val buf = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val n = source.readAvailable(buf, 0, buf.size)
                        if (n == -1) break
                        if (n > 0) {
                            writeFully(buf, 0, n)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "分片流式转发失败 [$yunPath]: $e")
                throw e
            }
        }
    }

    private fun appendChunkHeaders(call: ApplicationCall) {
        call.response.headers.append(HttpHeaders.CacheControl, "no-cache")
        call.response.headers.append(HttpHeaders.AccessControlAllowOrigin, "*")
    }

    companion object {
        private const val TAG = "HlsChunkHandler"
        private const val BUFFER_SIZE = 65536
        private val TS_CONTENT_TYPE = ContentType("video", "mp2t")
    }
}
