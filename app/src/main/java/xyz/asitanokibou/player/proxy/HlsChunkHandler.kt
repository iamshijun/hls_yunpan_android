package xyz.asitanokibou.player.proxy

import android.os.SystemClock
import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.utils.io.writeFully
import xyz.asitanokibou.player.baidu.BaiduClient
import xyz.asitanokibou.player.baidu.StreamProbe
import xyz.asitanokibou.player.core.ProxyErrors

class HlsChunkHandler(
    private val baidu: BaiduClient,
    private val yunIndex: YunIndex,
) {
    suspend fun handle(call: ApplicationCall, requestPath: String) {
        val yunPath = yunIndex.toYunPath(requestPath)
        Log.i(TAG, "处理分片请求: $requestPath -> $yunPath")

        val fsid = try {
            yunIndex.fsid(yunPath)
        } catch (e: Exception) {
            Log.e(TAG, "分片请求预处理失败: $e")
            call.respondText(ProxyErrors.messageFor(e), status = ProxyErrors.statusFor(e))
            return
        }
        if (fsid == null) {
            Log.e(TAG, "未找到分片文件的fsid: $yunPath")
            call.respondText("File not found: $yunPath", status = HttpStatusCode.NotFound)
            return
        }

        appendChunkHeaders(call)
        call.respondBytesWriter(contentType = TS_CONTENT_TYPE) {
            val startedAt = SystemClock.elapsedRealtime()
            val probe = StreamProbe()
            var bytes = 0L
            var firstByteMs = -1L
            try {
                baidu.openDownloadStream(fsid, probe) { source ->
                    val buf = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val n = source.readAvailable(buf, 0, buf.size)
                        if (n == -1) break
                        if (n > 0) {
                            if (firstByteMs < 0) firstByteMs = SystemClock.elapsedRealtime() - startedAt
                            writeFully(buf, 0, n)
                            bytes += n
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "分片流式转发失败 [$yunPath]: $e")
                throw e
            } finally {
                val totalMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1)
                Log.i(TAG, "分片转发 $requestPath ${bytes}B 换直链=${probe.dlinkMs}ms " +
                    "响应头=${probe.headersMs}ms 首字节=${firstByteMs}ms 总=${totalMs}ms " +
                    "${bytes * 1000 / 1024 / totalMs}KB/s")
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
