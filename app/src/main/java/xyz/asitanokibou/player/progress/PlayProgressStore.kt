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

/** 一部影片的观看进度:以番号(目录名)为唯一标识 */
@Serializable
data class PlayProgress(
    val fanCode: String,
    /** 已看到的位置(毫秒) */
    val positionMs: Long,
    /** 影片总时长(毫秒);采集时未知则为 0(此时不做"看完"判断) */
    val durationMs: Long,
    /** 最近一次更新时间戳(毫秒),用于淘汰最旧的记录 */
    val updatedAt: Long,
)

private val Context.playProgressDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "play_progress")

/**
 * 观看进度的本地持久化(DataStore Preferences + JSON 数组)。
 *
 * 写入者只有 [xyz.asitanokibou.player.service.PlaybackService]:它是唯一知道"现在真的在播"
 * 的一方。后台播放时 UI(及它的 MediaController)已经被销毁,任何挂在 UI 侧的采集器都会
 * 在退到后台后失效,所以采集权不下放到 UI —— UI 只读。
 *
 * 精度按"不需要太精确"设计:位置以 30 秒粒度的节流采集(见 PlaybackService),不做逐秒记录。
 * 读取时统一走 [resumePosition],把"看完"和"没真正开始看"两种不续播的情况在这里收敛掉。
 */
class PlayProgressStore(private val context: Context) {

    /** 全部记录,按最近更新时间倒序 */
    val entriesFlow: Flow<List<PlayProgress>> = context.playProgressDataStore.data.map { p ->
        decode(p[KEY_ENTRIES]).sortedByDescending { it.updatedAt }
    }

    suspend fun entries(): List<PlayProgress> = entriesFlow.first()

    /** 续播点:没有记录、位置过短时返回 null(调用方从 0 开始播)。写入侧已做过归一化,这里只做防御 */
    suspend fun resumePosition(fanCode: String): Long? {
        val code = normalize(fanCode) ?: return null
        val entry = entries().firstOrNull { it.fanCode == code } ?: return null
        return entry.positionMs.takeIf { it >= MIN_RESUME_MS }
    }

    /**
     * 记录/更新一部影片的观看进度。传入的位置会先被归一化,分三种情况:
     * - 位置 < [MIN_RESUME_MS]:视为“还没真正开始看”,**不写也不删**——用户可能只是拖回片头
     *   看了一眼,此时把已有的续播点清掉是纯粹的损失
     * - 已判定看完:删掉记录(二刷从片头开始),不保留“已看完”标记
     * - 其他:插入/覆盖,并按 [PlayProgress.updatedAt] 把超出 [MAX_ENTRIES] 的最旧条目淘汰
     *
     * DataStore 的 edit 是原子的读-改-写,定时采集与强制落盘(暂停/结束/服务销毁)并发调用安全;
     * 内容没有变化时 DataStore 自身会跳过写盘。
     */
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

    /** 删除一部影片的观看进度(影片被删除时联动清理);不存在则为 no-op */
    suspend fun remove(fanCode: String) {
        val code = normalize(fanCode) ?: return
        context.playProgressDataStore.edit { p ->
            val next = decode(p[KEY_ENTRIES]).filterNot { it.fanCode == code }
            p[KEY_ENTRIES] = json.encodeToString(next)
        }
    }

    /** 清空全部观看进度(设置页入口) */
    suspend fun clear() {
        context.playProgressDataStore.edit { p ->
            p[KEY_ENTRIES] = json.encodeToString(emptyList<PlayProgress>())
        }
    }

    private fun normalize(fanCode: String): String? =
        fanCode.trim().trim('/').ifEmpty { null }

    /** 剩余不足 [FINISH_REMAIN_MS] 或已看超过 [FINISH_RATIO] 视为看完;时长未知时不下判断 */
    private fun isFinished(positionMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0L) return false
        return positionMs >= durationMs - FINISH_REMAIN_MS ||
            positionMs >= (durationMs * FINISH_RATIO).toLong()
    }

    /** 超出上限时按最近更新时间保留最新的 [MAX_ENTRIES] 条 */
    private fun cap(list: List<PlayProgress>): List<PlayProgress> =
        if (list.size <= MAX_ENTRIES) list
        else list.sortedByDescending { it.updatedAt }.take(MAX_ENTRIES)

    private fun decode(raw: String?): List<PlayProgress> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<PlayProgress>>(raw) }
            .getOrDefault(emptyList())
    }

    companion object {
        /** 位置小于 30 秒视为没真正开始看:既不记录也不续播 */
        const val MIN_RESUME_MS = 30_000L

        /** 记录条数上限:超出按最近更新时间淘汰最旧的 */
        const val MAX_ENTRIES = 50

        /** 剩余不足 2 分钟算看完 */
        private const val FINISH_REMAIN_MS = 120_000L

        /** 已看超过 95% 算看完(覆盖片尾字幕很长的情况) */
        private const val FINISH_RATIO = 0.95

        private val json = Json { ignoreUnknownKeys = true }
        private val KEY_ENTRIES = stringPreferencesKey("entries")
    }
}
