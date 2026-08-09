package xyz.asitanokibou.player.core

object HlsPaths {
    const val BAIDU_MEDIA_ROOT = "/apps/movies"
    const val PROXY_ROOT_PATH = "/hls"
    const val PLAYLIST_NAME = "playlist.m3u8"
    const val LOCAL_HOST = "127.0.0.1"

    fun playlistUrl(port: Int, relativeDir: String): String =
        "http://$LOCAL_HOST:$port$PROXY_ROOT_PATH/${relativeDir.trim('/')}/$PLAYLIST_NAME"

    fun toRelativePath(absolutePath: String): String =
        absolutePath.removePrefix("$BAIDU_MEDIA_ROOT/").trim('/')
}
