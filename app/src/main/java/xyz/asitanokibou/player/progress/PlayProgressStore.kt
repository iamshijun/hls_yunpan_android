package xyz.asitanokibou.player.progress

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

@Serializable
data class PlayProgress(
    val fanCode: String,
    val positionMs: Long,
    /** 未知为 0(此时不做「看完」判断) */
    val durationMs: Long,
    val updatedAt: Long,
)

private val Context.playProgressDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "play_progress")

class PlayProgressStore(private val context: Context) {

    val entriesFlow: Flow<List<PlayProgress>> = context.playProgressDataStore.data.map { p ->
        decode(p[KEY_ENTRIES]).sortedByDescending { it.updatedAt }
    }

    suspend fun entries(): List<PlayProgress> = entriesFlow.first()

    /**
     * null ⇒ 调用方从 0 播;归一化只在写入侧做一次。上界:落在「看完」区间的记录不可信(片源可能已换短),
     * 不续播——否则 prepare 完就停在片尾,这条记录还会被判成「看完」删掉。
     */
    suspend fun resumePosition(fanCode: String): Long? {
        val code = normalize(fanCode) ?: return null
        val entry = entries().firstOrNull { it.fanCode == code } ?: return null
        return entry.positionMs.takeIf { it >= MIN_RESUME_MS && !isFinished(it, entry.durationMs) }
    }

    /**
     * 位置 < [MIN_RESUME_MS] 时不写也不删:用户可能只是拖回片头看一眼,删掉已有续播点是纯粹的损失。
     * [androidx.datastore.preferences.core.edit] 的读-改-写是原子的,定时采集与强制落盘可并发调用。
     */
    @PlaybackServiceOnly
    suspend fun record(fanCode: String, positionMs: Long, durationMs: Long) {
        val code = normalize(fanCode) ?: return
        if (positionMs < MIN_RESUME_MS) return
        val finished = isFinished(positionMs, durationMs)
        context.playProgressDataStore.edit { p ->
            val current = decode(p[KEY_ENTRIES]).filterNot { it.fanCode == code }
            val next = if (finished) {
                current
            } else {
                current + PlayProgress(
                    fanCode = code,
                    positionMs = positionMs,
                    durationMs = durationMs.coerceAtLeast(0L),
                    updatedAt = System.currentTimeMillis(),
                )
            }
            p[KEY_ENTRIES] = json.encodeToString(cap(next))
        }
    }

    suspend fun remove(fanCode: String) {
        val code = normalize(fanCode) ?: return
        context.playProgressDataStore.edit { p ->
            val next = decode(p[KEY_ENTRIES]).filterNot { it.fanCode == code }
            p[KEY_ENTRIES] = json.encodeToString(next)
        }
    }

    suspend fun clear() {
        context.playProgressDataStore.edit { p ->
            p[KEY_ENTRIES] = json.encodeToString(emptyList<PlayProgress>())
        }
    }

    private fun normalize(fanCode: String): String? =
        fanCode.trim().trim('/').ifEmpty { null }

    private fun isFinished(positionMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0L) return false
        return positionMs >= durationMs - FINISH_REMAIN_MS ||
            positionMs >= (durationMs * FINISH_RATIO).toLong()
    }

    private fun cap(list: List<PlayProgress>): List<PlayProgress> =
        if (list.size <= MAX_ENTRIES) list
        else list.sortedByDescending { it.updatedAt }.take(MAX_ENTRIES)

    private fun decode(raw: String?): List<PlayProgress> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<PlayProgress>>(raw) }
            .getOrDefault(emptyList())
    }

    companion object {
        const val MIN_RESUME_MS = 30_000L

        const val MAX_ENTRIES = 50

        private const val FINISH_REMAIN_MS = 120_000L

        /** 不用 100%:片尾字幕可能很长 */
        private const val FINISH_RATIO = 0.95

        private val json = Json { ignoreUnknownKeys = true }
        private val KEY_ENTRIES = stringPreferencesKey("entries")
    }
}
