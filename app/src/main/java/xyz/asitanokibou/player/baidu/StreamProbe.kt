package xyz.asitanokibou.player.baidu

/**
 * 一次 [BaiduClient.openDownloadStream] 的耗时分解(毫秒,`SystemClock.elapsedRealtime` 基准)。
 *
 * 诊断「下载慢」必须能分开三段成本:
 * - [dlinkMs]:filemetas 换直链(纯 RTT + 服务端处理)
 * - [headersMs]:发出下载请求 → 收到响应头(含 302 跳转与 TLS/连接建立)
 * - 首字节与总耗时由调用方在自己的读循环里测(见 DownloadManager)
 *
 * 调用方可选传入(null 表示不关心),代理转发路径不填。
 */
class StreamProbe {
    /** 换直链耗时;0 表示未测量 */
    var dlinkMs: Long = 0

    /** 发起下载请求 → 拿到响应头 */
    var headersMs: Long = 0

    /** 响应体首字节时刻(未在类内测量,留给调用方按需填写) */
    var firstByteMs: Long = -1

    /** 响应头里的 Content-Length;-1 表示服务端未给(调用方据此判断是否被截断) */
    var contentLength: Long = -1
}
