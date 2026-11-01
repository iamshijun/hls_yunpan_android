package xyz.asitanokibou.player.progress

/**
 * 标记「只能由 [xyz.asitanokibou.player.service.PlaybackService] 调用」的成员。
 *
 * 位置采集必须留在服务侧:UI 的 MediaController 在 MainActivity.onStop 就被释放,
 * 退到后台后只有服务还持有 player,任何挂在 UI 侧的采集器都会在后台失效。
 * 加了 @OptIn 的调用点因此必须是一次自觉的选择,而不是顺手加一行。
 */
@RequiresOptIn(
    message = "观看进度的位置采集只由 PlaybackService 写入(UI 在后台拿不到 player);" +
        "UI 侧只允许 remove / clear / resumePosition",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
annotation class PlaybackServiceOnly
