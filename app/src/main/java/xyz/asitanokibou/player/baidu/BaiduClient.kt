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

    /**
     * 按路径删除（移入网盘回收站，非永久删除）。
     * 任一路径失败或整体 errno != 0 时抛 [BaiduApiException]，调用方自行处理。
     */
    suspend fun deleteFiles(paths: List<String>)

    suspend fun downloadBytes(fsid: Long): ByteArray

    suspend fun <T> openDownloadStream(fsid: Long, block: suspend (ByteReadChannel) -> T): T

    fun close()

    companion object {
        const val BATCH_SIZE = 1000
    }
}
