package xyz.asitanokibou.player.core

import android.content.Intent
import android.net.Uri

/**
 * 深链解析:`hlspan://play/<fan_code>`
 * - scheme = hlspan
 * - host   = play
 * - path   = fan_code(单段,即百度云 /apps/${app_name}/movies/ 下的目录名)
 */
object DeepLink {
    const val SCHEME = "hlspan"
    const val HOST = "play"

    /** 从深链 intent 中提取 fan_code;非深链(普通启动)返回 null。 */
    fun fanCode(intent: Intent?): String? {
        val uri: Uri = intent?.data ?: return null
        if (uri.scheme != SCHEME || uri.host != HOST) return null
        return uri.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() }
    }
}
