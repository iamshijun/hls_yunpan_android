package xyz.asitanokibou.player.proxy

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import xyz.asitanokibou.player.baidu.BaiduClient
import xyz.asitanokibou.player.core.HlsPaths
import java.util.concurrent.ConcurrentHashMap

/**
 * Yun index:把 HLS 请求路径解析为百度网盘 fsid 的唯一入口(深模块)。
 *
 * 内部封装了原先分散在三个浅模块里的逻辑,调用方(m3u8 / 分片 handler)
 * 只看到一次 resolve 动作:
 * - 路径映射:`/hls/<dir>/<file>` → `/apps/{app_name}/movies/<dir>/<file>`
 * - 目录文件列表加载 + fsid 缓存(条目级 TTL)
 * - 每目录互斥,避免并发重复加载同一目录
 *
 * 解析策略统一为惰性:缓存未命中(无记录或已过期)时才加载所属目录后重试,
 * 消除了 m3u8 handler 无条件重载与分片 handler 惰性回退之间的不一致。
 */
class YunIndex(
    private val baidu: BaiduClient,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val hlsRootPath: String = HlsPaths.PROXY_ROOT_PATH,
) {
    private data class Entry(val fsid: Long, val timestamp: Long)

    private val fsidCache = ConcurrentHashMap<String, Entry>()
    private val dirLocks = ConcurrentHashMap<String, Mutex>()

    /** 把代理请求路径转换为网盘路径:`/hls/<dir>/<file>` → `/apps/${app_name}/movies/<dir>/<file>` */
    fun toYunPath(requestPath: String): String {
        var p = requestPath
        if (p.startsWith(hlsRootPath)) {
            p = p.substring(hlsRootPath.length)
        }
        return HlsPaths.BAIDU_MEDIA_ROOT + p
    }

    /**
     * 解析 [yunPath] 的 fsid:缓存命中直接返回;未命中/过期时加载所属目录后重试,
     * 仍无则返回 null(文件不存在或不在该目录下)。
     */
    suspend fun fsid(yunPath: String): Long? {
        cached(yunPath)?.let { return it }
        loadDirectory(dirName(yunPath))
        return cached(yunPath)
    }

    /** 读取未过期的缓存项;已过期则移除并视为未命中 */
    private fun cached(yunPath: String): Long? {
        val e = fsidCache[yunPath] ?: return null
        return if (System.currentTimeMillis() - e.timestamp < ttlMillis) {
            e.fsid
        } else {
            fsidCache.remove(yunPath)
            null
        }
    }

    /** 取路径的所属目录;根目录返回 "/" */
    private fun dirName(path: String): String {
        val idx = path.lastIndexOf('/')
        return if (idx <= 0) "/" else path.substring(0, idx)
    }

    /** 加载目录下所有文件的 fsid;每目录一把锁,并发请求串行化,避免重复拉取 */
    private suspend fun loadDirectory(dirPath: String) {
        val lock = dirLocks.getOrPut(dirPath) { Mutex() }
        lock.withLock {
            Log.i(TAG, "开始加载目录 [$dirPath] 的文件列表和fsid...")
            val files = baidu.getFileListAll(dirPath)
            val now = System.currentTimeMillis()
            for (f in files) {
                if (f.path.isNotEmpty() && f.fsId != 0L) {
                    fsidCache[f.path] = Entry(f.fsId, now)
                }
            }
            Log.i(TAG, "目录 [$dirPath] 加载完成，共 ${files.size} 个文件")
        }
    }

    companion object {
        private const val TAG = "YunIndex"
        private const val DEFAULT_TTL_MILLIS = 60 * 60 * 1000L
    }
}
