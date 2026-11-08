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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import xyz.asitanokibou.player.baidu.BaiduClient
import xyz.asitanokibou.player.core.HlsPaths
import xyz.asitanokibou.player.core.NetworkGate
import xyz.asitanokibou.player.service.DownloadService
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

/** 分片在网盘目录列表中不存在:不重试,任务转 [DownloadStatus.MISSING_SEGMENT] 等用户决定 */
private class SegmentMissingException(segName: String) :
    IllegalStateException("分片不存在: $segName")

/**
 * 下载调度器(单例,AppContainer 持有):
 * - 并发队列:最多 [MAX_CONCURRENT](=3) 个任务同时下载,其余 QUEUED 排队([pump] 空出槽位即补位)
 * - 分片顺序下载,失败自动重试 3 次(1s/2s/4s 退避)后转 FAILED;
 *   分片在网盘上不存在时转 MISSING_SEGMENT,由用户选择「跳过分片」或「重试」
 * - 分片粒度续传:每片完成即追加 .part + 原子写回 task.json
 * - 全部完成后经 [DownloadedStore] 以 IS_PENDING 原子发布,清理工作目录
 *
 * UI 只读 [tasks](StateFlow)即得全部状态;操作入口
 * [enqueue]/[pause]/[resume]/[retry]/[skipMissingSegment]/[cancel]。
 * 速度由 UI 层对 totalBytes 差分采样得到(每秒一次),本类不维护。
 */
