package xyz.asitanokibou.player.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** 一个待下载分片的计划项:[index] 是 m3u8 里的顺序号,[size] 来自目录列表(用于分车道) */
internal data class PlannedSegment(val index: Int, val fsid: Long?, val name: String, val size: Long)

/** 单片耗时分解(毫秒):换直链 / 等响应头 / 等首字节 / 整片 */
internal class SegmentTiming {
    var dlinkMs = 0L
    var headersMs = 0L
    var firstByteMs = 0L
    var totalMs = 0L
    var bytes = 0

    /** 这一片试了几次(1 = 一次成功) */
    var attempts = 0

    /** 其中属于连接级失败(对端断流/重置)的重试次数 */
    var transientRetries = 0

    /** 服务端给的 Content-Length;-1 = 未给。与 [bytes] 不等说明这一片被截断了 */
    var contentLength = -1L
}

/** 单片下载的结局 */
internal sealed interface SegmentOutcome {
    /** 下载成功(整片在内存里) */
    data class Done(val bytes: ByteArray, val timing: SegmentTiming) : SegmentOutcome

    /** 网盘目录里没有这一片:不重试,任务转 MISSING_SEGMENT 等用户决定 */
    data class Missing(val name: String, val cause: Throwable) : SegmentOutcome

    /** 重试仍失败:任务转 FAILED */
    data class Failed(val name: String, val cause: Throwable) : SegmentOutcome
}

/**
 * 任务内的**并行分片下载器 + 顺序交付**。
 *
 * 为什么分车道(2026-11 实测,数据见下):百度对网盘对象按大小走两条通道 ——
 * `≤2MiB` 的对象单连接 1.3MB/s 且随并发叠加(10 连接 5.8MB/s 聚合);
 * `>2MiB` 的对象被账号级限到 ~100KB/s,加并发只是把每条摊薄(10 连接仍 ~94KB/s)。
 * 所以小片给多条车道、大片只给 1 条,别让慢片把并发槽位占死。
 *
 * 职责边界:
 * - 本类只负责「认领 → 下载 → 记下结果」,不碰 `DownloadTask`、不落盘
 * - 顺序由调用方保证:[await] 按 index 取结果,调用方从下一个未处理分片起顺序取
 * - 每条车道有独立槽位([LanePlan.ahead]):既限内存,也保证队列头所属车道永远有空位,
 *   不会被另一条车道占满槽位后饿死
 *
 * @param shouldStop 返回 true 时车道不再认领新分片(暂停/取消)
 * @param fetch 单片下载(含重试),由调用方注入
 */
internal class SegmentFetcher(
    plan: List<PlannedSegment>,
    private val lanePlan: LanePlan,
    private val shouldStop: () -> Boolean,
    private val fetch: suspend (PlannedSegment) -> SegmentOutcome,
) {
    private val byIndex: Map<Int, PlannedSegment> = plan.associateBy { it.index }

    private val smallIndices: IntArray =
        plan.filter { it.size < lanePlan.bigThresholdBytes }.map { it.index }.toIntArray()

    private val bigIndices: IntArray =
        plan.filter { it.size >= lanePlan.bigThresholdBytes }.map { it.index }.toIntArray()

    private val smallCursor = AtomicInteger(0)
    private val bigCursor = AtomicInteger(0)

    private val smallSlots = Semaphore(lanePlan.smallLanes + lanePlan.ahead)
    private val bigSlots = Semaphore(lanePlan.bigLanes + lanePlan.ahead)

    private val claimed = ConcurrentHashMap.newKeySet<Int>()
    private val results = ConcurrentHashMap<Int, SegmentOutcome>()
    private val waiters = ConcurrentHashMap<Int, CompletableDeferred<SegmentOutcome>>()

    val smallCount: Int get() = smallIndices.size
    val bigCount: Int get() = bigIndices.size

    /** 启动两条车道;返回的 Job 一旦 cancel,所有车道立即收手(调用方退出时必须 cancel) */
    fun launch(scope: CoroutineScope): Job = scope.launch {
        coroutineScope {
            repeat(lanePlan.smallLanes) { launch { lane(smallCursor, smallIndices, smallSlots) } }
            repeat(lanePlan.bigLanes) { launch { lane(bigCursor, bigIndices, bigSlots) } }
        }
    }

    private suspend fun lane(cursor: AtomicInteger, indices: IntArray, slots: Semaphore) {
        while (true) {
            if (shouldStop()) return
            slots.acquire()
            if (shouldStop()) {
                slots.release()
                return
            }
            val pos = cursor.getAndIncrement()
            if (pos >= indices.size) {
                slots.release()
                return
            }
            val planned = byIndex[indices[pos]] ?: run {
                slots.release()
                return
            }
            claimed.add(planned.index)
            val outcome = try {
                fetch(planned)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                SegmentOutcome.Failed(planned.name, e)
            }
            results[planned.index] = outcome
            waiters[planned.index]?.complete(outcome)
        }
    }

    /**
     * 取第 [index] 片的结果(挂起直到下载完)。
     * 返回 null 表示这一片不会再有人下(已暂停/停止且尚未被认领),调用方该收手了。
     */
    suspend fun await(index: Int): SegmentOutcome? {
        while (true) {
            results.remove(index)?.let { outcome ->
                waiters.remove(index)
                slotsOf(index).release()
                return outcome
            }
            if (!claimed.contains(index) && shouldStop()) return null
            val waiter = waiters.computeIfAbsent(index) { CompletableDeferred() }
            if (withTimeoutOrNull(POLL_INTERVAL_MS) { waiter.await() } != null) continue
        }
    }

    /** 该分片所属车道的槽位 */
    private fun slotsOf(index: Int): Semaphore {
        val size = byIndex[index]?.size ?: 0
        return if (size < lanePlan.bigThresholdBytes) smallSlots else bigSlots
    }

    companion object {
        private const val POLL_INTERVAL_MS = 250L
    }
}

/**
 * 车道配置。
 *
 * @param smallLanes `≤ bigThresholdBytes` 的分片用几条并发(实测单连接 1.3MB/s,几条就到聚合上限)
 * @param bigLanes `> bigThresholdBytes` 的分片用几条(服务端账号级 ~100KB/s,再多只是摊薄)
 * @param ahead 每条车道额外允许「下好但还没落盘」的分片数(内存与白干量的上限)
 */
internal data class LanePlan(
    val smallLanes: Int,
    val bigLanes: Int,
    val bigThresholdBytes: Long,
    val ahead: Int,
) {
    companion object {
        /** 百度对 ≥2MiB 的对象走限速通道(实测边界:2096952B 快 / 2098268B 慢) */
        const val BIG_OBJECT_THRESHOLD = 2L * 1024 * 1024

        /**
         * 车道配置。
         *
         * 小片:单连接 1.1~1.3MB/s,4 条并发实测聚合 3.6~5.8MB/s,再多也不明显涨;
         * 大片:百度按账号限速且随时间波动(实测单连接 39~85KB/s),并发 3 条约 1.4 倍聚合(39→56KB/s),
         * 再多只是摊薄每条,所以只给 3 条。
         */
        val DEFAULT = LanePlan(
            smallLanes = 4,
            bigLanes = 3,
            bigThresholdBytes = BIG_OBJECT_THRESHOLD,
            ahead = 2,
        )
    }
}
