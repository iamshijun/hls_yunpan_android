# 长按倍速提示的图形微调

播放页长按 2 倍速时,画面右上角会弹出一个带三个前进三角形(波浪式明暗)的提示胶囊。
想调它的外观,改 `app/src/main/java/xyz/asitanokibou/player/ui/PlayerView.kt` 里的
`SpeedBoostOverlay` / `drawFastForwardTriangles`:

| 想调整什么 | 改哪里 | 说明 |
|---|---|---|
| 三角形大小 | `SPEED_TRIANGLE_HEIGHT` / `SPEED_TRIANGLE_WIDTH` | 单个三角形的高与宽 |
| 三角形间距 | `SPEED_TRIANGLE_GAP` | dp 单位 |
| 波浪速度 | `tween(durationMillis = 800)` | 一圈的毫秒数,越小越快 |
| 亮/暗幅度 | `drawFastForwardTriangles` 内的透明度底值 | 越大对比越弱 |
| 整体亮度 | `0.9f * alpha` | 图标透明度上限 |
| 提示位置 | `AnimatedVisibility` 的 `padding(top = 24.dp)` | 距顶部距离 |
| 胶囊背景 | `Color.Black.copy(alpha = 0.5f)` | 背景透明度 |
| 胶囊圆角 | `RoundedCornerShape(50)` | 50 为全圆胶囊 |
| 胶囊内边距 | `padding(horizontal = 14.dp, vertical = 6.dp)` | 决定胶囊高度 |
| 文字大小 | `MaterialTheme.typography.titleSmall` | 可换 `titleMedium` 等 |
| 淡入淡出 | `fadeIn` / `fadeOut(tween(150/200))` | 出现/消失时长 |
