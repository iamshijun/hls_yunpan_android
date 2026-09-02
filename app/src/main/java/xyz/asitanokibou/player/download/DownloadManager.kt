package xyz.asitanokibou.player.download

import android.content.Context
import android.util.Log
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import xyz.asitanokibou.player.baidu.BaiduClient
import xyz.asitanokibou.player.core.HlsPaths
import xyz.asitanokibou.player.service.DownloadService
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/**
 * 下载调度器(单例,AppContainer 持有):
 * - 并发队列:最多 [MAX_CONCURRENT](=3) 个任务同时下载,其余 QUEUED 排队([pump] 空出槽位即补位)
 * - 分片顺序下载,失败自动重试 3 次(1s/2s/4s 退避)后转 FAILED
 * - 分片粒度续传:每片完成即追加 .part + 原子写回 task.json
 * - 全部完成后经 [DownloadedStore] 以 IS_PENDING 原子发布,清理工作目录
 *
 * UI 只读 [tasks](StateFlow)即得全部状态;操作入口
 * [enqueue]/[pause]/[resume]/[cancel]/[retry]。
 * 速度由 UI 层对 totalBytes 差分采样得到(每秒一次),本类不维护。
 */
class DownloadManager(
    private val context: Context,
    private val baidu: BaiduClient,
    rootDir: File? = null,
) {
    private val downloadsRoot: File =
        rootDir ?: File(context.getExternalFilesDir(null) ?: context.filesDir, "downloads")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks

    /** 正在运行的下载任务:fanCode -> Job,容量不超过 [MAX_CONCURRENT] */
    private val runningJobs = ConcurrentHashMap<String, Job>()

    /**
     * 请求暂停的任务集合(分片粒度:当前片下完即停)。
     * pause 先落状态再登记标记,runTask 在解析前与每片之间检查并消费;
     * resume 会移除标记,避免"暂停瞬间又恢复"把任务打回 PAUSED。
     */
    private val pauseRequested = ConcurrentHashMap.newKeySet<String>()

    /** 串行化"抢槽启动"的互斥锁:防止并发 pump 重复启动同一任务/超发槽位 */
    private val pumpMutex = Mutex()

    /** 入队去重与列表更新的互斥锁 */
    private val enqueueMutex = Mutex()

    init {
        restoreFromDisk()
    }

    // ---- 对外操作 ----

    /**
     * 加入下载队列。返回 null 表示已接受;否则为拒绝原因(已下载/已在列表中)。
     */
    suspend fun enqueue(fanCode: String): String? = withContext(Dispatchers.IO) {
        enqueueMutex.withLock {
            val code = fanCode.trim().trim('/')
            if (code.isEmpty()) return@withLock "番号为空"
            if (_tasks.value.any { it.fanCode == code }) {
                return@withLock "已在下载列表中"
            }
            if (DownloadedStore.exists(context, code)) {
                return@withLock "已下载完成,如需重新下载请先删除"
            }
            val task = DownloadTask(fanCode = code, segmentNames = emptyList())
            val dir = task.toDir(downloadsRoot)
            if (dir.exists()) dir.deleteRecursively()
            dir.mkdirs()
            task.save(dir)
            _tasks.value = _tasks.value + task
            Log.i(TAG, "新任务入队: $code")
            pump()
            null
        }
    }

    /** 暂停:当前分片下完即停(分片粒度续传);排队中的任务直接转 PAUSED */
    fun pause(fanCode: String) {
        val status = current(fanCode)?.status
        if (status == DownloadStatus.RUNNING || status == DownloadStatus.QUEUED) {
            // 正在跑(含刚被 pump 抢到槽还没置 RUNNING)的任务登记暂停标记
            pauseRequested.add(fanCode)
        }
        updateTask(fanCode) {
            if (it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED) {
                it.copy(status = DownloadStatus.PAUSED)
            } else it
        }
    }

    /** 恢复:PAUSED → QUEUED(有空槽则立即开跑) */
    fun resume(fanCode: String) {
        pauseRequested.remove(fanCode)
        updateTask(fanCode) {
            if (it.status == DownloadStatus.PAUSED) it.copy(status = DownloadStatus.QUEUED) else it
        }
        pump()
    }

    /** 失败任务重试:保留分片进度,从断点继续 */
    fun retry(fanCode: String) {
        updateTask(fanCode) {
            if (it.status == DownloadStatus.FAILED) it.copy(status = DownloadStatus.QUEUED, error = null) else it
        }
        pump()
    }

    /** 取消并删除任务(分片进度作废,工作目录一并删除) */
    fun cancel(fanCode: String) {
        runningJobs.remove(fanCode)?.cancel()
        pauseRequested.remove(fanCode)
        _tasks.value = _tasks.value.filterNot { it.fanCode == fanCode }
        // 工作目录可能是几 GB 的 .part,删除放 IO 线程避免卡 UI
        scope.launch { File(downloadsRoot, fanCode).deleteRecursively() }
        pump()
    }

    /** 已完成任务从列表移除(文件已发布,工作目录已清理) */
    fun dismiss(fanCode: String) {
        _tasks.value = _tasks.value.filterNot { it.fanCode == fanCode }
    }

    fun close() {
        scope.cancel()
    }

    // ---- 内部实现 ----

    /**
     * 并发泵:槽位不满时按入队顺序启动 QUEUED 任务,同时确保前台服务在运行。
     * 抢槽与启动放同一把锁内串行,避免两个调用方同时启动同一任务。
     */
    private fun pump() {
        scope.launch { pumpLocked() }
    }

    private suspend fun pumpLocked() {
        pumpMutex.withLock {
            var free = MAX_CONCURRENT - runningJobs.size
            if (free <= 0) return@withLock
            runCatching { DownloadService.start(context) }
            for (task in _tasks.value) {
                if (free <= 0) break
                if (task.status != DownloadStatus.QUEUED) continue
                if (runningJobs.containsKey(task.fanCode)) continue
                runningJobs[task.fanCode] = scope.launch { runTask(task.fanCode) }
                free--
            }
        }
    }

    private suspend fun runTask(fanCode: String) {
        try {
            // 抢到槽后立刻被暂停(入队即暂停的竞态),不进入解析/下载
            if (pauseRequested.contains(fanCode)) {
                pauseRequested.remove(fanCode)
                updateTask(fanCode) {
                    if (it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED) {
                        it.copy(status = DownloadStatus.PAUSED)
                    } else it
                }
                persist(fanCode)
                return
            }
            updateTask(fanCode) { it.copy(status = DownloadStatus.RUNNING) }
            var t = current(fanCode) ?: return

            val dir = t.toDir(downloadsRoot)
            // 1. 首次运行:解析分片名列表(m3u8)
            if (t.segmentNames.isEmpty()) {
                val segments = resolveSegmentNames(t.fanCode)
                if (segments.isEmpty()) throw IllegalStateException("未找到 playlist.m3u8 或无分片")
                t = t.copy(segmentNames = segments)
                t.save(dir)
                updateTask(fanCode) { it.copy(segmentNames = segments) }
            }

            // 2. 分片名 → fsid 映射(一次目录列表;重试时按需刷新)
            var fsids = resolveFsids(t.fanCode)

            val dataFile = File(dir, DownloadTask.DATA_FILE)
            var totalBytes = t.totalBytes
            while (true) {
                if (pauseRequested.contains(fanCode)) {
                    pauseRequested.remove(fanCode)
                    updateTask(fanCode) {
                        if (it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED) {
                            it.copy(status = DownloadStatus.PAUSED)
                        } else it
                    }
                    persist(fanCode)
                    Log.i(TAG, "任务暂停 [$fanCode] (${t.completedCount}/${t.totalSegments})")
                    return
                }
                // "暂停瞬间又恢复"的竞态:恢复时标记已移除,这里把状态扶正为 RUNNING 继续
                updateTask(fanCode) {
                    if (it.status == DownloadStatus.QUEUED) it.copy(status = DownloadStatus.RUNNING) else it
                }
                val idx = t.nextSegmentIndex
                if (idx < 0) break
                val segName = t.segmentNames[idx]

                val bytes = downloadSegmentWithRetry(fsids[segName], t.fanCode, segName) { refresh ->
                    if (refresh) fsids = resolveFsids(t.fanCode)
                    fsids[segName]
                }

                // 顺序追加写(续传时定位到文件末尾)
                RandomAccessFile(dataFile, "rw").use { raf ->
                    raf.seek(raf.length())
                    raf.write(bytes)
                }
                totalBytes += bytes.size
                t = t.copy(completedSegments = t.completedSegments + idx, totalBytes = totalBytes)
                t.save(dir)
                updateTask(fanCode) { it.copy(completedSegments = t.completedSegments, totalBytes = totalBytes) }
            }

            // 3. 全部完成 → IS_PENDING 原子发布到 Movies/HlsPan/
            DownloadedStore.publish(context, fanCode, dataFile)
            dir.deleteRecursively()
            updateTask(fanCode) { it.copy(status = DownloadStatus.COMPLETED) }
            Log.i(TAG, "下载完成并发布: $fanCode (${t.totalSegments} 片, $totalBytes 字节)")
        } catch (e: CancellationException) {
            // cancel():任务已从列表移除、目录已删,无需 persist
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "任务失败 [$fanCode]: $e")
            updateTask(fanCode) {
                it.copy(status = DownloadStatus.FAILED, error = e.message ?: e::class.java.simpleName)
            }
            persist(fanCode)
        } finally {
            runningJobs.remove(fanCode)
            pauseRequested.remove(fanCode)
            // 空出的槽位交给泵补位
            pump()
        }
    }

    /** 拉取 m3u8 解析顺序分片名列表(非 # 开头、非空行) */
    private suspend fun resolveSegmentNames(fanCode: String): List<String> {
        val dirPath = "${HlsPaths.BAIDU_MEDIA_ROOT}/${fanCode.trim('/')}"
        val files = baidu.getFileListAll(dirPath)
        val playlist = files.firstOrNull { it.serverFilename == HlsPaths.PLAYLIST_NAME }
            ?: return emptyList()
        val content = baidu.downloadBytes(playlist.fsId).toString(Charsets.UTF_8)
        return content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
    }

    /** 目录文件名 → fsid 映射(一次全量列表) */
    private suspend fun resolveFsids(fanCode: String): Map<String, Long> {
        val dirPath = "${HlsPaths.BAIDU_MEDIA_ROOT}/${fanCode.trim('/')}"
        return baidu.getFileListAll(dirPath)
            .filter { it.fsId != 0L }
            .associate { it.serverFilename to it.fsId }
    }

    /**
     * 分片下载 + 重试:失败后按 1s/2s/4s 退避重试,共 3 次机会;
     * 每次重试前刷新一次 fsid 映射(dlink/fsid 可能过期)。
     */
    private suspend fun downloadSegmentWithRetry(
        initialFsid: Long?,
        fanCode: String,
        segName: String,
        refreshFsid: suspend (Boolean) -> Long?,
    ): ByteArray {
        var lastError: Exception? = null
        var fsid = initialFsid
        repeat(RETRY_COUNT) { attempt ->
            try {
                if (attempt > 0) fsid = refreshFsid(true)
                return downloadSegment(fsid, segName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "分片下载失败(第${attempt + 1}次) [$segName]: $e")
                if (attempt < RETRY_COUNT - 1) delay(RETRY_DELAYS_MS[attempt])
            }
        }
        throw lastError ?: IllegalStateException("分片下载失败: $segName")
    }

    /** 下载单个分片(整片读入内存后返回);读空闲超时 [STREAM_IDLE_TIMEOUT_MS] */
    private suspend fun downloadSegment(fsid: Long?, segName: String): ByteArray {
        if (fsid == null) throw IllegalStateException("分片不存在: $segName")
        return baidu.openDownloadStream(fsid) { channel ->
            val acc = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = withTimeoutOrNull(STREAM_IDLE_TIMEOUT_MS) {
                    channel.readAvailable(buffer, 0, buffer.size)
                } ?: -1
                if (n <= 0) break
                acc.write(buffer, 0, n)
            }
            if (acc.size() == 0) throw IllegalStateException("分片内容为空: $segName")
            acc.toByteArray()
        }
    }

    /** 启动时扫描 downloads/ 目录恢复未完成任务(一律置 PAUSED 等用户恢复) */
    private fun restoreFromDisk() {
        val restored = mutableListOf<DownloadTask>()
        runCatching {
            downloadsRoot.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
                val taskFile = File(dir, DownloadTask.TASK_FILE)
                if (!taskFile.exists()) {
                    dir.deleteRecursively()
                    return@forEach
                }
                runCatching { DownloadTask.fromDir(dir) }.getOrNull()?.let { task ->
                    when (task.status) {
                        DownloadStatus.COMPLETED -> dir.deleteRecursively()
                        else -> restored.add(task.copy(status = DownloadStatus.PAUSED))
                    }
                }
            }
        }
        _tasks.value = restored
        if (restored.isNotEmpty()) {
            Log.i(TAG, "恢复下载任务 ${restored.size} 个: " +
                restored.joinToString { "${it.fanCode}(${it.completedCount}/${it.totalSegments})" })
        }
    }

    private fun current(fanCode: String): DownloadTask? =
        _tasks.value.firstOrNull { it.fanCode == fanCode }

    private fun updateTask(fanCode: String, transform: (DownloadTask) -> DownloadTask) {
        _tasks.value = _tasks.value.map { if (it.fanCode == fanCode) transform(it) else it }
    }

    /** 把内存状态落盘(暂停/失败时) */
    private fun persist(fanCode: String) {
        runCatching {
            val t = current(fanCode) ?: return
            val dir = t.toDir(downloadsRoot)
            if (dir.exists()) t.save(dir)
        }
    }

    companion object {
        private const val TAG = "DownloadManager"
        private const val STREAM_IDLE_TIMEOUT_MS = 60_000L
        const val RETRY_COUNT = 3
        val RETRY_DELAYS_MS = longArrayOf(1_000, 2_000, 4_000)

        /** 最大并发下载数:同时最多下载几部影片,超出的排队 */
        const val MAX_CONCURRENT = 3
    }
}
