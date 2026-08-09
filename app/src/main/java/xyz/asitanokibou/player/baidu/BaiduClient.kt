package xyz.asitanokibou.player.baidu

import io.ktor.utils.io.ByteReadChannel
import xyz.asitanokibou.player.baidu.model.BaiduFile

interface BaiduClient {
    suspend fun getFileListAll(
        path: String = "/",
        order: String = "name",
        desc: Int = 1,
        batchSize: Int = BATCH_SIZE,
    ): List<BaiduFile>

    suspend fun getFileList(
        path: String = "/",
        start: Int = 0,
        limit: Int = BATCH_SIZE,
        order: String = "name",
        desc: Int = 1,
    ): List<BaiduFile>

    suspend fun downloadBytes(fsid: Long): ByteArray

    suspend fun <T> openDownloadStream(fsid: Long, block: suspend (ByteReadChannel) -> T): T

    fun close()

    companion object {
        const val BATCH_SIZE = 1000
    }
}
