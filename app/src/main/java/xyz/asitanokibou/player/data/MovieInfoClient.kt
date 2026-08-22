package xyz.asitanokibou.player.data

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json

/**
 * 影片详情信息(movie_api 客户端)。
 *
 * 批量按番号查询:`GET <baseUrl>/api/movies?fan_code=a,b,c&size=n`,
 * 返回 `fan_code -> [MovieInfo]` 映射。任何失败(地址为空/网络/HTTP/解析)
 * 都不抛异常,返回空 map,由 UI 回退显示番号。
 *
 * @param baseUrlProvider 返回当前配置的服务地址(来源于设置,可动态变化)
 */
class MovieInfoClient(
    private val baseUrlProvider: () -> String?,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val http: HttpClient = HttpClient(OkHttp) {
        expectSuccess = true
        install(HttpTimeout) {
            connectTimeoutMillis = API_TIMEOUT_MS
            requestTimeoutMillis = API_TIMEOUT_MS
            socketTimeoutMillis = API_TIMEOUT_MS
        }
    }

    suspend fun findByFanCodes(fanCodes: List<String>): Map<String, MovieInfo> {
        if (fanCodes.isEmpty()) return emptyMap()
        val base = baseUrlProvider()?.trim()?.trimEnd('/')
        if (base.isNullOrEmpty()) return emptyMap()
        return withContext(Dispatchers.IO) {
            try {
                val text = http.get("$base$MOVIES_PATH") {
                    parameter("page", 1)
                    parameter("size", fanCodes.size)
                    parameter("fan_code", fanCodes.joinToString(","))
                }.bodyAsText()
                val resp = json.decodeFromString<MoviesResponse>(text)
                resp.data.associateBy({ it.fanCode }, { it.toInfo() })
            } catch (e: Exception) {
                Log.e(TAG, "批量获取影片详情失败: ${e.message}")
                emptyMap()
            }
        }
    }

    /** 按番号查单部影片详情;任何失败(未配置地址/网络/HTTP 404 等)返回 null,由 UI 静默降级 */
    suspend fun findDetail(fanCode: String): MovieInfo? {
        val code = fanCode.trim().trim('/')
        if (code.isEmpty()) return null
        val base = baseUrlProvider()?.trim()?.trimEnd('/')
        if (base.isNullOrEmpty()) return null
        return withContext(Dispatchers.IO) {
            try {
                val text = http.get("$base$MOVIES_PATH/$code").bodyAsText()
                json.decodeFromString<MovieDto>(text).toInfo()
            } catch (e: Exception) {
                Log.e(TAG, "获取影片详情失败: ${e.message}")
                null
            }
        }
    }

    fun close() {
        http.close()
    }

    companion object {
        private const val TAG = "MovieInfoClient"
        private const val MOVIES_PATH = "/api/movies"
        private const val API_TIMEOUT_MS = 15_000L
    }
}

/** 影片元信息(列表展示 + 播放页详情) */
data class MovieInfo(
    val fanCode: String,
    val title: String,
    val casts: List<String> = emptyList(),
    val cover: String? = null,
    val thumbnail: String? = null,
    val year: Int? = null,
)

@Serializable
private data class MoviesResponse(
    val total: Int = 0,
    val page: Int = 1,
    val size: Int = 0,
    val data: List<MovieDto> = emptyList(),
)

@Serializable
private data class MovieDto(
    @SerialName("fan_code") val fanCode: String = "",
    val title: String = "",
    val year: Int? = null,
    val cover: String? = null,
    val thumbnail: String? = null,
    val duration: Int? = null,
    val casts: List<String> = emptyList(),
    val labels: List<String> = emptyList(),
) {
    fun toInfo() = MovieInfo(
        fanCode = fanCode,
        title = cleanTitle(fanCode, title),
        casts = casts,
        cover = cover,
        thumbnail = thumbnail,
        year = year,
    )

    companion object {
        /** 删除 title 中包含的番号(通常在开头),剩下的作为展示标题 */
        fun cleanTitle(fanCode: String, title: String): String {
            val cleaned = if (fanCode.isNotBlank() && title.contains(fanCode)) {
                title.replace(fanCode, "")
            } else {
                title
            }.trim(' ', '\t', '-', '_', '　')
            return cleaned.ifBlank { fanCode }
        }
    }
}