class DownloadManager(
    private val context: Context,
    private val baidu: BaiduClient,
    private val networkGate: NetworkGate,
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
        networkGate.observeMeteredTransitions(scope) { metered ->
            if (metered) pauseForMeteredGate() else resumeAfterMeteredGate()
        }
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
            // 闸门兜底:计费网络且未放行 → 不入队(UI 层已经问过「本次继续」)
            if (!networkGate.canUseMeteredDownload(code)) {
                networkGate.revokeDownload(code)
                return@withLock METERED_REJECTED
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
        // 用户手动暂停:闸门标记作废,回 WiFi 不该自动续上
        val status = current(fanCode)?.status
        if (status == DownloadStatus.RUNNING || status == DownloadStatus.QUEUED) {
            // 正在跑(含刚被 pump 抢到槽还没置 RUNNING)的任务登记暂停标记
            pauseRequested.add(fanCode)
        }
        updateTask(fanCode) {
            if (it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED) {
                it.copy(status = DownloadStatus.PAUSED, pausedByGate = false)
            } else it.copy(pausedByGate = false)
        }
    }

    /** 恢复:PAUSED → QUEUED(有空槽则立即开跑);计费网络且未放行时不动,返回拒绝原因 */
    fun resume(fanCode: String): String? {
        if (!networkGate.canUseMeteredDownload(fanCode)) {
            // 放行令牌是「本次继续」刚发下来的,恢复被拒说明这次授权没用了
            networkGate.revokeDownload(fanCode)
            return METERED_REJECTED
        }
        pauseRequested.remove(fanCode)
        updateTask(fanCode) {
            if (it.status == DownloadStatus.PAUSED) {
                it.copy(status = DownloadStatus.QUEUED, pausedByGate = false)
            } else it.copy(pausedByGate = false)
        }
        pump()
        return null
    }

    /** 失败任务重试,或缺失分片再试一次:保留分片进度,从断点继续 */
    fun retry(fanCode: String) {
        updateTask(fanCode) {
            if (it.status == DownloadStatus.FAILED || it.status == DownloadStatus.MISSING_SEGMENT) {
                it.copy(status = DownloadStatus.QUEUED, error = null, pausedByGate = false)
            } else it
        }
        pump()
    }

    /**
     * 跳过缺失分片:把当前卡住的那一片记入 [DownloadTask.skippedSegments] 后重新排队,
     * 从下一片继续下载。缺失的那一片只是让播放画面不连续,不影响整体播放。
     * 只在 [DownloadStatus.MISSING_SEGMENT] 下生效(缺失分片 = 第一个未处理分片)。
     */
    fun skipMissingSegment(fanCode: String) {
        val task = current(fanCode) ?: return
        if (task.status != DownloadStatus.MISSING_SEGMENT) return
        val idx = task.nextSegmentIndex
        if (idx < 0) return
        updateTask(fanCode) {
            it.copy(
                skippedSegments = it.skippedSegments + idx,
                status = DownloadStatus.QUEUED,
                error = null,
                pausedByGate = false,
            )
        }
        persist(fanCode)
        Log.i(TAG, "跳过分片 [$fanCode] #$idx ${task.segmentNames.getOrNull(idx)}")
        pump()
    }

    /** 取消并删除任务(分片进度作废,工作目录一并删除) */
    fun cancel(fanCode: String) {
        runningJobs.remove(fanCode)?.cancel()
        pauseRequested.remove(fanCode)
        // 任务没了,「本次继续」的放行也一并作废
        networkGate.revokeDownload(fanCode)
        _tasks.value = _tasks.value.filterNot { it.fanCode == fanCode }
        // 工作目录可能是几 GB 的 .part,删除放 IO 线程避免卡 UI
        scope.launch { File(downloadsRoot, fanCode).deleteRecursively() }
        pump()
    }

    /** 已完成任务从列表移除(文件已发布,工作目录已清理) */
    fun dismiss(fanCode: String) {
        networkGate.revokeDownload(fanCode)
        _tasks.value = _tasks.value.filterNot { it.fanCode == fanCode }
    }

    fun close() {
        scope.cancel()
    }

    // ---- 内部实现 ----

    /**
     * 切到计费网络:未放行的任务全部转 PAUSED 并打上「闸门暂停」标记。
     * 已经放行(设置开关或本次继续)的任务不打断。
     */
    private fun pauseForMeteredGate() {
        for (task in _tasks.value) {
            if (task.status != DownloadStatus.RUNNING && task.status != DownloadStatus.QUEUED) continue
            if (networkGate.canUseMeteredDownload(task.fanCode)) continue
            // 状态立即翻 PAUSED(UI 马上能看到),pauseRequested 让运行中的任务在当前分片下完就停
            pauseRequested.add(task.fanCode)
            updateTask(task.fanCode) { it.copy(status = DownloadStatus.PAUSED, pausedByGate = true) }
            persist(task.fanCode)
            Log.i(TAG, "移动数据下暂停任务 [${task.fanCode}]")
        }
    }

    /** 回到非计费网络:只续上被闸门暂停的任务,用户手动暂停的保持 PAUSED */
    private fun resumeAfterMeteredGate() {
        val codes = _tasks.value.filter { it.status == DownloadStatus.PAUSED && it.pausedByGate }
            .map { it.fanCode }
        if (codes.isEmpty()) return
        for (code in codes) {
            pauseRequested.remove(code)
            updateTask(code) {
                // runTask 若还在跑,会把 QUEUED 自愈回 RUNNING,不必在这里猜时序
                it.copy(status = DownloadStatus.QUEUED, pausedByGate = false)
            }
        }
        Log.i(TAG, "回到非计费网络, 恢复 ${codes.size} 个任务")
        pump()
    }

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
                    Log.i(TAG, "任务暂停 [$fanCode] (${t.resolvedCount}/${t.totalSegments})")
                    return
                }
                // "暂停瞬间又恢复"的竞态:恢复时标记已移除,这里把状态扶正为 RUNNING 继续
                updateTask(fanCode) {
                    if (it.status == DownloadStatus.QUEUED) it.copy(status = DownloadStatus.RUNNING) else it
                }
                val idx = t.nextSegmentIndex
                if (idx < 0) break
                val segName = t.segmentNames[idx]

                val bytes = try {
                    downloadSegmentWithRetry(fsids[segName], segName) {
                        fsids = resolveFsids(fanCode)
                        fsids[segName]
                    }
                } catch (e: SegmentMissingException) {
                    // 缺片不影响整体播放(只是画面不连续):停下等用户在下载管理页决定
                    // 「跳过分片」或「重试」。任务退出 runTask 后槽位让给其他任务。
                    val done = t.resolvedCount
                    updateTask(fanCode) {
                        it.copy(
                            status = DownloadStatus.MISSING_SEGMENT,
                            error = "分片不存在: $segName",
                        )
                    }
                    persist(fanCode)
                    Log.i(TAG, "分片缺失,等待用户决定 [$fanCode] $segName ($done/${t.totalSegments})")
                    return
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
            // 任务完成,「本次继续」的放行作废
            networkGate.revokeDownload(fanCode)
            Log.i(TAG, "下载完成并发布: $fanCode (${t.totalSegments} 片/${t.skippedCount} 跳, $totalBytes 字节)")
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
     * 分片在目录列表里不存在时抛 [SegmentMissingException] 且不重试 —— 由调用方转
     * [DownloadStatus.MISSING_SEGMENT] 等用户决定是否跳过。
     */
    private suspend fun downloadSegmentWithRetry(
        initialFsid: Long?,
        segName: String,
        refreshFsid: suspend () -> Long?,
    ): ByteArray {
        // 先刷新一次映射:排除"目录列表拉取时那张分片还没出现"的假缺失
        var fsid = initialFsid ?: refreshFsid()
        if (fsid == null) throw SegmentMissingException(segName)
        var lastError: Exception? = null
        repeat(RETRY_COUNT) { attempt ->
            try {
                return downloadSegment(fsid, segName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SegmentMissingException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "分片下载失败(第${attempt + 1}次) [$segName]: $e")
                if (attempt < RETRY_COUNT - 1) {
                    delay(RETRY_DELAYS_MS[attempt])
                    fsid = refreshFsid() ?: throw SegmentMissingException(segName)
                }
            }
        }
        throw lastError ?: IllegalStateException("分片下载失败: $segName")
    }

    /** 下载单个分片(整片读入内存后返回);读空闲超时 [STREAM_IDLE_TIMEOUT_MS] */
    private suspend fun downloadSegment(fsid: Long?, segName: String): ByteArray {
        if (fsid == null) throw SegmentMissingException(segName)
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
                        // 重启后无从得知暂停是不是闸门造成的,按用户手动暂停处理
                        else -> restored.add(task.copy(status = DownloadStatus.PAUSED, pausedByGate = false))
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
        // CAS 循环而不是读-改-写:闸门跟随与 runTask 会在不同线程同时改同一份列表
        _tasks.update { list -> list.map { if (it.fanCode == fanCode) transform(it) else it } }
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

        /** 计费网络且未放行时的拒绝原因(与 enqueue 的 String? 契约一致) */
        const val METERED_REJECTED = "当前为移动数据,未开始下载"

        const val RETRY_COUNT = 3
        val RETRY_DELAYS_MS = longArrayOf(1_000, 2_000, 4_000)

        /** 最大并发下载数:同时最多下载几部影片,超出的排队 */
        const val MAX_CONCURRENT = 3
    }
}
