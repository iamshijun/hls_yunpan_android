package xyz.asitanokibou.player.core

import io.ktor.http.HttpStatusCode
import xyz.asitanokibou.player.baidu.BaiduApiException

object ProxyErrors {
    fun statusFor(e: Exception): HttpStatusCode = when {
        e is BaiduApiException && e.message?.contains("未配置 access_token") == true ->
            HttpStatusCode.Unauthorized
        e is BaiduApiException -> HttpStatusCode.BadGateway
        else -> HttpStatusCode.InternalServerError
    }

    fun messageFor(e: Exception): String = when {
        e is BaiduApiException && e.message?.contains("未配置 access_token") == true ->
            "请先在设置中填写 access_token"
        e is BaiduApiException -> e.message ?: "百度API错误"
        else -> "服务器内部错误"
    }
}
