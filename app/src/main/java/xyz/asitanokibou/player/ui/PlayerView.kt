package xyz.asitanokibou.player.ui

import android.content.Context
import android.media.AudioManager
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.view.View
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import xyz.asitanokibou.player.ui.system.DOUBLE_TAP_SEEK_MS
import xyz.asitanokibou.player.ui.system.readBrightness
import xyz.asitanokibou.player.ui.system.setBrightness
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt

private const val LONG_PRESS_SPEED = 2f

@OptIn(UnstableApi::class)
@Composable
internal fun HlsPlayerView(
    controller: Player?,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
    resizeMode: Int = AspectRatioFrameLayout.RESIZE_MODE_FIT,
    idleCoverUrl: String? = null,
    title: String? = null,
    coverUrl: String? = null,
    doubleTapToSeek: Boolean = false,
) {
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    var seekDeltaSec by remember { mutableStateOf<Float?>(null) }
    var seekBaseMs by remember { mutableStateOf(0L) }
    var volumeLevel by remember { mutableStateOf<Float?>(null) }
    var brightnessLevel by remember { mutableStateOf<Float?>(null) }
    var volumeBase by remember { mutableStateOf(0.5f) }
    var brightnessBase by remember { mutableStateOf(0.5f) }
    // 标题栏随默认控制栏一起显隐(YouTube 风格):初始控制栏可见
    var controllerVisible by remember { mutableStateOf(true) }
    // 长按倍速播放中
    var speedBoost by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx ->
                GesturePlayerView(ctx).apply {
                    useController = true
                    this.resizeMode = resizeMode
                    setFullscreenButtonClickListener { onToggleFullscreen() }
                    // 标题栏跟随控制栏显隐:控制栏显示(含自动收起前的初始态)时露出标题
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                        controllerVisible = visibility == View.VISIBLE
                    })

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
                            if (doubleTapToSeek) {
                                p.seekTo((p.currentPosition + side * DOUBLE_TAP_SEEK_MS).coerceAtLeast(0L))
                            } else {
                                if (p.playWhenReady) p.pause() else p.play()
                            }
                        }
                    }
                    onLongPressStart = {
                        speedBoost = true
                        player?.setPlaybackSpeed(LONG_PRESS_SPEED)
                    }
                    onLongPressEnd = {
                        speedBoost = false
                        player?.setPlaybackSpeed(1f)
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
            speedBoost = speedBoost,
            modifier = Modifier.fillMaxSize(),
        )

        // 顶部标题栏:与控制栏同步显隐;纯 Box 无 pointerInput,触摸穿透到播放器不挡手势
        if (controllerVisible && !title.isNullOrBlank()) {
            PlayerTitleBar(
                title = title,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

@Composable
private fun PlayerTitleBar(title: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent),
                ),
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = title,
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
    speedBoost: Boolean,
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
        AnimatedVisibility(
            visible = speedBoost,
            enter = fadeIn(animationSpec = tween(150)),
            exit = fadeOut(animationSpec = tween(200)),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 24.dp),
        ) {
            SpeedBoostOverlay()
        }
    }
}

/**
 * bilibili 风格长按倍速提示：横向半透明胶囊 = 三个向右三角图标 + 文字，
 * 靠近顶部显示，整体紧凑；三角形整体做透明度脉动模拟快进闪烁效果。
 */
@Composable
private fun SpeedBoostOverlay(modifier: Modifier = Modifier) {
    // 波浪相位基准：0..1 循环，三角形透明度按相位依次亮灭（从左到右波浪）
    val pulse by rememberInfiniteTransition(label = "speedPulse").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = LinearEasing),
        ),
        label = "speedAlpha",
    )
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 三个向右的三角形，从左到右逐渐变小（快进加速感）
        Box(
            modifier = Modifier.size(width = 24.2.dp, height = 17.6.dp),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                drawFastForwardTriangles(pulse)
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = "2x 倍速",
            color = Color.White.copy(alpha = 0.9f),
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

/**
 * 绘制三个等大的向右三角形（类似 ⏩）：按波浪相位从左到右依次亮灭，
 * [pulse] 为 0..1 循环的相位基准，各三角形相位依次错开 1/3 周期。
 */
private fun DrawScope.drawFastForwardTriangles(pulse: Float) {
    val heightPx = size.height
    val triWidth = 6.6.dp.toPx()
    val gap = 2.2.dp.toPx()
    repeat(3) { i ->
        // 相位递减（取模），使波峰依次经过 tri0 → tri1 → tri2，即从左到右传播
        val phase = (pulse - i * 0.3333f + 1f) % 1f
        // 余弦脉冲：alpha 在 0.25..1 平滑变化（phase = 0 时最亮）
        val alpha = 0.25f + 0.75f * ((cos(phase * 2f * PI.toFloat()) + 1f) / 2f)
        val color = Color.White.copy(alpha = 0.9f * alpha)
        val x = i * (triWidth + gap)
        val path = Path().apply {
            moveTo(x, 0f)
            lineTo(x + triWidth, heightPx / 2)
            lineTo(x, heightPx)
            close()
        }
        drawPath(path, color)
    }
}

@Composable
private fun SeekOverlay(deltaSec: Float, baseMs: Long, targetMs: Long) {
    val isForward = deltaSec >= 0f
    Column(
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(12.dp))
            .padding(horizontal = 22.dp, vertical = 16.dp),
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
