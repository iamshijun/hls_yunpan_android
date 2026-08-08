package xyz.asitanokibou.player.cache

import java.util.concurrent.ConcurrentHashMap

/**
 * fsid 存储接口
 * 手机为单实例，MVP 仅提供内存实现；接口保留以便后续扩展磁盘持久化。
 */
interface FsidStore {
    suspend fun get(filePath: String): Long?
    suspend fun set(filePath: String, fsid: Long)
    suspend fun setMany(fsidMap: Map<String, Long>)
    suspend fun clearDir(dirPath: String)
}

/**
 * 纯内存 fsid 存储（进程生命周期内有效，带 TTL）。
 */
class MemoryFsidStore(private val ttlMillis: Long) : FsidStore {

    private data class Entry(val fsid: Long, val timestamp: Long)

    private val data = ConcurrentHashMap<String, Entry>()

    override suspend fun get(filePath: String): Long? {
        val e = data[filePath] ?: return null
        return if (System.currentTimeMillis() - e.timestamp < ttlMillis) {
            e.fsid
        } else {
            data.remove(filePath)
            null
        }
    }

    override suspend fun set(filePath: String, fsid: Long) {
        data[filePath] = Entry(fsid, System.currentTimeMillis())
    }

    override suspend fun setMany(fsidMap: Map<String, Long>) {
        val now = System.currentTimeMillis()
        fsidMap.forEach { (path, fsid) -> data[path] = Entry(fsid, now) }
    }

    override suspend fun clearDir(dirPath: String) {
        val prefix = dirPath.trimEnd('/') + "/"
        data.keys.filter { it.startsWith(prefix) }.forEach { data.remove(it) }
    }
}
