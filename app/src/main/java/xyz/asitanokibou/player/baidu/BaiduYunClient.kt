package xyz.asitanokibou.player.baidu

import android.util.Log
import android.os.SystemClock
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Parameters
import io.ktor.utils.io.ByteReadChannel
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import xyz.asitanokibou.player.baidu.model.BaiduFile
import xyz.asitanokibou.player.baidu.model.FileListResponse
import xyz.asitanokibou.player.baidu.model.FileManagerResponse
import xyz.asitanokibou.player.baidu.model.FileMetasResponse

/**
 * 百度网盘客户端。
 *
 * - 列表 / 元数据用浏览器 UA
 * - 下载直链必须用 `User-Agent: pan.baidu.com` 且携带 access_token、跟随重定向
 * - errno != 0 视为业务失败
 *
 * @param tokenProvider 返回当前 access_token（来源于设置，可动态变化）
 */
class BaiduYunClient(
    private val tokenProvider: () -> String?,
) {
    /** fsid → 直链缓存(带取回时刻)。批量预取与单点补全共用它 */
    private data class CachedLink(val url: String, val fetchedAtMs: Long)

    private val linkCache = java.util.concurrent.ConcurrentHashMap<Long, CachedLink>()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val http: HttpClient = HttpClient(OkHttp) {
        // OkHttp 引擎：完整实现 hostname-aware checkServerTrusted，
        // 避免 Android 7+ 在严格 networkSecurityConfig 下抛 CertificateException
        followRedirects = true
        expectSuccess = true // 类似 httpx 的 raise_for_status（HTTP 层）
        install(HttpTimeout) {
            connectTimeoutMillis = API_TIMEOUT_MS
            requestTimeoutMillis = DOWNLOAD_STREAM_TIMEOUT_MS
            socketTimeoutMillis = DOWNLOAD_STREAM_TIMEOUT_MS
        }
    }

    /**
     * 获取目录下所有文件
     * @param path 目录路径
     * @param order 排序字段：`name` / `time` / `size`，与百度 list API 一致
     * @param desc 1 = 降序，0 = 升序
     * @param batchSize 批量
     */
    suspend fun getFileListAll(
        path: String = "/",
        order: String = "name",
        desc: Int = 1,
        batchSize: Int = BATCH_SIZE,
    ): List<BaiduFile> {
        val all = mutableListOf<BaiduFile>()
        var start = 0
        while (true) {
            val files = getFileList(path, start, batchSize, order, desc)
            if (files.isEmpty()) break
            all.addAll(files)
            if (files.size < batchSize) break
            start += batchSize
            Log.i(TAG, "已获取 ${all.size} 个文件，继续获取...")
        }
        Log.i(TAG, "目录 [$path] 共有 ${all.size} 个文件")
        return all
    }

    /**
     * 获取目录下的单批文件列表，支持分页参数。
     *
     * 调用方负责按需累加 start 并判断何时结束（如 `result.size < limit` 即为末页）。
     * 注意：百度 API 的 `errno != 0` 会被吞为 `emptyList()`，与 [getFileListAll] 行为一致；
     * 网络/HTTP 异常会直接抛出，由调用方处理。
     *
     * @param order 排序字段：`name` / `time` / `size`
     * @param desc 1 = 降序，0 = 升序
     */
    suspend fun getFileList(
        path: String = "/",
        start: Int = 0,
        limit: Int = BATCH_SIZE,
        order: String = "name",
        desc: Int = 1,
    ): List<BaiduFile> {
        val token = requireToken()
        val text = http.get(LIST_URL) {
            parameter("method", "list")
            parameter("dir", path)
            parameter("order", order)
            parameter("desc", desc)
            parameter("start", start)
            parameter("limit", limit)
            parameter("access_token", token)
            header(HttpHeaders.UserAgent, WEB_UA)
        }.bodyAsText()

        val resp = json.decodeFromString<FileListResponse>(text)
        if (resp.errno != 0) {
            Log.e(TAG, "获取文件列表失败: errno=${resp.errno} ${resp.errmsg}")
            throw BaiduApiException("获取文件列表失败: errno=${resp.errno} ${resp.errmsg}")
        }
        return resp.list
    }

    /**
     * 向服务端要一批直链。`filemetas` 的 `fsids` 参数支持一次带多个(实测 120 个仍是 ~0.5s),
     * 所以「每片一次换直链」的 RTT 可以摊到一批上。
     */
    private suspend fun fetchDownloadUrls(fsids: List<Long>): Map<Long, String> {
        if (fsids.isEmpty()) return emptyMap()
        val token = requireToken()
        val started = SystemClock.elapsedRealtime()
        val text = http.get(META_URL) {
            parameter("method", "filemetas")
            parameter("access_token", token)
            parameter("dlink", 1)
            parameter("fsids", fsids.joinToString(prefix = "[", postfix = "]"))
            header(HttpHeaders.UserAgent, WEB_UA)
        }.bodyAsText()

        val resp = json.decodeFromString<FileMetasResponse>(text)
        if (resp.errno != 0) {
            throw BaiduApiException("获取下载链接失败: errno=${resp.errno} ${resp.errmsg}")
        }
        Log.d(
            TAG,
            "filemetas ${fsids.size} 个 fsid ${SystemClock.elapsedRealtime() - started}ms" +
                "(返回 ${resp.list.count { !it.dlink.isNullOrBlank() }} 条直链)",
        )
        return resp.list
            .mapNotNull { meta -> meta.dlink?.takeIf { it.isNotBlank() }?.let { meta.fsId to it } }
            .toMap()
    }

    /**
     * 批量预取下载直链(`filemetas` 一次可带多个 fsid)。
     * 已在缓存里且未过期的会被跳过;下载路径靠它把「每片一次换直链」的 RTT 摊薄掉。
     */
    suspend fun prefetchDownloadUrls(fsids: List<Long>) {
        val now = SystemClock.elapsedRealtime()
        val missing = fsids.filter { fsid ->
            linkCache[fsid]?.let { now - it.fetchedAtMs < LINK_TTL_MS } != true
        }
        if (missing.isEmpty()) return
        for (chunk in missing.chunked(FETCH_BATCH)) {
            val fetched = fetchDownloadUrls(chunk)
            val at = SystemClock.elapsedRealtime()
            fetched.forEach { (fsid, url) -> linkCache[fsid] = CachedLink(url, at) }
        }
        trimLinkCache()
    }

    /** 直链作废(重试前调用):下次取会重新向服务端要 */
    fun invalidateDownloadUrl(fsid: Long) {
        linkCache.remove(fsid)
    }

    /** 取直链:缓存命中直接返回,否则单点补一次 */
    private suspend fun getDownloadUrl(fsid: Long): String {
        val cached = linkCache[fsid]
        if (cached != null && SystemClock.elapsedRealtime() - cached.fetchedAtMs < LINK_TTL_MS) {
            return cached.url
        }
        val url = fetchDownloadUrls(listOf(fsid))[fsid]
            ?: throw BaiduApiException("下载链接为空 (fsid=$fsid)")
        linkCache[fsid] = CachedLink(url, SystemClock.elapsedRealtime())
        trimLinkCache()
        return url
    }

    /** 缓存只服务「前面一小段」:超上限时先清过期的,还不够就按取回时刻从旧到新砍 */
    private fun trimLinkCache() {
        if (linkCache.size <= LINK_CACHE_MAX) return
        val now = SystemClock.elapsedRealtime()
        for ((fsid, link) in linkCache) {
            if (now - link.fetchedAtMs >= LINK_TTL_MS) linkCache.remove(fsid, link)
        }
        if (linkCache.size <= LINK_CACHE_MAX) return
        linkCache.entries
            .sortedBy { it.value.fetchedAtMs }
            .take(linkCache.size - LINK_CACHE_MAX / 2)
            .forEach { linkCache.remove(it.key, it.value) }
    }

    /** 下载整个文件（用于较小的 m3u8） */
    suspend fun downloadBytes(fsid: Long): ByteArray {
        val dlink = getDownloadUrl(fsid)
        Log.d(TAG, "download fsid=$fsid")
        return http.get(dlink) {
            parameter("access_token", requireToken())
            header(HttpHeaders.UserAgent, DOWNLOAD_UA)
        }.body()
    }

    /** 流式下载文件。字节流仅在 [block] 内有效(Ktor 流式生命周期)。
     * 供代理层将其复制到本地响应,避免整包缓冲。
     * [probe] 非空时填入直链耗时、响应头耗时与 Content-Length。
     */
    suspend fun <T> openDownloadStream(
        fsid: Long,
        probe: StreamProbe? = null,
        block: suspend (ByteReadChannel) -> T,
    ): T {
        val started = SystemClock.elapsedRealtime()
        val dlink = getDownloadUrl(fsid)
        if (probe != null) probe.dlinkMs = SystemClock.elapsedRealtime() - started
        val requestStarted = SystemClock.elapsedRealtime()
        return http.prepareGet(dlink) {
            parameter("access_token", requireToken())
            header(HttpHeaders.UserAgent, DOWNLOAD_UA)
        }.execute { response ->
            val headersAt = SystemClock.elapsedRealtime()
            if (probe != null) {
                probe.headersMs = headersAt - requestStarted
                probe.contentLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: -1
                // 首字节到 block 里第一个字节时由调用方补:见 DownloadManager
            }
            block(response.bodyAsChannel())
        }
    }

    suspend fun rename(path: String, newPath: String) {
        val token = requireToken()
        val text = http.submitForm(
            url = "$MANAGE_URL?method=filemanager&opera=rename",
            formParameters = Parameters.build {
                append("async", "0")
                append("ondup", "overwrite")
                append("filelist", json.encodeToString(listOf(RenameReq(path,newPath))))
            },
        ) {
            parameter("access_token", token)
            header(HttpHeaders.UserAgent, WEB_UA)
        }.bodyAsText()

        val resp = json.decodeFromString<FileManagerResponse>(text)
        if (resp.errno != 0) {
            Log.e(TAG, "移动失败: errno=${resp.errno} ${resp.errmsg}")
            throw BaiduApiException("移动失败: errno=${resp.errno} ${resp.errmsg}")
        }
        val failed = resp.info.filter { it.errno != 0 }
        if (failed.isNotEmpty()) {
            val detail = failed.joinToString { "${it.path} errno=${it.errno}" }
            Log.e(TAG, "移动失败: $detail")
            throw BaiduApiException("移动失败: $detail")
        }
        Log.i(TAG, "已将文件 $path 移动到新路径: $newPath")
    }

    /**
     * 按路径删除（移入网盘回收站）。
     *
     * 官方文档 filemanager 用 POST + form；但 `method` / `opera` / `access_token` 必须放在
     * URL query 上（放 body 会被服务端拒绝 - 400 Bad Request），body 只携带 `filelist`。
     * filelist 为路径 JSON 数组（删除目录会连同子内容）。
     * 整体 errno != 0 或任一目标 errno != 0 均视为失败并抛出。
     */
    suspend fun deleteFiles(paths: List<String>) {
        if (paths.isEmpty()) return
        val token = requireToken()
        val text = http.submitForm(
            url = "$MANAGE_URL?method=filemanager&opera=delete",
            formParameters = Parameters.build {
                append("async", "0")
                append("filelist", json.encodeToString(paths))
            },
        ) {
            parameter("access_token", token)
            header(HttpHeaders.UserAgent, WEB_UA)
        }.bodyAsText()

        val resp = json.decodeFromString<FileManagerResponse>(text)
        if (resp.errno != 0) {
            Log.e(TAG, "删除失败: errno=${resp.errno} ${resp.errmsg}")
            throw BaiduApiException("删除失败: errno=${resp.errno} ${resp.errmsg}")
        }
        val failed = resp.info.filter { it.errno != 0 }
        if (failed.isNotEmpty()) {
            val detail = failed.joinToString { "${it.path} errno=${it.errno}" }
            Log.e(TAG, "删除失败: $detail")
            throw BaiduApiException("删除失败: $detail")
        }
        Log.i(TAG, "已删除: ${paths.joinToString()}")
    }

    fun close() {
        http.close()
    }

    private fun requireToken(): String =
        tokenProvider()?.takeIf { it.isNotBlank() }
            ?: throw BaiduApiException("未配置 access_token")


    @Serializable
    data class RenameReq (val path: String, val newname: String){
    }

    companion object {
        private const val TAG = "BaiduYunClient"
        const val BATCH_SIZE = 1000

        private const val API_TIMEOUT_MS = 30_000L
        private const val DOWNLOAD_STREAM_TIMEOUT_MS = 300_000L

        /** 一次 `filemetas` 带多少个 fsid(实测 120 个仍是一次 RTT,取 100 留余量) */
        private const val FETCH_BATCH = 100

        /** 直链缓存有效期:直链自带 expires,保守取 20 分钟 */
        private const val LINK_TTL_MS = 20 * 60_000L

        /** 直链缓存条数上限 */
        private const val LINK_CACHE_MAX = 600

        private const val WEB_UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36"
        private const val DOWNLOAD_UA = "pan.baidu.com"

        private const val LIST_URL = "https://pan.baidu.com/rest/2.0/xpan/file"
        private const val MANAGE_URL = "https://pan.baidu.com/rest/2.0/xpan/file"
        private const val META_URL = "https://pan.baidu.com/rest/2.0/xpan/multimedia"
    }
}
