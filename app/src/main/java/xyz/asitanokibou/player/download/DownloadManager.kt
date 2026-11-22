package xyz.asitanokibou.player.download

import android.content.Context
import android.os.SystemClock
import android.util.Log
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import xyz.asitanokibou.player.baidu.BaiduYunClient
import xyz.asitanokibou.player.baidu.StreamProbe
import xyz.asitanokibou.player.config.AppSettings
import xyz.asitanokibou.player.core.HlsPaths
import xyz.asitanokibou.player.core.NetworkGate
import xyz.asitanokibou.player.service.DownloadService
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.RandomAccessFile
import java.net.ProtocolException
import java.net.SocketException
import java.nio.channels.ClosedChannelException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** 分片在网盘目录列表中不存在:不重试,任务转 [DownloadStatus.MISSING_SEGMENT] 等用户决定 */
private class SegmentMissingException(segName: String) :
    IllegalStateException("分片不存在: $segName")

/**
 * 读到的字节数少于服务端声明的 Content-Length(连接被对端关掉、或读空闲超时)。
 * 当作「连接级失败」重试 —— 绝不能把短一截的分片写进 .part。
 */
private class TruncatedBodyException(message: String) : IllegalStateException(message)

/** 单片耗时分解(毫秒):换直链 / 等响应头 / 等首字节 / 整片。定义在 SegmentFetcher.kt */

/**
 * 一个任务的耗时累加器:回答「下载慢在哪一段」。
 * 每片累加 [SegmentTiming],并按 [LOG_EVERY_SEGMENTS] 片打一行汇总。
 */
private class TaskTelemetry(private val totalSegments: Int) {
    private val startedAt = SystemClock.elapsedRealtime()
    private var lastLogAt = startedAt
    private var segments = 0
    private var bytes = 0L
    private var dlinkMs = 0L
    private var headersMs = 0L
    private var firstByteMs = 0L
    private var totalMs = 0L

    /** 按大小分档(百度对 ≥2MiB 的对象限速,两档速度差一个数量级,不能混着平均) */
    private var smallCount = 0
    private var smallBytes = 0L
    private var smallMs = 0L
    private var bigCount = 0
    private var bigBytes = 0L
    private var bigMs = 0L

    var retries = 0
        private set

    /** 其中属于「连接级」的重试次数(CDN 主动断流/拒绝),与直链无关 */
    var transientRetries = 0
        private set

    /** 自动跳过的缺失分片数(不计入耗时/吞吐统计) */
    var skipped = 0
        private set

    fun onSkipped() {
        skipped++
    }

    fun add(t: SegmentTiming) {
        segments++
        bytes += t.bytes
        dlinkMs += t.dlinkMs
        headersMs += t.headersMs
        firstByteMs += t.firstByteMs
        totalMs += t.totalMs
        retries += (t.attempts - 1).coerceAtLeast(0)
        transientRetries += t.transientRetries
        if (t.bytes < LanePlan.BIG_OBJECT_THRESHOLD) {
            smallCount++
            smallBytes += t.bytes
            smallMs += t.totalMs
        } else {
            bigCount++
            bigBytes += t.bytes
            bigMs += t.totalMs
        }
    }

    /** 到点就打汇总(每 N 片,且至少隔 [LOG_MIN_INTERVAL_MS] 毫秒,避免小分片刷屏) */
    fun shouldLog(): Boolean {
        if (segments == 0) return false
        val now = SystemClock.elapsedRealtime()
        return segments % LOG_EVERY_SEGMENTS == 0 && now - lastLogAt >= LOG_MIN_INTERVAL_MS
    }

    fun log(fanCode: String) {
        if (segments == 0) return
        lastLogAt = SystemClock.elapsedRealtime()
        val wallMs = (lastLogAt - startedAt).coerceAtLeast(1)
        val n = segments.coerceAtLeast(1)
        val line = "耗时统计 [%s] %d/%d 片, %.1f MB, 墙钟均速 %d KB/s | "
            .format(fanCode, n, totalSegments, bytes / 1048576.0, bytes / 1024 * 1000 / wallMs) +
            "均:换直链 %d ms / 等响应头 %d ms / 等首字节 %d ms / 单片 %d ms, 重试 %d 次(连接级 %d)".format(
                dlinkMs / n, headersMs / n, firstByteMs / n, totalMs / n, retries, transientRetries,
            ) +
            " | 小 %d 片 %.1f MB 单片均速 %d KB/s, 大 %d 片 %.1f MB 单片均速 %d KB/s".format(
                smallCount, smallBytes / 1048576.0, rate(smallBytes, smallMs),
                bigCount, bigBytes / 1048576.0, rate(bigBytes, bigMs),
            ) +
            (if (skipped > 0) " | 已跳过 %d 片(网盘缺失)".format(skipped) else "")
        Log.i(TAG, line)
    }

