package xyz.asitanokibou.player.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import xyz.asitanokibou.player.HlsPanApp
import xyz.asitanokibou.player.MainActivity
import xyz.asitanokibou.player.download.DownloadStatus
import xyz.asitanokibou.player.download.DownloadTask
import android.app.Service as AndroidService

/**
 * 下载前台服务:只做宿主与通知;队列/下载逻辑全在 DownloadManager。
 *
 * - startForegroundService 拉起;进入前台后收集 manager.tasks 更新通知
 * - 有 RUNNING/QUEUED 任务时保持前台;队列空闲即 stopSelf
 * - 通知点击回到 MainActivity
 */
class DownloadService : AndroidService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var manager: xyz.asitanokibou.player.download.DownloadManager

    override fun onCreate() {
        super.onCreate()
        manager = (application as HlsPanApp).container.downloadManager
        createChannel()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildIdleNotification(), foregroundType())
        serviceScope.launch {
            manager.tasks.collectLatest { tasks -> onTasksChanged(tasks) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun onTasksChanged(tasks: List<DownloadTask>) {
        val active = tasks.filter { it.status == DownloadStatus.RUNNING || it.status == DownloadStatus.QUEUED }
        if (active.isEmpty()) {
            Log.i(TAG, "队列空闲,停止下载服务")
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildProgressNotification(active))
    }

    /**
     * 通知展示:最多同时下载 [xyz.asitanokibou.player.download.DownloadManager.MAX_CONCURRENT] 个,
     * 标题分母为实际待下载数量(RUNNING + QUEUED,随完成递减);内容逐行给出 番号+百分比;
     * 进度条取进行中任务的分片完成数聚合(解析分片列表前为不确定态)。
     */
    private fun buildProgressNotification(active: List<DownloadTask>): Notification {
        val running = active.filter { it.status == DownloadStatus.RUNNING }
        val queuedCount = active.count { it.status == DownloadStatus.QUEUED }
        val content = buildString {
            if (running.isNotEmpty()) {
                append(running.joinToString("  ") {
                    "${it.fanCode} ${(it.progress * 100).toInt()}%"
                })
                if (queuedCount > 0) append("  |  排队 $queuedCount")
            } else {
                append("排队中:$queuedCount 个任务")
            }
        }
        val segTotal = running.sumOf { it.totalSegments }
        val segDone = running.sumOf { it.completedCount }
        val determinate = running.isNotEmpty() && segTotal > 0
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("正在下载 ${running.size}/${active.size}")
            .setContentText(content)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, if (determinate) (segDone * 100 / segTotal).toInt() else 0, !determinate)
            .setContentIntent(mainActivityPendingIntent())
            .build()
    }

    private fun buildIdleNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("下载服务")
            .setContentText("准备中...")
            .setOngoing(true)
            .setContentIntent(mainActivityPendingIntent())
            .build()

    private fun mainActivityPendingIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            /* requestCode = */ 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "下载进度",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "HLS 分片下载进度" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun foregroundType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }

    companion object {
        private const val TAG = "DownloadService"
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 2001

        /** UI 入队后拉起前台服务的便捷入口 */
        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
