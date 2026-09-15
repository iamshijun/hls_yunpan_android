# hls_pan_player (Android)

将网盘 HLS 代理服务改造为 Android 原生 App：本地 Ktor 代理 + Media3 ExoPlayer 播放。

- 包名：`xyz.asitanokibou.player`
- minSdk：30（Android 11），targetSdk / compileSdk：34
- 工具链：AGP 8.1.4 / Gradle 8.2 / Kotlin 1.9.22（适配 Android Studio Giraffe 2022.3.1）
- UI：Jetpack Compose（Compose 编译器扩展 1.5.10）
- 播放：Media3 ExoPlayer（HLS），统一由 `PlaybackService`（MediaSessionService）托管
- 本地代理与百度下载：Ktor Server / Client（CIO）
- fsid 缓存：仅内存（MVP）

## 运行步骤

1. Android Studio 选择 **Open**，打开本 `android/` 目录（不是仓库根）。
2. 首次自动生成 `local.properties`（SDK 路径）和 Gradle Wrapper。
   - 若命令行构建，先 `gradle wrapper --gradle-version 8.2` 生成 wrapper。
3. **重要**：第一次 Sync 后若 IDE 报 `kotlinx.serialization compiler plugin is not applied`，做 `File → Invalidate Caches / Restart`（编译本身不受影响）。
4. 真机 / 模拟器运行 `app`。
5. 启动后：
   - 授予「通知」权限（Android 13+）；
   - 进入「设置」填写百度网盘 `access_token`（设置页保存即生效）；
   - 返回主页输入网盘目录名（如 `video1`，对应 `/apps/{app_name}/movies/video1/playlist.m3u8`），点播放。

## 已知限制（MVP 范围）

- **目录约定**：`/apps/{app_name}/movies/<输入路径>/playlist.m3u8`，与 Python 版一致。`app_name` = 你在百度网盘开放平台注册的应用名，在 `app/build.gradle.kts` 的 `BAIDU_APP_NAME` 中配置，改它即可调整路径前缀（需重新构建）。
- **本地模式**：未实现（手机端单实例，走云端）。
- **Redis fsid 共享**：未实现（单实例，内存 fsid 已够用）。
- **缓存开关**：`cacheEnabled` / `cacheSegments` / `cacheTtlSec` 启动时快照；改动需重启播放服务（杀进程重开）。
- **access_token**：过期后仅在第一次网络请求时才会被百度端拒绝（错误会显示在 UI）。重新进入「设置」覆盖新 token 即可（立即生效）。

## 图形调整

参数（都在 `PlayerView.kt`，`SpeedBoostOverlay` 和 `drawFastForwardTriangles` 两个函数里）：

| 想调整什么  | 改哪里                                                      | 说明                                    |
|--------|----------------------------------------------------------|---------------------------------------|
| 三角形大小  | `triWidth` / `gap`（`drawFastForwardTriangles` 内）         | 单个三角宽、间距，dp 单位                        |
| 图标整体尺寸 | `Box(Modifier.size(width=, height=))`                    | 记得同步放大，否则会裁切                          |
| 波浪速度   | `tween(durationMillis = 800)`                            | 一圈 0→1 的毫秒数，越小越快                      |
| 亮/暗幅度  | `0.25f`（`drawFastForwardTriangles` 内）                    | 暗部透明度底值，0.25 = 最暗 25%；调大到 0.5 则闪烁对比变弱 |
| 整体亮度   | `0.9f * alpha`                                           | 图标整体透明度上限                             |
| 提示位置   | `.padding(top = 24.dp)`（`AnimatedVisibility` 的 modifier） | 距顶部距离                                 |
| 胶囊背景   | `Color.Black.copy(alpha = 0.5f)`                         | 背景透明度                                 |
| 胶囊圆角   | `RoundedCornerShape(50)`                                 | 50 = 全圆胶囊，调小变方                        |
| 胶囊上下高度 | `.padding(horizontal = 14.dp, vertical = 6.dp)`          | 胶囊内边距                                 |
| 文字大小   | `MaterialTheme.typography.titleSmall`                    | 可换 `titleMedium` 等                    |
| 淡入淡出   | `fadeIn` / `fadeOut(tween(150/200))`                     | 出现/消失时长                               |