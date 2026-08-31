package xyz.asitanokibou.player.data

import xyz.asitanokibou.player.baidu.BaiduClient
import xyz.asitanokibou.player.baidu.model.BaiduFile
import xyz.asitanokibou.player.core.HlsPaths

/**
 * 影片库:网盘目录分页 + 影片详情查询的统一入口。
 *
 * 列表页(分页目录)与播放页(单部详情)都只经过这一条接缝;
 * 详情服务未配置或查询失败时统一静默降级(空 map / null),由 UI 回退显示番号。
 */
class MovieRepository(
    private val baidu: BaiduClient,
    private val movieInfoClient: MovieInfoClient? = null,
) {

    /** 影片根目录下第 [page] 页目录(page 从 1 开始) */
    suspend fun directories(
        page: Int,
        limit: Int = DEFAULT_PAGE_SIZE,
        order: String = "time",
        desc: Boolean = true,
    ): List<BaiduFile> =
        baidu.getFileList(
            path = HlsPaths.BAIDU_MEDIA_ROOT,
            start = (page - 1) * limit,
            limit = limit,
            order = order,
            desc = if (desc) 1 else 0,
        ).filter { it.isdir == 1 }

    /** 按番号批量查影片详情;未配置服务时返回空 map */
    suspend fun movieInfo(fanCodes: List<String>): Map<String, MovieInfo> =
        movieInfoClient?.findByFanCodes(fanCodes) ?: emptyMap()

    /** 删除影片根目录下的一个目录(移入网盘回收站)。失败抛 BaiduApiException */
    suspend fun deleteDirectory(relativePath: String) {
        val mediaPath = "${HlsPaths.BAIDU_MEDIA_ROOT}/${relativePath.trim('/')}"
//        val trashPath = "${HlsPaths.APP_TRASH_PATH}/${relativePath.trim('/')}"
        baidu.deleteFiles(listOf(mediaPath))
//        baidu.rename(mediaPath, trashPath)
    }

    /** 删除影片服务端记录(可选服务;未配置或失败均静默,不影响网盘删除结果) */
    suspend fun deleteRemoteInfo(fanCode: String) {
        movieInfoClient?.deleteByFanCode(fanCode)
    }

    /** 单部影片详情;未配置服务或查询失败返回 null,由 UI 静默降级显示番号 */
    suspend fun detail(fanCode: String): MovieInfo? =
        movieInfoClient?.findDetail(fanCode)

    companion object {
        const val DEFAULT_PAGE_SIZE = 20
    }
}
