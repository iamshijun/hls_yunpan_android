package xyz.asitanokibou.player.cache

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 文件内容缓存
 *
 * 布局：cacheDir/<md5前2位>/<md5>  与同名 <md5>.meta（记录 timestamp）。
 * 由 [enabled] 控制是否落盘；是否缓存分片由调用方（代理层）决定是否 set。
 */
class FileCache(
    private val cacheDir: File,
    private val ttlMillis: Long,
    private val enabled: Boolean,
) : ContentCache {
    @Serializable
    private data class Meta(val path: String, val timestamp: Long, val size: Long)

    private val json = Json { ignoreUnknownKeys = true }
    private val locks = ConcurrentHashMap<String, Mutex>()

    init {
        if (enabled) cacheDir.mkdirs()
    }

    private fun cacheKey(path: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(path.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun cacheFile(path: String): File {
        val key = cacheKey(path)
        return File(File(cacheDir, key.substring(0, 2)), key)
    }

    private fun metaFile(path: String): File {
        val key = cacheKey(path)
        return File(cacheFile(path).parentFile, "$key.meta")
    }

    private fun readMeta(path: String): Meta? {
        val mf = metaFile(path)
        if (!mf.exists()) return null
        return try {
            json.decodeFromString(Meta.serializer(), mf.readText())
        } catch (e: Exception) {
            Log.e(TAG, "读取缓存元数据失败: $e")
            null
        }
    }

    private fun isValidBlocking(path: String): Boolean {
        val meta = readMeta(path) ?: return false
        return System.currentTimeMillis() - meta.timestamp < ttlMillis
    }

    /** 读取缓存内容，无效/不存在返回 null */
    override suspend fun get(path: String): ByteArray? = withContext(Dispatchers.IO) {
        if (!enabled || !isValidBlocking(path)) return@withContext null
        val cf = cacheFile(path)
        if (!cf.exists()) return@withContext null
        try {
            Log.i(TAG, "缓存命中: $path")
            cf.readBytes()
        } catch (e: Exception) {
            Log.e(TAG, "读取缓存失败: $e")
            null
        }
    }

    /** 写入缓存（含元数据），[enabled] 为 false 时空操作 */
    override suspend fun set(path: String, content: ByteArray) {
        if (!enabled) return
        val lock = locks.getOrPut(cacheKey(path)) { Mutex() }
        lock.withLock {
            withContext(Dispatchers.IO) {
                try {
                    val cf = cacheFile(path)
                    cf.parentFile?.mkdirs()
                    cf.writeBytes(content)
                    val meta = Meta(path, System.currentTimeMillis(), content.size.toLong())
                    metaFile(path).writeText(json.encodeToString(Meta.serializer(), meta))
                    Log.i(TAG, "缓存已写入: $path (${content.size} bytes)")
                } catch (e: Exception) {
                    Log.e(TAG, "写入缓存失败: $e")
                }
            }
        }
    }

    override suspend fun delete(path: String) {
        withContext(Dispatchers.IO) {
            try {
                cacheFile(path).takeIf { it.exists() }?.delete()
                metaFile(path).takeIf { it.exists() }?.delete()
            } catch (e: Exception) {
                Log.e(TAG, "删除缓存失败: $e")
            }
        }
    }

    /** 清理过期的内容缓存，返回清理条数 */
    override suspend fun clearExpired(): Int = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext 0
        var count = 0
        try {
            cacheDir.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".meta") }
                .forEach { mf ->
                    try {
                        val meta = json.decodeFromString(Meta.serializer(), mf.readText())
                        if (System.currentTimeMillis() - meta.timestamp >= ttlMillis) {
                            // 内容文件名 = meta 文件去掉 .meta 后缀
                            File(mf.parentFile, mf.nameWithoutExtension).takeIf { it.exists() }?.delete()
                            mf.delete()
                            count++
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "清理缓存失败 [$mf]: $e")
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "清理过期缓存失败: $e")
        }
        count
    }

    companion object {
        private const val TAG = "FileCache"
    }
}
