package xyz.asitanokibou.player.cache

interface ContentCache {
    suspend fun get(path: String): ByteArray?
    suspend fun set(path: String, content: ByteArray)
    suspend fun delete(path: String)
    suspend fun clearExpired(): Int
}
