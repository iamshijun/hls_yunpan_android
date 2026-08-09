package xyz.asitanokibou.player.data

import xyz.asitanokibou.player.baidu.BaiduClient
import xyz.asitanokibou.player.baidu.model.BaiduFile
import xyz.asitanokibou.player.core.HlsPaths

class MovieRepository(private val baidu: BaiduClient) {

    suspend fun directories(
        start: Int = 0,
        limit: Int = 20,
        order: String = "time",
        desc: Int = 1,
    ): List<BaiduFile> =
        baidu.getFileList(
            path = HlsPaths.BAIDU_MEDIA_ROOT,
            start = start,
            limit = limit,
            order = order,
            desc = desc,
        ).filter { it.isdir == 1 }

    fun toRelativePath(absolutePath: String): String = HlsPaths.toRelativePath(absolutePath)
}
