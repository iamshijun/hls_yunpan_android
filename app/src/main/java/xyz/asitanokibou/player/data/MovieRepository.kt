package xyz.asitanokibou.player.data

import xyz.asitanokibou.player.baidu.BaiduClient
import xyz.asitanokibou.player.baidu.model.BaiduFile
import xyz.asitanokibou.player.core.HlsPaths

class MovieRepository(
    private val baidu: BaiduClient,
    private val movieInfoClient: MovieInfoClient? = null,
) {

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

    /** 按番号批量查影片详情;未配置服务时返回空 map */
    suspend fun movieInfo(fanCodes: List<String>): Map<String, MovieInfo> =
        movieInfoClient?.findByFanCodes(fanCodes) ?: emptyMap()

    fun toRelativePath(absolutePath: String): String = HlsPaths.toRelativePath(absolutePath)
}
