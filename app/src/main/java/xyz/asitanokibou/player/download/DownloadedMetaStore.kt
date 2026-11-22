package xyz.asitanokibou.player.download

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 一次已发布下载的「过程事实」——目前只有缺片。
 *
 * 为什么需要这份侧车:缺片是下载过程的结果,而任务发布成功后 `task.json` 即被删除,
 * MediaStore 只记录文件本身,MISSING 的信息无处可去。
 *
 * 约定:**只有存在缺片的条目才有记录**(无缺片 = 没有记录 = 列表不显示任何缺片文案)。
 * 这样文件远多于记录,读取时顺手 GC 掉的代价才小。
 */
@Serializable
data class DownloadedMeta(
    val fanCode: String,
    /** 下载结束时被跳过(网盘上不存在)的分片数 */
    val skippedCount: Int,
    /** 该次下载的总分片数,用于展示「缺 3 片(共 1480 片)」 */
    val totalSegments: Int,
    /**
     * 具体跳过哪些分片。当前只用于排查;保留它是为了将来能「只补下缺失的几片、
     * 按偏移回插」—— 不存的话那时只能重扫 playlist。
     */
    val skippedIndices: List<Int> = emptyList(),
    val atMillis: Long = System.currentTimeMillis(),
)

/**
 * `downloaded_meta.json` 存取(应用私有目录,与 MediaStore 里的文件一一对照)。
 *
 * 一致性规则:
 * - 发布时按番号**覆盖**写入;本次无缺片则删除该番号的旧记录(重下即重置);
 * - 删除已完成条目时同步删 key;
 * - 读取时以 MediaStore 现存的番号集合为准 GC —— 用户从系统相册直接删文件时,
 *   这是唯一能清理脏 key 的时机。
 *
 * 文件很小(只含有缺片的条目),故用同步 IO + 一把锁;调用方多在 IO 线程。
 */
object DownloadedMetaStore {

    const val FILE_NAME = "downloaded_meta.json"

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val lock = Any()

    /** 读出全部记录;文件缺失/损坏一律当空表(侧车丢了大不了不显示缺片数) */
    fun readAll(context: Context): Map<String, DownloadedMeta> = synchronized(lock) {
        val file = fileOf(context)
        if (!file.exists()) return emptyMap()
        runCatching {
            json.decodeFromString<Map<String, DownloadedMeta>>(file.readText())
        }.getOrDefault(emptyMap())
    }

    /**
     * 记录一次已完成的下载。`skippedIndices` 为空表示本次没有缺片 —— 此时删除旧记录,
     * 而不是写一条 skippedCount=0 的空记录。
     */
    fun record(context: Context, fanCode: String, skippedIndices: List<Int>, totalSegments: Int) {
        synchronized(lock) {
            val all = readAll(context).toMutableMap()
            if (skippedIndices.isEmpty()) {
                if (all.remove(fanCode) == null) return
            } else {
                all[fanCode] = DownloadedMeta(
                    fanCode = fanCode,
                    skippedCount = skippedIndices.size,
                    totalSegments = totalSegments,
                    skippedIndices = skippedIndices.sorted(),
                )
            }
            write(context, all)
        }
    }

    fun remove(context: Context, fanCode: String) {
        synchronized(lock) {
            val all = readAll(context).toMutableMap()
            if (all.remove(fanCode) == null) return
            write(context, all)
        }
    }

    /** 只保留 [keepFanCodes] 里还存在的记录(MediaStore 已无此文件 → 记录作废) */
    fun retainOnly(context: Context, keepFanCodes: Set<String>) {
        synchronized(lock) {
            val all = readAll(context)
            val kept = all.filterKeys { it in keepFanCodes }
            if (kept.size == all.size) return
            write(context, kept)
        }
    }

    private fun write(context: Context, all: Map<String, DownloadedMeta>) {
        val file = fileOf(context)
        if (all.isEmpty()) {
            file.delete()
            return
        }
        val tmp = File(file.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(json.encodeToString(all))
        if (!tmp.renameTo(file)) {
            file.writeText(json.encodeToString(all))
            tmp.delete()
        }
    }

    private fun fileOf(context: Context) = File(context.filesDir, FILE_NAME)
}
