package xyz.asitanokibou.player.download

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** 任务状态机:QUEUED → RUNNING → (PAUSED | FAILED | COMPLETED) */
enum class DownloadStatus {
    QUEUED,
    RUNNING,
    PAUSED,
    FAILED,
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
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val error: String? = null,
    val totalBytes: Long = 0,
    val createdAtMillis: Long = System.currentTimeMillis(),
) {
    val totalSegments: Int get() = segmentNames.size
    val completedCount: Int get() = completedSegments.size
    val progress: Float get() = if (totalSegments == 0) 0f else completedCount.toFloat() / totalSegments
    /** 下一个未完成分片在 segmentNames 中的索引;全部完成返回 -1 */
    val nextSegmentIndex: Int
        get() = segmentNames.indices.firstOrNull { it !in completedSegments.toHashSet() } ?: -1

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
