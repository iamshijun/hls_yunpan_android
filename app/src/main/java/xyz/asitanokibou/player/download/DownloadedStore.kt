package xyz.asitanokibou.player.download

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 已下载文件的媒体库存取(Movies/HlsPan/)。
 *
 * - Scoped Storage:通过 MediaStore 写入公共 Movies 目录,无需存储权限
 * - 发布走 IS_PENDING 原子机制:先以 pending 条目写入,内容复制完成后置 0,
 *   保证媒体库中永远不会出现半成品
 * - 已完成列表查询 / 删除也在此处
 */
object DownloadedStore {

    const val DIR_NAME = "HlsPan"
    private const val MIME_TS = "video/mp2t"

    /** 媒体库中是否已存在指定番号的已完成文件 */
    fun exists(context: Context, fanCode: String): Boolean =
        queryUri(context, fanCode) != null

    /**
     * 发布一个本地文件到 Movies/HlsPan/<fanCode>.ts。
     * 以 IS_PENDING=1 创建条目 → 复制内容 → IS_PENDING=0 原子可见。
     * 复制完成后调用方负责删除源文件。
     */
    suspend fun publish(context: Context, fanCode: String, source: File) = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "$fanCode.ts")
            put(MediaStore.Video.Media.MIME_TYPE, MIME_TS)
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/$DIR_NAME")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("MediaStore 插入失败: $fanCode")
        try {
            resolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { it.copyTo(out, DEFAULT_BUFFER_SIZE * 8) }
            } ?: throw IllegalStateException("MediaStore 打开输出流失败: $fanCode")
            val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
        } catch (e: Exception) {
            // 发布失败清理 pending 条目,不留半成品记录
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    /** 查询已完成文件列表,按修改时间倒序 */
    fun list(context: Context): List<DownloadedItem> {
        val result = mutableListOf<DownloadedItem>()
        query(context)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameCol) ?: continue
                val fanCode = name.removeSuffix(".ts")
                if (fanCode.isEmpty()) continue
                result.add(
                    DownloadedItem(
                        id = cursor.getLong(idCol),
                        fanCode = fanCode,
                        sizeBytes = cursor.getLong(sizeCol),
                        modifiedAtMillis = cursor.getLong(dateCol) * 1000L,
                    )
                )
            }
        }
        return result.sortedByDescending { it.modifiedAtMillis }
    }

    /** 删除一个已完成条目(仅删媒体库记录;文件由 MediaStore 一并清理) */
    fun delete(context: Context, item: DownloadedItem): Boolean {
        val uri = ContentUris.withAppendedId(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), item.id
        )
        return context.contentResolver.delete(uri, null, null) > 0
    }

    private fun queryUri(context: Context, fanCode: String): Uri? {
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Video.Media._ID),
            "${MediaStore.Video.Media.DISPLAY_NAME} = ?",
            arrayOf("$fanCode.ts"),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(0)
                return ContentUris.withAppendedId(collection, id)
            }
        }
        return null
    }

    private fun query(context: Context) =
        context.contentResolver.query(
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATE_MODIFIED,
            ),
            "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("%$DIR_NAME%"),
            null,
        )
}

/** 已完成下载的媒体库条目 */
data class DownloadedItem(
    val id: Long,
    val fanCode: String,
    val sizeBytes: Long,
    val modifiedAtMillis: Long,
)
