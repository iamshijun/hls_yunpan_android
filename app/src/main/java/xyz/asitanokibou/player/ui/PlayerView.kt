package xyz.asitanokibou.player.ui

import android.content.Context
import android.media.AudioManager
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import coil.compose.AsyncImage
import xyz.asitanokibou.player.ui.system.DOUBLE_TAP_SEEK_MS
import xyz.asitanokibou.player.ui.system.readBrightness
import xyz.asitanokibou.player.ui.system.setBrightness
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
@Composable
internal fun HlsPlayerView(
    controller: Player?,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
    resizeMode: Int = AspectRatioFrameLayout.RESIZE_MODE_FIT,
    idleCoverUrl: String? = null,
) {
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    var seekDeltaSec by remember { mutableStateOf<Float?>(null) }
    var seekBaseMs by remember { mutableStateOf(0L) }
    var volumeLevel by remember { mutableStateOf<Float?>(null) }
    var brightnessLevel by remember { mutableStateOf<Float?>(null) }
    var volumeBase by remember { mutableStateOf(0.5f) }
    var brightnessBase by remember { mutableStateOf(0.5f) }

    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx ->
                GesturePlayerView(ctx).apply {
                    useController = true
                    this.resizeMode = resizeMode
                    setFullscreenButtonClickListener { onToggleFullscreen() }

                    onSeekPreview = { deltaSec ->
                        if (seekDeltaSec == null) {
                            seekBaseMs = player?.currentPosition ?: 0L
                        }
                        seekDeltaSec = deltaSec
                    }
                    onSeekCommit = {
                        seekDeltaSec?.let { delta ->
                            val target = seekBaseMs + (delta * 1000).toLong()
                            player?.seekTo(target.coerceAtLeast(0L))
                        }
                        seekDeltaSec = null
                    }
                    onSeekCancel = {
                        seekDeltaSec = null
                    }
                    onVerticalDrag = { fraction, isLeftHalf ->
                        if (isLeftHalf) {
                            if (brightnessLevel == null) brightnessBase = readBrightness(context)
                            val target = (brightnessBase + fraction).coerceIn(0.01f, 1f)
                            setBrightness(context, target)
                            brightnessLevel = target
                        } else {
                            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                            if (volumeLevel == null && max > 0) {
                                volumeBase = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
                            }
                            val level = (volumeBase + fraction).coerceIn(0f, 1f)
                            if (max > 0) {
                                audioManager.setStreamVolume(
                                    AudioManager.STREAM_MUSIC,
                                    (level * max).roundToInt(),
                                    0,
                                )
                            }
                            volumeLevel = level
                        }
                    }
                    onVerticalDragEnd = {
                        volumeLevel = null
                        brightnessLevel = null
                    }
                    onDoubleTap = { side ->
                        player?.let { p ->
                            p.seekTo((p.currentPosition + side * DOUBLE_TAP_SEEK_MS).coerceAtLeast(0L))
                        }
                    }
                }
            },
            update = { view ->
                if (view.player !== controller) {
                    if (view.isAttachedToWindow) {
                        view.player = controller
                    } else {
                        view.post { view.player = controller }
                    }
                }
                view.resizeMode = resizeMode
            },
            onRelease = { view ->
                view.player = null
            },
            modifier = Modifier.matchParentSize(),
        )

        // 空闲态海报:低透明度封面铺在播放器上、手势层之下,不消费触摸事件
        idleCoverUrl?.let { url ->
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.5f,
                modifier = Modifier.matchParentSize(),
            )
        }

        PlayerGestureOverlay(
            seekDeltaSec = seekDeltaSec,
            seekBaseMs = seekBaseMs,
            durationMs = controller?.duration?.takeIf { it > 0 } ?: 0L,
            volumeLevel = volumeLevel,
            brightnessLevel = brightnessLevel,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun PlayerGestureOverlay(
    seekDeltaSec: Float?,
    seekBaseMs: Long,
    durationMs: Long,
    volumeLevel: Float?,
    brightnessLevel: Float?,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        seekDeltaSec?.let { delta ->
            val targetMs = if (durationMs > 0) {
                (seekBaseMs + (delta * 1000).toLong()).coerceIn(0L, durationMs)
            } else {
                (seekBaseMs + (delta * 1000).toLong()).coerceAtLeast(0L)
            }
            SeekOverlay(
                deltaSec = delta,
                baseMs = seekBaseMs,
                targetMs = targetMs,
            )
        }
        brightnessLevel?.let { level ->
            LevelOverlay(
                label = "亮度",
                level = level,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 28.dp),
            )
        }
        volumeLevel?.let { level ->
            LevelOverlay(
                label = "音量",
                level = level,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 28.dp),
            )
        }
    }
}

@Composable
private fun SeekOverlay(deltaSec: Float, baseMs: Long, targetMs: Long) {
    val isForward = deltaSec >= 0f
    Column(
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(12.dp))
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (isForward) "快进 ${abs(deltaSec).roundToInt()} 秒" else "快退 ${abs(deltaSec).roundToInt()} 秒",
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "${formatTime(baseMs)} → ${formatTime(targetMs)}",
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun LevelOverlay(label: String, level: Float, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(10.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = Color.White, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .width(6.dp)
                .height(80.dp)
                .background(Color.White.copy(alpha = 0.3f), RoundedCornerShape(3.dp)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(level.coerceIn(0f, 1f))
                    .background(Color.White, RoundedCornerShape(3.dp)),
            )
        }
    }
}

internal fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val hours = totalSec / 3600
    val minutes = (totalSec % 3600) / 60
    val seconds = totalSec % 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}
