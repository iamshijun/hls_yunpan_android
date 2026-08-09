package xyz.asitanokibou.player.proxy

import xyz.asitanokibou.player.core.HlsPaths

class YunPathMapper(private val hlsRootPath: String = HlsPaths.PROXY_ROOT_PATH) {

    fun toYunPath(requestPath: String): String {
        var p = requestPath
        if (p.startsWith(hlsRootPath)) {
            p = p.substring(hlsRootPath.length)
        }
        return HlsPaths.BAIDU_MEDIA_ROOT + p
    }

    fun dirName(path: String): String {
        val idx = path.lastIndexOf('/')
        return if (idx <= 0) "/" else path.substring(0, idx)
    }
}
