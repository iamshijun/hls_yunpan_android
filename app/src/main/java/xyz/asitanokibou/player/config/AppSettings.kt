package xyz.asitanokibou.player.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 应用配置
 * 手机端不需要 Redis；port=0 表示自动选择空闲端口。
 */
data class AppConfig(
    val accessToken: String? = null,
    val cacheTtlSec: Long = 3600,
    val port: Int = 0,
    val movieApiBaseUrl: String = DEFAULT_MOVIE_API_BASE_URL,
    /** 后台播放:离开应用(Home/锁屏/切后台)时是否继续播放,默认开启 */
    val backgroundPlayback: Boolean = true,
) {
    companion object {
        /** 影片信息服务(movie_api)地址,末尾带路径前缀 */
        const val DEFAULT_MOVIE_API_BASE_URL = "https://www.asitanokibou.xyz/movies/"
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class AppSettings(private val context: Context) {

    val configFlow: Flow<AppConfig> = context.dataStore.data.map { p ->
        AppConfig(
            accessToken = p[KEY_TOKEN],
            cacheTtlSec = p[KEY_TTL] ?: 3600,
            port = p[KEY_PORT] ?: 0,
            movieApiBaseUrl = p[KEY_MOVIE_API_BASE_URL] ?: AppConfig.DEFAULT_MOVIE_API_BASE_URL,
            backgroundPlayback = p[KEY_BACKGROUND_PLAYBACK] ?: true,
        )
    }

    /** 读取一次当前配置快照 */
    suspend fun get(): AppConfig = configFlow.first()

    suspend fun setAccessToken(token: String) {
        context.dataStore.edit { it[KEY_TOKEN] = token }
    }

    suspend fun update(config: AppConfig) {
        context.dataStore.edit { p ->
            config.accessToken?.let { p[KEY_TOKEN] = it }
            p[KEY_TTL] = config.cacheTtlSec
            p[KEY_PORT] = config.port
            p[KEY_MOVIE_API_BASE_URL] = config.movieApiBaseUrl
            p[KEY_BACKGROUND_PLAYBACK] = config.backgroundPlayback
        }
    }

    companion object {
        private val KEY_TOKEN = stringPreferencesKey("access_token")
        private val KEY_TTL = longPreferencesKey("cache_ttl_sec")
        private val KEY_PORT = intPreferencesKey("port")
        private val KEY_MOVIE_API_BASE_URL = stringPreferencesKey("movie_api_base_url")
        private val KEY_BACKGROUND_PLAYBACK = booleanPreferencesKey("background_playback")
    }
}
