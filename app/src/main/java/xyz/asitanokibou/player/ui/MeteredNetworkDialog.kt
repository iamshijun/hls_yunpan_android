package xyz.asitanokibou.player.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/**
 * 移动数据闸门的「本次继续 / 取消」弹框 —— 三个发起入口(播放、入队、恢复)共用同一份语义:
 * 只放行这一次,不提供"以后都允许"(那要走设置里的开关)。
 *
 * @param action 动作名,例如「播放」「下载」,只用于文案
 */
@Composable
internal fun MeteredNetworkDialog(
    action: String,
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("当前为移动数据") },
        text = {
            Text(
                "继续${action}会通过移动数据取流，可能产生较多流量费用。" +
                    "「本次继续」只对这一次${action}有效，之后仍会重新询问；" +
                    "想一律允许请到「设置」打开对应开关。",
            )
        },
        confirmButton = {
            TextButton(onClick = onContinue) { Text("本次继续") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
