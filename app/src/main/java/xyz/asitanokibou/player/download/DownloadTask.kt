package xyz.asitanokibou.player.download

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 任务状态机:QUEUED → RUNNING → (PAUSED | FAILED | MISSING_SEGMENT | COMPLETED)
 *
 * [MISSING_SEGMENT]:某个分片在网盘目录里不存在,任务停下等用户决定
 * 「跳过分片」(记入 [DownloadTask.skippedSegments] 后继续)还是「重试」。
 */
enum class DownloadStatus {
    QUEUED,
    RUNNING,
    PAUSED,
    FAILED,
    MISSING_SEGMENT,
    COMPLETED,
}

/**
 * 下载任务:一次「把某番号的全部分片合并成一个 .ts」的持久化单元。
 *
 * task.json 与 media.ts.part 同目录;分片粒度续传:每完成一片,把索引追加进
 * completedSegments 并原子写回 task.json(先写临时文件再 rename)。
 * 删除任务 = 删除整个任务目录。
 */
@Serializable
data class DownloadTask(
    val fanCode: String,
    val segmentNames: List<String>,
    val completedSegments: List<Int> = emptyList(),
    /** 用户主动跳过的分片索引(网盘上不存在)。不影响合并,只是播放时画面不连续 */
    val skippedSegments: List<Int> = emptyList(),
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val error: String? = null,
    /**
     * 暂停来自移动数据闸门(而不是用户手动暂停)。只用于文案,不污染 [error] 的失败语义；
     * 回到非计费网络后这类任务会被自动续上。
     */
    val pausedByGate: Boolean = false,
    val totalBytes: Long = 0,
    val createdAtMillis: Long = System.currentTimeMillis(),
) {
    val totalSegments: Int get() = segmentNames.size
    val completedCount: Int get() = completedSegments.size
    val skippedCount: Int get() = skippedSegments.size
    /** 已处理分片数 = 下载成功 + 用户跳过 */
    val resolvedCount: Int get() = completedCount + skippedCount
    val progress: Float get() = if (totalSegments == 0) 0f else resolvedCount.toFloat() / totalSegments
    /** 下一个未处理分片(既没下完、也没跳过)的索引;全部处理完返回 -1 */
    val nextSegmentIndex: Int
        get() {
            val done = (completedSegments + skippedSegments).toHashSet()
            return segmentNames.indices.firstOrNull { it !in done } ?: -1
        }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
        const val TASK_FILE = "task.json"
        const val DATA_FILE = "media.ts.part"

        fun fromDir(dir: File): DownloadTask {
            val file = File(dir, TASK_FILE)
            require(file.exists()) { "任务文件不存在: ${file.path}" }
            return json.decodeFromString<DownloadTask>(file.readText())
        }
    }

    fun toDir(root: File): File = File(root, fanCode)

    fun save(dir: File) {
        val file = File(dir, TASK_FILE)
        val tmp = File(dir, "$TASK_FILE.tmp")
        tmp.writeText(json.encodeToString(this))
        if (!tmp.renameTo(file)) {
            // rename 失败(跨设备等罕见情况)退回直接写
            file.writeText(json.encodeToString(this))
            tmp.delete()
        }
    }
}
