package xyz.asitanokibou.player.ui

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 从 SurfaceView 抓取当前视频帧。SurfaceView 的内容由独立图层合成,
 * 普通 draw(View) 到 Bitmap 拿不到画面,PixelCopy 是系统提供的唯一同步抓取途径
 * (API 26+,本项目 minSdk 30 满足)。
 *
 * 返回 null 表示抓取失败:视图不是 SurfaceView、尺寸为 0 或 surface 尚未渲染过帧。
 */
@Suppress("DEPRECATION") // Executor 版重载 API 34 才有,listener 版本全版本可用
suspend fun captureSurfaceFrame(view: View): Bitmap? {
    val surface = view as? SurfaceView ?: return null
    if (surface.width <= 0 || surface.height <= 0) return null
    val bitmap = Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
    val result = suspendCancellableCoroutine<Int> { cont ->
        PixelCopy.request(
            surface,
            bitmap,
            { copyResult -> cont.resume(copyResult) },
            Handler(Looper.getMainLooper()),
        )
    }
    if (result != PixelCopy.SUCCESS) {
        bitmap.recycle()
        return null
    }
    return bitmap
}

/**
 * 保存截图到系统相册 Pictures/Player 目录。Scoped Storage 下往 MediaStore
 * 写自己的图片贡献无需任何权限(API 29+)。返回 null 表示保存失败。
 */
suspend fun saveBitmapToGallery(context: Context, bitmap: Bitmap): Uri? = withContext(Dispatchers.IO) {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "player_${System.currentTimeMillis()}.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Player")
    }
    try {
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return@withContext null
        val saved = resolver.openOutputStream(uri)?.use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        } ?: false
        if (!saved) {
            resolver.delete(uri, null, null)
            null
        } else {
            uri
        }
    } catch (e: Exception) {
        null
    }
}
