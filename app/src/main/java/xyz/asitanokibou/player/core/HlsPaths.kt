package xyz.asitanokibou.player.core

import xyz.asitanokibou.player.BuildConfig

object HlsPaths {
    /** 网盘媒体根目录:/apps/<app_name>/movies,app_name = 开放平台注册应用名(见 build.gradle.kts 的 BAIDU_APP_NAME) */
    val BAIDU_MEDIA_ROOT: String = "/apps/${BuildConfig.BAIDU_APP_NAME}/movies"
    val APP_TRASH_PATH : String = "/apps/${BuildConfig.BAIDU_APP_NAME}/trash"
    const val PROXY_ROOT_PATH = "/hls"
    const val PLAYLIST_NAME = "playlist.m3u8"
    const val LOCAL_HOST = "127.0.0.1"

    fun playlistUrl(port: Int, relativeDir: String): String =
        "http://$LOCAL_HOST:$port$PROXY_ROOT_PATH/${relativeDir.trim('/')}/$PLAYLIST_NAME"

    fun toRelativePath(absolutePath: String): String =
        absolutePath.removePrefix("$BAIDU_MEDIA_ROOT/").trim('/')
}
