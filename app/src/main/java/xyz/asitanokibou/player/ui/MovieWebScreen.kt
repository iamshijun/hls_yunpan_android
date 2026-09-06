package xyz.asitanokibou.player.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 由设置中的「影片信息服务地址」拼出网页入口地址：base + /index.html。
 * 地址留空(未配置)时返回 null，入口卡不提供跳转。
 */
internal fun movieIndexUrl(movieApiBaseUrl: String?): String? {
    val base = movieApiBaseUrl?.trim()?.trimEnd('/').orEmpty()
    if (base.isEmpty()) return null
    return "$base/index.html"
}

/** 持有 WebView 实例供返回/刷新/销毁等事件回调使用(普通引用,不驱动重组) */
private class WebViewRef {
    var view: WebView? = null
}

/**
 * 影片信息服务网页(WebView 浏览)。
 *
 * - 仅 http(s) 链接留在 WebView 内导航;自定义 scheme(如 hlspan://play/<code>)
 *   交给系统处理 —— 本应用注册了 hlspan scheme,命中即跳回播放页。
 * - 页面内可后退时,系统返回键先回退网页历史;退回首页后再由外层导航弹栈。
 * - 离开本页即销毁 WebView(不做页面状态缓存,重新进入会重新加载)。
 */
@Composable
internal fun MovieWebScreen(
    url: String,
    onBack: () -> Unit,
) {
    val webRef = remember { WebViewRef() }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf<String?>(null) }
    var canGoBack by remember { mutableStateOf(false) }

    // 页面内可后退时优先回退网页历史;子 BackHandler 优先于 AppRoot 的弹栈处理
    BackHandler(enabled = canGoBack) {
        webRef.view?.goBack()
    }

    // 离开本页时停掉加载并销毁 WebView,避免后台继续加载与内存泄漏
    DisposableEffect(Unit) {
        onDispose {
            // destroy 会停止一切加载并释放内部状态;先 stopLoading 再销毁
            webRef.view?.stopLoading()
            webRef.view?.destroy()
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("← 返回") }
                Spacer(Modifier.width(8.dp))
                Text("影片服务网页", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = {
                        failed = null
                        loading = true
                        webRef.view?.reload()
                    },
                    enabled = !loading,
                ) { Text("刷新") }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).also { wv ->
                            wv.layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            wv.settings.javaScriptEnabled = true
                            wv.settings.domStorageEnabled = true
                            wv.webViewClient = object : WebViewClient() {
                                override fun onPageStarted(
                                    view: WebView?,
                                    pageUrl: String?,
                                    favicon: Bitmap?,
                                ) {
                                    loading = true
                                    failed = null
                                }

                                override fun onPageFinished(view: WebView?, pageUrl: String?) {
                                    loading = false
                                }

                                override fun onPageCommitVisible(view: WebView?, pageUrl: String?) {
                                    canGoBack = view?.canGoBack() == true
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                ): Boolean {
                                    val target = request?.url ?: return false
                                    return openExternallyIfCustomScheme(view, target.toString())
                                }

                                @Suppress("DEPRECATION")
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    url: String?,
                                ): Boolean {
                                    return url?.let { openExternallyIfCustomScheme(view, it) } ?: false
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?,
                                ) {
                                    // 仅主框架失败才阻断展示;子资源(封面图等)失败不影响页面
                                    if (request?.isForMainFrame == true) {
                                        loading = false
                                        failed = error?.description?.toString() ?: "页面加载失败"
                                    }
                                }
                            }
                            webRef.view = wv
                            wv.loadUrl(url)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                if (loading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                failed?.let { message ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text("页面加载失败：$message", color = MaterialTheme.colorScheme.error)
                        TextButton(
                            onClick = {
                                failed = null
                                loading = true
                                webRef.view?.loadUrl(url)
                            },
                        ) { Text("重试") }
                    }
                }
            }
        }
    }
}

/**
 * 除 http(s)(以及 about/data/javascript/blob 等内建 scheme)外的链接
 * 交给系统处理:本应用注册了 hlspan://play/<code> 深链,网页里的影片入口
 * 命中后即回到 App 播放页。没有能处理的 App 时吞掉点击(不打断网页浏览)。
 */
private fun openExternallyIfCustomScheme(view: WebView?, url: String): Boolean {
    val scheme = Uri.parse(url).scheme?.lowercase()
    if (scheme == null ||
        scheme == "http" || scheme == "https" ||
        scheme == "about" || scheme == "data" || scheme == "javascript" || scheme == "blob"
    ) {
        return false
    }
    return try {
        view?.context?.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        true
    } catch (e: Exception) {
        Log.w(TAG, "无法处理外部链接 $url: ${e.message}")
        true
    }
}

private const val TAG = "MovieWebScreen"
