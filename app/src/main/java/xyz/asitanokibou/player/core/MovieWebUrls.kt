package xyz.asitanokibou.player.core

import android.net.Uri

/**
 * 影片网页(movie_web 仓库,线上 https://www.asitanokibou.xyz/movies/)的地址构造。
 *
 * 这是 App 与 movie_web 之间唯一的地址约定,路由名与查询参数名两边必须同步:
 * - `index.html`          影库首页(App 内 WebView 入口)
 * - `index.html?cast=`    影库首页的演员筛选态(与 movie_web/js/library.js 的 `qs.get('cast')` 一致)
 * - `play.html?path=`     播放页(兼分享落地页,参数名与 movie_web/js/common.js 的 playPageUrl 一致)
 *
 * 分享链接指向 https 播放页而不是 `hlspan://`:自定义 scheme 在多数聊天工具里不可点,
 * 而 https 链接任何设备都能打开,播放页在 Android 上会立刻尝试 `hlspan://play/<番号>`
 * 调起本应用(见 movie_web/js/play.js 的 attemptAppLaunch),调不起时还能直接网页播放。
 *
 * base = 设置中的「影片信息服务地址」;为空(未配置)时返回 null,由调用方自行降级。
 */
object MovieWebUrls {

    fun index(base: String?): String? = normalize(base)?.let { "$it/$INDEX_PAGE" }

    /**
     * 演员筛选页:`index.html?cast=<演员名>`,与首页是同一个页面。
     * movie_web 的 library.js 启动时用 `qs.get('cast')` 回填筛选状态(state.casts),
     * 抽屉里的演员名与影片详情里的演员名同源,所以点演员名即可直接列出其全部影片。
     * base 未配置或演员名为空时返回 null(调用方自行降级)。
     */
    fun castFilter(base: String?, cast: String): String? =
        cast.trim().takeIf { it.isNotEmpty() }?.let { name ->
            normalize(base)?.let { "$it/$INDEX_PAGE?$CAST_PARAM=${Uri.encode(name)}" }
        }

    /** 分享用的 https 播放页地址;base 未配置时返回 null(调用方回退到 [DeepLink.link])。 */
    fun play(base: String?, fanCode: String): String? = normalize(base)?.let {
        "$it/$PLAY_PAGE?path=${Uri.encode(fanCode.trim('/'))}"
    }

    private fun normalize(base: String?): String? =
        base?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }

    private const val INDEX_PAGE = "index.html"
    private const val PLAY_PAGE = "play.html"
    private const val CAST_PARAM = "cast"
}
