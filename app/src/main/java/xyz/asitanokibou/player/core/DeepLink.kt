package xyz.asitanokibou.player.core

import android.content.Intent
import android.net.Uri

/**
 * 深链格式与解析:`hlspan://play/<fan_code>`(由 [link] 生成,由 [fanCode] 解析,两者必须成对使用)
 * - scheme = hlspan
 * - host   = play
 * - path   = fan_code(即百度云 /apps/${app_name}/movies/ 下的目录名)
 */
object DeepLink {
    const val SCHEME = "hlspan"
    const val HOST = "play"

    /**
     * 生成分享链接 `hlspan://play/<fan_code>`。
     *
     * 番号(网盘目录名)可能含中文、空格、`#`、`&` 等字符,统一交给 Uri.Builder 做百分号编码;
     * 接收端 Uri.pathSegments 会自动解码,与 [fanCode] 严格对称(不要手工拼字符串)。
     */
    fun link(fanCode: String): String = Uri.Builder()
        .scheme(SCHEME)
        .authority(HOST)
        .appendPath(fanCode.trim('/'))
        .build()
        .toString()

    /** 从深链 intent 中提取 fan_code;非深链(普通启动)返回 null。 */
    fun fanCode(intent: Intent?): String? = intent?.data?.let { fanCode(it) }

    /** 从深链 uri 中提取 fan_code;非本应用深链返回 null。
     *  路径按整段拼接还原(嵌套目录的 `/` 在 [link] 里被编码为 %2F,segments 解码后会再拼回来)。 */
    fun fanCode(uri: Uri): String? {
        if (uri.scheme != SCHEME || uri.host != HOST) return null
        return uri.pathSegments
            .filter { it.isNotBlank() }
            .joinToString("/")
            .takeIf { it.isNotBlank() }
    }
}
