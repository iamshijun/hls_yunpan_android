package xyz.asitanokibou.player.watchlater

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 一条稍后再看记录:以番号(目录名)为唯一标识,记录加入时间(毫秒时间戳) */
@Serializable
data class WatchLaterEntry(
    val fanCode: String,
    val addedAt: Long,
)

private val Context.watchLaterDataStore: DataStore<Preferences> by preferencesDataStore(name = "watch_later")

/**
 * 稍后再看列表的本地持久化(DataStore Preferences + JSON 数组)。
 * 只存 {fanCode, addedAt};标题/封面等详情在列表页用 fanCode 实时向影片服务拉取,
 * 不依赖添加时刻的快照(手动输入路径、详情未加载完就收藏也能在列表页补上标题)。
 * 排序不在这里做(调用方按需排),写入保持幂等。
 */
class WatchLaterStore(private val context: Context) {

    /** 当前列表(无序,按写入顺序);UI 侧再按 addedAt 倒序展示 */
    val entriesFlow: Flow<List<WatchLaterEntry>> = context.watchLaterDataStore.data.map { p ->
        decode(p[KEY_ENTRIES])
    }

    suspend fun list(): List<WatchLaterEntry> = entriesFlow.first()

    suspend fun contains(fanCode: String): Boolean = list().any { it.fanCode == fanCode }

    /** 加入;已存在则为 no-op(幂等)。新条目记入当前时间,供列表按加入时间倒序。 */
    suspend fun add(fanCode: String) {
        val normalized = fanCode.trim().trim('/')
        if (normalized.isEmpty()) return
        context.watchLaterDataStore.edit { p ->
            val current = decode(p[KEY_ENTRIES])
            if (current.none { it.fanCode == normalized }) {
                p[KEY_ENTRIES] = json.encodeToString(
                    current + WatchLaterEntry(normalized, System.currentTimeMillis()),
                )
            }
        }
    }

    /** 移除;不存在则为 no-op。 */
    suspend fun remove(fanCode: String) {
        context.watchLaterDataStore.edit { p ->
            val current = decode(p[KEY_ENTRIES])
            val next = current.filterNot { it.fanCode == fanCode }
            p[KEY_ENTRIES] = json.encodeToString(next)
        }
    }

    private fun decode(raw: String?): List<WatchLaterEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<WatchLaterEntry>>(raw) }
            .getOrDefault(emptyList())
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val KEY_ENTRIES = stringPreferencesKey("entries")
    }
}
