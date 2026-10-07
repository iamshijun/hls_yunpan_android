package xyz.asitanokibou.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import xyz.asitanokibou.player.data.MovieInfo

/** 播放页影片详情卡片:竖版封面缩略图 + 标题 + 番号/年份 + 演员(可点击) */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MovieDetailCard(
    detail: MovieInfo,
    modifier: Modifier = Modifier,
    /** 点击缩略图查看大图;为 null 时缩略图不可点击 */
    onCoverClick: (() -> Unit)? = null,
    /** 点击某个演员名浏览其全部影片(走「从网页浏览影片」入口);为 null 时演员名不可点击 */
    onCastClick: ((String) -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 封面缩略图:竖版 90x125,加载失败时露出 surfaceVariant 色块
        Box(
            modifier = Modifier
                .width(90.dp)
                .height(125.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(enabled = onCoverClick != null) { onCoverClick?.invoke() },
            contentAlignment = Alignment.Center,
        ) {
            // 竖版缩略图:优先 thumbnail,为空时回退 cover 大图;加载失败露出 surfaceVariant 色块
            (detail.thumbnail ?: detail.cover)?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = detail.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = detail.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(
                detail.fanCode,
                detail.year?.toString(),
            ).joinToString(" · ")
            if (meta.isNotEmpty()) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (detail.casts.isNotEmpty()) {
                // 每个演员名单独可点(下划线 + 主色作为链接暗示),窄栏/分屏下自动换行
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    detail.casts.forEach { cast ->
                        val clickable = onCastClick != null
                        Text(
                            text = cast,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (clickable) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            textDecoration = if (clickable) TextDecoration.Underline else null,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable(enabled = clickable) { onCastClick?.invoke(cast) },
                        )
                    }
                }
            }
        }
    }
}