    private fun rate(bytes: Long, ms: Long): Long = if (ms <= 0) 0 else bytes / 1024 * 1000 / ms

    companion object {
        /** 沿用 DownloadManager 的 tag,便于统一 `logcat -s DownloadManager` */
        private const val TAG = "DownloadManager"

        /** 每 N 片打一次汇总 */
        const val LOG_EVERY_SEGMENTS = 10

        /** 两次汇总至少间隔这么长时间(小分片任务不至于刷屏) */
        const val LOG_MIN_INTERVAL_MS = 10_000L
    }
}

/**
 * 下载调度器(单例,AppContainer 持有):
 * - 并发队列:最多 [MAX_CONCURRENT](=3) 个任务同时下载,其余 QUEUED 排队([pump] 空出槽位即补位)
 * - 任务内并行分片 + 双车道(见 [SegmentFetcher]):小片多车道、大片单车道;
 *   落盘仍按索引顺序追加,失败重试 3 次(1s/2s/4s 退避)后转 FAILED,
 *   分片在网盘上不存在时转 MISSING_SEGMENT,由用户选择「跳过分片」或「重试」
 * - 直链批量预取([PREFETCH_AHEAD] 窗口),不再每片付一次 filemetas 的 RTT
 * - 分片粒度续传:每片落盘即追加 .part + 原子写回 task.json
 * - 全部完成后经 [DownloadedStore] 以 IS_PENDING 原子发布,清理工作目录
 *
 * UI 只读 [tasks](StateFlow)即得全部状态;操作入口
 * [enqueue]/[pause]/[resume]/[retry]/[skipMissingSegment]/[cancel]。
 * 速度由 UI 层对 totalBytes 差分采样得到(每秒一次),本类不维护。
 *
 * 缺片策略由设置项 [AppSettings] 的「跳过所有缺失分片」实时控制:
 * - 关(默认):遇到缺失片停下,任务转 [DownloadStatus.MISSING_SEGMENT] 等用户选择;
 * - 开:缺失片记入 [DownloadTask.skippedSegments] 后直接继续,MISSING_SEGMENT 不可达。
 */
