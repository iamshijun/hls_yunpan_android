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

    suspend fun rename(path: String, newPath: String)

    suspend fun downloadBytes(fsid: Long): ByteArray

    /**
     * 批量预取下载直链(`filemetas` 一次可带多个 fsid)。
     * 已在缓存里且未过期的会被跳过;下载路径靠它把「每片一次换直链」的 RTT 摊薄掉。
     */
    suspend fun prefetchDownloadUrls(fsids: List<Long>)

    /** 直链作废(重试前调用):下次取会重新向服务端要 */
    fun invalidateDownloadUrl(fsid: Long)

    /**
     * 流式下载。传入 [probe] 时由实现填充直链与响应头的耗时分解(诊断用);
     * 不关心的调用方(如代理转发)传 null 即可。
     */
    suspend fun <T> openDownloadStream(
        fsid: Long,
        probe: StreamProbe? = null,
        block: suspend (ByteReadChannel) -> T,
    ): T

    fun close()

    companion object {
        const val BATCH_SIZE = 1000
    }
}