class DownloadManager(
    private val context: Context,
    private val baidu: BaiduYunClient,
    private val networkGate: NetworkGate,
    private val settings: AppSettings,
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

    /**
     * 「跳过所有缺失分片」开关的实时快照。
     * 每到一个分片边界都会读一次,所以改设置对**正在跑**的任务也立即生效;
     * 不需要为此重启下载服务,也不该按任务启动时快照 —— 用户常常是卡住了才去改。
     */
    @Volatile
    private var skipAllMissing = false

    init {
        restoreFromDisk()
        networkGate.observeMeteredTransitions(scope) { metered ->
            if (metered) pauseForMeteredGate() else resumeAfterMeteredGate()
        }
        observeSkipAllMissingSetting()
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
     * 跟踪「跳过所有缺失分片」开关,并只在**上升沿**(关→开)推进已卡住的任务。
     *
     * 只在上升沿:开关本来就开着时反复保存设置不应该把队列推一遍(那是在替用户做决定)。
     * 开→关不必处理:MISSING_SEGMENT 只是「不再自动跳」,已经跳过的分片照旧算数。
     */
    private fun observeSkipAllMissingSetting() {
        scope.launch {
            var wasEnabled = false
            settings.configFlow
                .map { it.skipAllMissingSegments }
                .distinctUntilChanged()
                .collect { enabled ->
                    skipAllMissing = enabled
                    if (enabled && !wasEnabled) resumeMissingSegmentTasks()
                    wasEnabled = enabled
                }
        }
    }

    /**
     * 开关打开时,把停在「缺片待处理」的任务推回队列 —— 开关既然开着,
     * 任务就不该再等人点按钮,否则会留下「设置已生效但任务没反应」的自相矛盾状态。
     *
     * 必须走 QUEUED + pump(),不能直接启动任务,否则会绕过 [MAX_CONCURRENT] 槽位约束;
     * 计费网络下未放行的转 PAUSED + pausedByGate,交给 [resumeAfterMeteredGate] 回 WiFi 自动续。
     */
    private fun resumeMissingSegmentTasks() {
        val codes = _tasks.value.filter { it.status == DownloadStatus.MISSING_SEGMENT }.map { it.fanCode }
        if (codes.isEmpty()) return
        for (code in codes) {
            // 缺片任务退出 runTask 时标记已被消费;这里再清一次,避免任何残留把新起的任务立刻停掉
            pauseRequested.remove(code)
            if (networkGate.canUseMeteredDownload(code)) {
                updateTask(code) {
                    it.copy(status = DownloadStatus.QUEUED, error = null, pausedByGate = false)
                }
            } else {
                // 移动数据且未放行:记成「闸门暂停」而不是停在缺片状态,回 WiFi 后自动接着跳过
                updateTask(code) { it.copy(status = DownloadStatus.PAUSED, pausedByGate = true) }
            }
            persist(code)
        }
        Log.i(TAG, "开启「跳过所有缺失分片」, ${codes.size} 个缺片任务自动继续")
        pump()
    }

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

    /** 串行化「缺片补刷目录列表」:多车道可能同时碰到同一批缺失 */
    private val remoteRefresh = Mutex()

    /**
     * 全局分片连接预算:车道数 × 任务数 最多能开 21 条连接,但实测 10 条以上就只增加
     * 「等响应头」的排队(0.7s → 1.5s)与 CDN 主动断流的概率,吞吐不再涨。
     */
    private val segmentConnections = Semaphore(MAX_SEGMENT_CONNECTIONS)

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

            when (val end = downloadAll(fanCode, dir)) {
                is TaskEnd.Completed -> {
                    val cur = current(fanCode)
                    if (cur == null || cur.completedCount == 0) {
                        // 自动跳过跑到底、但一片都没下到:此时 .part 根本没被创建,
                        // 进发布流程只会抛一句莫名其妙的「文件不存在」。得直说是片源问题。
                        val total = cur?.totalSegments ?: 0
                        val skipped = cur?.skippedCount ?: 0
                        updateTask(fanCode) {
                            it.copy(
                                status = DownloadStatus.FAILED,
                                error = "全部 $total 片都缺失($skipped 片已跳过),未生成文件",
                            )
                        }
                        persist(fanCode)
                        Log.e(TAG, "全部分片缺失,未发布 [$fanCode] 共 $total 片")
                        return
                    }
                    DownloadedStore.publish(context, fanCode, File(dir, DownloadTask.DATA_FILE))
                    // 缺片是这次下载的过程事实,task.json 发布后就要被删了 —— 先落侧车再清目录
                    DownloadedMetaStore.record(
                        context = context,
                        fanCode = fanCode,
                        skippedIndices = cur.skippedSegments,
                        totalSegments = cur.totalSegments,
                    )
                    dir.deleteRecursively()
                    updateTask(fanCode) { it.copy(status = DownloadStatus.COMPLETED) }
                    // 任务完成,「本次继续」的放行作废
                    networkGate.revokeDownload(fanCode)
                    Log.i(
                        TAG,
                        "下载完成并发布: $fanCode (${end.segments} 片, ${end.bytes} 字节" +
                            (if (cur.skippedCount > 0) ", 跳过 ${cur.skippedCount} 片" else "") + ")",
                    )
                }
                is TaskEnd.Paused -> {
                    updateTask(fanCode) {
                        if (it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED) {
                            it.copy(status = DownloadStatus.PAUSED)
                        } else it
                    }
                    persist(fanCode)
                    Log.i(TAG, "任务暂停 [$fanCode] (${end.resolved}/${end.total})")
                }
                is TaskEnd.Missing -> {
                    if (skipAllMissing) {
                        // 与「开关刚打开」的竞态:这一片被判缺失时开关还是关的,现在已经开了。
                        // 直接推回队列,下一轮就会自动跳过它 —— 不让任务停在一个需要人点按钮的状态。
                        updateTask(fanCode) { it.copy(status = DownloadStatus.QUEUED, error = null) }
                        persist(fanCode)
                        Log.i(TAG, "分片缺失但已开启自动跳过,重新排队 [$fanCode] ${end.segName}")
                        return
                    }
                    // 缺片不影响整体播放(只是画面不连续):停下等用户在下载管理页决定
                    // 「跳过分片」或「重试」。任务退出 runTask 后槽位让给其他任务。
                    // 开关开着时这里不会发生 —— 缺失在 downloadAll 里已被直接跳过。
                    updateTask(fanCode) {
                        it.copy(
                            status = DownloadStatus.MISSING_SEGMENT,
                            error = "分片不存在: ${end.segName}",
                        )
                    }
                    persist(fanCode)
                    Log.i(TAG, "分片缺失,等待用户决定 [$fanCode] ${end.segName} (${end.resolved}/${end.total})")
                }
                is TaskEnd.Failed -> {
                    Log.e(TAG, "任务失败 [$fanCode]: ${end.error}")
                    updateTask(fanCode) {
                        it.copy(
                            status = DownloadStatus.FAILED,
                            error = end.error.message ?: end.error::class.java.simpleName,
                        )
                    }
                    persist(fanCode)
                }
            }
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

    /** 任务的结束方式:状态落盘由 [runTask] 统一负责 */
    private sealed interface TaskEnd {
        data class Completed(val segments: Int, val bytes: Long) : TaskEnd
        data class Paused(val resolved: Int, val total: Int) : TaskEnd
        data class Missing(val segName: String, val resolved: Int, val total: Int) : TaskEnd
        data class Failed(val error: Exception) : TaskEnd
    }

    /**
     * 分片并行下载 + 顺序落盘。
     *
     * 形状:车道并行「认领 → 下载 → 交结果」([SegmentFetcher]),本方法按索引顺序把结果
     * 追加进 .part —— 顺序不变才能保证分片粒度续传的语义。
     */
    private suspend fun downloadAll(fanCode: String, dir: File): TaskEnd = coroutineScope {
        val task = current(fanCode) ?: return@coroutineScope TaskEnd.Failed(
            IllegalStateException("任务已消失"),
        )
        val dataFile = File(dir, DownloadTask.DATA_FILE)
        val telemetry = TaskTelemetry(task.totalSegments)
        var totalBytes = task.totalBytes

        // 目录列表:分片名 → fsid + 大小。整个任务只拉一次;真缺片时才补刷
        val remotes = AtomicReference(resolveRemotes(fanCode))
        val done = (task.completedSegments + task.skippedSegments).toHashSet()
        val pending = task.segmentNames.indices
            .filter { it !in done }
            .map { idx ->
                val name = task.segmentNames[idx]
                val remote = remotes.get()[name]
                PlannedSegment(idx, remote?.fsid, name, remote?.size ?: 0L)
            }
        if (pending.isEmpty()) {
            return@coroutineScope TaskEnd.Completed(task.totalSegments, totalBytes)
        }

        val fetcher = SegmentFetcher(
            plan = pending,
            lanePlan = LANES,
            shouldStop = { pauseRequested.contains(fanCode) },
            fetch = { seg -> segmentConnections.withPermit { fetchSegment(fanCode, seg, remotes) } },
        )
        Log.i(
            TAG,
            "开始下载 [$fanCode] 共 ${task.totalSegments} 片(已处理 ${task.resolvedCount}" +
                (if (task.skippedCount > 0) ", 其中跳过 ${task.skippedCount}" else "") + ");" +
                "本次 ${pending.size} 片:小 ${fetcher.smallCount} / 大 ${fetcher.bigCount} " +
                "(阈值 ${LANES.bigThresholdBytes / 1024 / 1024}MiB)," +
                "车道 小=${LANES.smallLanes} 大=${LANES.bigLanes}" +
                (if (skipAllMissing) ", 缺失分片自动跳过" else ""),
        )

        // 先给窗口内换好直链再开车道,否则开头几片会退回单片换直链(每次 0.4~0.8s)
        var prefetchPos = prefetchAhead(pending, 0, pending.first().index)
        val lanes = fetcher.launch(this)
        try {
            while (true) {
                val cur = current(fanCode) ?: break
                val idx = cur.nextSegmentIndex
                if (idx < 0) break

                // 直链预取:窗口内还没换过的批量换一次,把每片的 filemetas RTT 摊掉
                if (!pauseRequested.contains(fanCode)) {
                    prefetchPos = prefetchAhead(pending, prefetchPos, idx)
                }

                // "暂停瞬间又恢复"的竞态:恢复时标记已移除,这里把状态扶正为 RUNNING 继续
                updateTask(fanCode) {
                    if (it.status == DownloadStatus.QUEUED) it.copy(status = DownloadStatus.RUNNING) else it
                }

                // 暂停后不再认领新分片,但已认领的都排空(不白丢已下载的字节)
                val outcome = fetcher.await(idx) ?: break
                when (outcome) {
                    is SegmentOutcome.Done -> {
                        val bytes = outcome.bytes
                        val timing = outcome.timing
                        // 顺序追加写(续传时定位到文件末尾)
                        RandomAccessFile(dataFile, "rw").use { raf ->
                            raf.seek(raf.length())
                            raf.write(bytes)
                        }
                        totalBytes += bytes.size
                        val completed = cur.completedSegments + idx
                        cur.copy(completedSegments = completed, totalBytes = totalBytes).save(dir)
                        updateTask(fanCode) {
                            it.copy(completedSegments = completed, totalBytes = totalBytes)
                        }
                        telemetry.add(timing)
                        Log.i(
                            TAG,
                            "分片 [$fanCode] #$idx ${cur.segmentNames[idx]} ${bytes.size}B " +
                                "换直链=${timing.dlinkMs}ms 响应头=${timing.headersMs}ms " +
                                "首字节=${timing.firstByteMs}ms 单片=${timing.totalMs}ms " +
                                "传输 ${kbps(timing.bytes.toLong(), timing.totalMs - timing.dlinkMs - timing.headersMs)}KB/s " +
                                "有效 ${kbps(timing.bytes.toLong(), timing.totalMs)}KB/s" +
                                (if (timing.attempts > 1) " 第${timing.attempts}次" else "") +
                                if (timing.contentLength >= 0 && timing.contentLength != bytes.size.toLong()) {
                                    " ⚠ 长度不符(Content-Length=${timing.contentLength}, 实收=${bytes.size})"
                                } else "",
                        )
                        if (telemetry.shouldLog()) telemetry.log(fanCode)
                    }
                    is SegmentOutcome.Missing -> {
                        if (!skipAllMissing) {
                            telemetry.log(fanCode)
                            return@coroutineScope TaskEnd.Missing(outcome.name, cur.resolvedCount, cur.totalSegments)
                        }
                        // 开关开着:记住这一片被跳过,然后继续下一片。跳过的片不贡献字节,
                        // .part 的顺序因此不变(顺序是分片粒度续传成立的前提)。
                        val skipped = (cur.skippedSegments + idx).distinct()
                        cur.copy(skippedSegments = skipped, error = null).save(dir)
                        updateTask(fanCode) { it.copy(skippedSegments = skipped, error = null) }
                        telemetry.onSkipped()
                        Log.i(
                            TAG,
                            "自动跳过缺失分片 [$fanCode] #$idx ${outcome.name} " +
                                "(累计 ${skipped.size} 片, ${cur.completedCount + skipped.size}/${cur.totalSegments})",
                        )
                    }
                    is SegmentOutcome.Failed -> {
                        telemetry.log(fanCode)
                        val cause = outcome.cause
                        return@coroutineScope TaskEnd.Failed(
                            if (cause is Exception) cause else IllegalStateException(cause),
                        )
                    }
                }
            }
        } finally {
            lanes.cancelAndJoin()
        }

        telemetry.log(fanCode)
        val cur = current(fanCode)
        if (cur != null && cur.nextSegmentIndex >= 0) {
            return@coroutineScope TaskEnd.Paused(cur.resolvedCount, cur.totalSegments)
        }
        TaskEnd.Completed(task.totalSegments, totalBytes)
    }

    /**
     * 把「下一个未处理分片」往前 [PREFETCH_AHEAD] 范围内的直链批量换好。
     * 一次调用会把窗口内新触及的 fsid 收集起来一并请求(实测一次带 100 个 fsid 仍是 ~0.5s RTT)。
     * @return 更新后的窗口游标
     */
    private suspend fun prefetchAhead(
        plan: List<PlannedSegment>,
        cursor: Int,
        index: Int,
    ): Int {
        var pos = cursor
        val limit = index + PREFETCH_AHEAD
        val batch = mutableListOf<Long>()
        while (pos < plan.size && plan[pos].index <= limit) {
            plan[pos].fsid?.let { batch += it }
            pos++
        }
        // 攒够一批才请求;已到计划末尾(剩不到一批)就不再让尾巴回退到单片换直链
        val atEnd = pos >= plan.size
        if (batch.size < PREFETCH_MIN_BATCH && !atEnd) return cursor
        if (batch.isNotEmpty()) {
            runCatching { baidu.prefetchDownloadUrls(batch) }
                .onFailure { Log.w(TAG, "直链预取失败(不影响下载): $it") }
        }
        return pos
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

    /** 分片名 → fsid + 大小(一次全量目录列表) */
    private suspend fun resolveRemotes(fanCode: String): Map<String, RemoteSegment> {
        val dirPath = "${HlsPaths.BAIDU_MEDIA_ROOT}/${fanCode.trim('/')}"
        return baidu.getFileListAll(dirPath)
            .filter { it.fsId != 0L }
            .associate { it.serverFilename to RemoteSegment(it.fsId, it.size) }
    }

    /** 网盘上的一个分片:fsid + 字节数(大小用来分车道) */
    private data class RemoteSegment(val fsid: Long, val size: Long)

    /**
     * 单片下载(含重试)。失败重试前作废直链 —— 过期/被拒的直链换一条就能好,
     * 不必每次都重拉整个目录列表。
     */
    private suspend fun fetchSegment(
        fanCode: String,
        planned: PlannedSegment,
        remotes: AtomicReference<Map<String, RemoteSegment>>,
    ): SegmentOutcome {
        val timing = SegmentTiming()
        val resolved: Long? = if (planned.fsid != null) {
            planned.fsid
        } else if (remotes.get()[planned.name]?.fsid != null) {
            remotes.get()[planned.name]?.fsid
        } else {
            // 目录列表拉取时这张片还没出现 → 补刷一次再判缺失(多车道可能同时碰到,串行化)
            remoteRefresh.withLock {
                remotes.get()[planned.name]?.fsid ?: runCatching { resolveRemotes(fanCode) }
                    .onSuccess { remotes.set(it) }
                    .getOrNull()
                    ?.get(planned.name)
                    ?.fsid
            }
        }
        if (resolved == null) {
            return SegmentOutcome.Missing(planned.name, SegmentMissingException(planned.name))
        }
        val fsid = resolved

        var lastError: Exception? = null
        var attempts = 0
        var transientBudget = MAX_TRANSIENT_RETRIES
        var dlinkBudget = RETRY_COUNT
        while (transientBudget > 0 && dlinkBudget > 0) {
            attempts++
            timing.attempts = attempts
            try {
                return SegmentOutcome.Done(downloadSegment(fsid, planned.name, timing), timing)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SegmentMissingException) {
                return SegmentOutcome.Missing(planned.name, e)
            } catch (e: Exception) {
                lastError = e
                // 连接级失败(Unexpected end of stream / 被重置 / 读到一半断了):
                // 直链是好的,错的是这条连接 —— 换条连接立刻重来,不退避、不作废直链。
                // OkHttp 不会替我们重试这类错(它把 ProtocolException 归为不可恢复),
                // 所以这层重试是唯一的兼底。
                val transient = e.isTransientConnectionError()
                if (transient) {
                    timing.transientRetries++
                    transientBudget--
                } else {
                    dlinkBudget--
                }
                Log.w(
                    TAG,
                    "分片下载失败(第${attempts}次${if (transient) ", 连接级, 剩余$transientBudget" else ", 直链级, 剩余$dlinkBudget"}) " +
                        "[${planned.name}] (换直链=${timing.dlinkMs}ms 响应头=${timing.headersMs}ms " +
                        "首字节=${timing.firstByteMs}ms 已收=${timing.bytes}B): $e",
                )
                if (transient) {
                    if (transientBudget > 0) delay(TRANSIENT_RETRY_DELAY_MS)
                } else {
                    baidu.invalidateDownloadUrl(fsid)
                    if (dlinkBudget > 0) delay(RETRY_DELAYS_MS[(RETRY_COUNT - dlinkBudget - 1).coerceIn(0, RETRY_DELAYS_MS.lastIndex)])
                }
            }
        }
        return SegmentOutcome.Failed(
            planned.name,
            lastError ?: IllegalStateException("分片下载失败: ${planned.name}"),
        )
    }

    /**
     * 这是「连接」的问题,不是「直链」的问题吗?
     *
     * 这些状态都是合法的(CDN 节点会主动断开、会拒绝超额连接),所以在这里换条连接重试是正解;
     * 反之把直链作废只会白白多花一次 `filemetas` 的 RTT。
     */
    private fun Throwable.isTransientConnectionError(): Boolean = when (this) {
        is ProtocolException -> true
        is EOFException -> true
        is SocketException -> true
        is ClosedChannelException -> true
        is TruncatedBodyException -> true
        else -> cause?.let { it.isTransientConnectionError() } == true
    }

    /** 下载单个分片(整片读入内存后返回);读空闲超时 [STREAM_IDLE_TIMEOUT_MS] */
    private suspend fun downloadSegment(fsid: Long, segName: String, timing: SegmentTiming): ByteArray {
        val startedAt = SystemClock.elapsedRealtime()
        timing.bytes = 0
        timing.firstByteMs = -1
        val probe = StreamProbe()
        return baidu.openDownloadStream(fsid, probe) { channel ->
            val acc = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var idleTimedOut = false
            while (true) {
                val n = withTimeoutOrNull(STREAM_IDLE_TIMEOUT_MS) {
                    channel.readAvailable(buffer, 0, buffer.size)
                }
                if (n == null) {
                    idleTimedOut = true
                    break
                }
                if (n <= 0) break
                acc.write(buffer, 0, n)
                if (timing.firstByteMs < 0) {
                    timing.firstByteMs = SystemClock.elapsedRealtime() - startedAt
                }
            }
            if (acc.size() == 0) throw IllegalStateException("分片内容为空: $segName")
            // 服务端给了 Content-Length 就必须一片不差:少一个字节写进 .part 就是坏文件。
            // 断流(连接被对端关掉)与读空闲超时都归到这里 —— 是连接的问题,要重试
            val expected = probe.contentLength
            val truncated = (expected >= 0 && acc.size().toLong() != expected) || idleTimedOut
            if (truncated) {
                timing.bytes = acc.size()
                throw TruncatedBodyException(
                    "$segName 断流: 期望 ${if (expected >= 0) "$expected B" else "未知"}, 实收 ${acc.size()} B" +
                        if (idleTimedOut) "(读空闲超时 ${STREAM_IDLE_TIMEOUT_MS}ms)" else "",
                )
            }
            acc.toByteArray()
        }.also { bytes ->
            timing.dlinkMs = probe.dlinkMs
            timing.headersMs = probe.headersMs
            timing.contentLength = probe.contentLength
            timing.bytes = bytes.size
            timing.totalMs = SystemClock.elapsedRealtime() - startedAt
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

    /** 字节 / 毫秒 → KB/s(毫秒非正时返回 0) */
    private fun kbps(bytes: Long, ms: Long): Long = if (ms <= 0) 0 else bytes * 1000 / 1024 / ms

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

        /** 连接级失败(对端断流)不值得退避:换条连接立刻重来 */
        private const val TRANSIENT_RETRY_DELAY_MS = 50L

        /** 连接级失败额外给几次机会(每次只是一次重连,很便宜) */
        private const val MAX_TRANSIENT_RETRIES = 5

        /** 全局同时进行的分片下载数上限(跨任务),对应约 10 条 CDN 连接 */
        private const val MAX_SEGMENT_CONNECTIONS = 10

        /** 最大并发下载数:同时最多下载几部影片,超出的排队 */
        const val MAX_CONCURRENT = 3

        /**
         * 分片车道:小片多并发、大片单条。
         * 百度对 ≥2MiB 的对象走账号级限速(~100KB/s 聚合),对这些片加并发只会摊薄每一条。
         */
        private val LANES = LanePlan.DEFAULT

        /** 直链预取窗口:下一个未处理分片往前多少片内的直链批量换好 */
        private const val PREFETCH_AHEAD = 240

        /** 攒够这么多条一起请求(一次 filemetas 带 100 个 fsid 仍是 ~0.5s RTT) */
        private const val PREFETCH_MIN_BATCH = 24
    }
}
