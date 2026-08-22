# 播放页影片详情(title/cast/cover)实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 从列表页进入播放页时,按番号拉取影片详情,显示详情卡片(封面缩略图+标题+番号/年份+演员)和播放器空闲海报。

**Architecture:** 复用现有 `MovieInfoClient`(新增单部详情查询 `findDetail`),`HomeScreen` 在进入时拉取详情并渲染新增的 `MovieDetailCard` 组件;`HlsPlayerView` 新增 `idleCoverUrl` 参数在空闲态显示低透明度封面海报。封面图片用 Coil 加载。

**Tech Stack:** Kotlin 1.9.10 / Jetpack Compose(BOM 2024.02.00)/ Ktor client / Coil 2.7.0

## Global Constraints

- 项目**无测试基础设施**(无测试源集、无测试依赖):每个任务的验证方式是 `./gradlew assembleDebug` 编译通过 + 最后的手工设备验证
- Log tag 必须是类名字符串字面量(如 `"MovieInfoClient"`)
- UI 文案使用中文
- `network_security_config.xml` 不改:cleartext 仅放行 localhost
- 版本:compileSdk 34 / minSdk 30 / Kotlin 1.9.10 / Compose BOM 2024.02.00 / coil-compose 2.7.0
- 仓库里有未提交的其他功能改动(列表页 movie info 等):**每次提交只 `git add` 本任务涉及的文件**,不要 `git add -A`
- spec: `docs/superpowers/specs/2026-08-22-playback-detail-design.md`

---

### Task 1: 数据层 — MovieInfo 扩展 + findDetail + Coil 依赖

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/java/xyz/asitanokibou/player/data/MovieInfoClient.kt`

**Interfaces:**
- Consumes: 无(现有代码)
- Produces:
  - `MovieInfo(fanCode: String, title: String, casts: List<String>, cover: String? = null, year: Int? = null)`
  - `MovieInfoClient.findDetail(fanCode: String): MovieInfo?`(suspend,任何失败返回 null)
  - gradle 依赖 `libs.coil.compose`(io.coil-kt:coil-compose:2.7.0)

- [ ] **Step 1: 版本目录加 Coil**

`gradle/libs.versions.toml` 的 `[versions]` 块末尾(在 `serialization = "1.6.2"` 之后)加:

```toml
coil = "2.7.0"
```

`[libraries]` 块中 `androidx-datastore-preferences` 那行之后加:

```toml
coil-compose = { group = "io.coil-kt", name = "coil-compose", version.ref = "coil" }
```

- [ ] **Step 2: app 模块引入依赖**

`app/build.gradle.kts` 的 dependencies 块中,`// 配置存储` 分组之前(即 Compose 依赖之后)加:

```kotlin
    // 图片加载(Coil)
    implementation(libs.coil.compose)
```

- [ ] **Step 3: MovieInfo 增加字段并透传**

`app/src/main/java/xyz/asitanokibou/player/data/MovieInfoClient.kt` 中,把:

```kotlin
/** 列表展示所需的影片元信息 */
data class MovieInfo(
    val fanCode: String,
    val title: String,
    val casts: List<String> = emptyList(),
)
```

改为:

```kotlin
/** 影片元信息(列表展示 + 播放页详情) */
data class MovieInfo(
    val fanCode: String,
    val title: String,
    val casts: List<String> = emptyList(),
    val cover: String? = null,
    val year: Int? = null,
)
```

同文件 `MovieDto.toInfo()` 把:

```kotlin
    fun toInfo() = MovieInfo(
        fanCode = fanCode,
        title = cleanTitle(fanCode, title),
        casts = casts,
    )
```

改为:

```kotlin
    fun toInfo() = MovieInfo(
        fanCode = fanCode,
        title = cleanTitle(fanCode, title),
        casts = casts,
        cover = cover,
        year = year,
    )
```

- [ ] **Step 4: 新增 findDetail**

`MovieInfoClient` 类中,`findByFanCodes` 方法之后加:

```kotlin
    /** 按番号查单部影片详情;任何失败(未配置地址/网络/HTTP 404 等)返回 null,由 UI 静默降级 */
    suspend fun findDetail(fanCode: String): MovieInfo? {
        val code = fanCode.trim().trim('/')
        if (code.isEmpty()) return null
        val base = baseUrlProvider()?.trim()?.trimEnd('/')
        if (base.isNullOrEmpty()) return null
        return withContext(Dispatchers.IO) {
            try {
                val text = http.get("$base$MOVIES_PATH/$code").bodyAsText()
                json.decodeFromString<MovieDto>(text).toInfo()
            } catch (e: Exception) {
                Log.e(TAG, "获取影片详情失败: ${e.message}")
                null
            }
        }
    }
```

类文档 KDoc 第一行从"影片详情信息(movie_api 客户端)。"保持不变即可(无需改)。
注意:`MOVIES_PATH` 常量已存在(`"/api/movies"`),`Log`/`withContext`/`Dispatchers` 均已在文件 import,无需新增 import。番号字符集为 `[A-Za-z0-9_-]`,直接拼路径安全。

- [ ] **Step 5: 编译验证**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 6: 提交**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/java/xyz/asitanokibou/player/data/MovieInfoClient.kt
git commit -m "feat: MovieInfoClient 新增 findDetail 单片详情查询,引入 Coil"
```

---

### Task 2: 详情卡片组件 + 播放页拉取详情

**Files:**
- Create: `app/src/main/java/xyz/asitanokibou/player/ui/MovieDetailCard.kt`
- Modify: `app/src/main/java/xyz/asitanokibou/player/ui/HomeScreen.kt`
- Modify: `app/src/main/java/xyz/asitanokibou/player/ui/AppRoot.kt`

**Interfaces:**
- Consumes: Task 1 的 `MovieInfo(cover, year)`、`MovieInfoClient.findDetail(fanCode)`、Coil `AsyncImage`
- Produces: `HomeScreen` 新增参数 `movieInfoClient: MovieInfoClient?`(后续 Task 3 在同一函数内使用 `detail` 状态);`MovieDetailCard(detail: MovieInfo, modifier: Modifier)`

- [ ] **Step 1: 新建 MovieDetailCard 组件**

创建 `app/src/main/java/xyz/asitanokibou/player/ui/MovieDetailCard.kt`:

```kotlin
package xyz.asitanokibou.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import xyz.asitanokibou.player.data.MovieInfo

/** 播放页影片详情卡片:竖版封面缩略图 + 标题 + 番号/年份 + 演员 */
@Composable
internal fun MovieDetailCard(
    detail: MovieInfo,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 封面缩略图:竖版 96x135,加载失败时露出 surfaceVariant 色块
        Box(
            modifier = Modifier
                .width(96.dp)
                .height(135.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            detail.cover?.let { url ->
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
            verticalArrangement = Arrangement.spacedBy(4.dp),
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
                Text(
                    text = detail.casts.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
```

- [ ] **Step 2: HomeScreen 增加 client 参数与详情拉取**

`app/src/main/java/xyz/asitanokibou/player/ui/HomeScreen.kt`:

签名在 `initialPath: String,` 之后加参数:

```kotlin
    movieInfoClient: MovieInfoClient?,
```

文件头部 import 区加:

```kotlin
import androidx.compose.foundation.layout.height
import xyz.asitanokibou.player.data.MovieInfo
import xyz.asitanokibou.player.data.MovieInfoClient
```

(`height` 为本任务新增,后续两个布局步骤的 `Spacer(Modifier.height(12.dp))` 都会用到)

函数体内,`var isFullscreen by remember { mutableStateOf(false) }` 之后加:

```kotlin
    // 从列表进入(initialPath 非空)时按目录名(番号)拉取详情;失败静默降级为 null
    var detail by remember(initialPath) { mutableStateOf<MovieInfo?>(null) }
    LaunchedEffect(initialPath, movieInfoClient) {
        detail = null
        val fanCode = initialPath.trim().trim('/')
        if (fanCode.isNotEmpty()) {
            detail = movieInfoClient?.findDetail(fanCode)
        }
    }
```

- [ ] **Step 3: 竖屏布局插入卡片**

`HomeScreen` 竖屏分支(`else ->` 的 `Column`)中,把:

```kotlin
                        ControlsPanel(
                            modifier = Modifier.fillMaxWidth(),
                            path = path,
                            onPathChange = { path = it },
                            onPlay = {
                                error.value = null
                                controller?.let { playPath(it, path) }
                            },
                            onBack = onBack,
                            onOpenSettings = onOpenSettings,
                            hasToken = hasToken,
                            controller = controller,
                            status = status.value,
                            error = error.value,
                        )
                        Spacer(Modifier.padding(4.dp))
```

改为(控制面板下方、播放器上方):

```kotlin
                        ControlsPanel(
                            modifier = Modifier.fillMaxWidth(),
                            path = path,
                            onPathChange = { path = it },
                            onPlay = {
                                error.value = null
                                controller?.let { playPath(it, path) }
                            },
                            onBack = onBack,
                            onOpenSettings = onOpenSettings,
                            hasToken = hasToken,
                            controller = controller,
                            status = status.value,
                            error = error.value,
                        )
                        detail?.let {
                            Spacer(Modifier.height(12.dp))
                            MovieDetailCard(detail = it)
                        }
                        Spacer(Modifier.padding(4.dp))
```

- [ ] **Step 4: 横屏布局插入卡片**

横屏分支中,把左侧 `ControlsPanel(...)`(带 `weight(1f)` 的那个)连同其外层位置改为外包一层 Column,卡片放控制面板下方。即把:

```kotlin
                if (isWide) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        ControlsPanel(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState()),
                            path = path,
                            onPathChange = { path = it },
                            onPlay = {
                                error.value = null
                                controller?.let { playPath(it, path) }
                            },
                            onBack = onBack,
                            onOpenSettings = onOpenSettings,
                            hasToken = hasToken,
                            controller = controller,
                            status = status.value,
                            error = error.value,
                        )
                        Box(
```

改为:

```kotlin
                if (isWide) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        ) {
                            ControlsPanel(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState()),
                                path = path,
                                onPathChange = { path = it },
                                onPlay = {
                                    error.value = null
                                    controller?.let { playPath(it, path) }
                                },
                                onBack = onBack,
                                onOpenSettings = onOpenSettings,
                                hasToken = hasToken,
                                controller = controller,
                                status = status.value,
                                error = error.value,
                            )
                            detail?.let {
                                Spacer(Modifier.height(12.dp))
                                MovieDetailCard(detail = it)
                            }
                        }
                        Box(
```

注意:`Modifier.height` 的 import 已在 Step 2 加入,无需重复处理。

- [ ] **Step 5: AppRoot 传入 client**

`app/src/main/java/xyz/asitanokibou/player/ui/AppRoot.kt` 中 `Screen.Play` 分支改为:

```kotlin
            is Screen.Play -> HomeScreen(
                controller = controller,
                hasToken = hasToken,
                initialPath = screen.initialPath,
                movieInfoClient = movieInfo,
                onBack = { nav.pop() },
                onOpenSettings = { nav.push(Screen.Settings) },
                onFullscreenChanged = onFullscreenChanged,
            )
```

(`movieInfo` 是 AppRoot 已有的 `MovieInfoClient?` 参数,无需新增。)

- [ ] **Step 6: 编译验证**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: 提交**

```bash
git add app/src/main/java/xyz/asitanokibou/player/ui/MovieDetailCard.kt app/src/main/java/xyz/asitanokibou/player/ui/HomeScreen.kt app/src/main/java/xyz/asitanokibou/player/ui/AppRoot.kt
git commit -m "feat: 播放页显示影片详情卡片(封面/标题/番号/演员)"
```

---

### Task 3: 播放器空闲海报

**Files:**
- Modify: `app/src/main/java/xyz/asitanokibou/player/ui/PlayerView.kt`
- Modify: `app/src/main/java/xyz/asitanokibou/player/ui/HomeScreen.kt`

**Interfaces:**
- Consumes: Task 1 `MovieInfo.cover`、Coil `AsyncImage`;Task 2 `HomeScreen` 的 `detail` 状态
- Produces: `HlsPlayerView(controller, onToggleFullscreen, modifier, resizeMode, idleCoverUrl: String? = null)`

- [ ] **Step 1: HlsPlayerView 增加海报层**

`app/src/main/java/xyz/asitanokibou/player/ui/PlayerView.kt`:

签名改为:

```kotlin
@OptIn(UnstableApi::class)
@Composable
internal fun HlsPlayerView(
    controller: Player?,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
    resizeMode: Int = AspectRatioFrameLayout.RESIZE_MODE_FIT,
    idleCoverUrl: String? = null,
) {
```

import 区加:

```kotlin
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
```

函数体 `Box(modifier = modifier) {` 内,`AndroidView(...)` 调用之后、`PlayerGestureOverlay(...)` 之前插入:

```kotlin
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
```

- [ ] **Step 2: HomeScreen 跟踪播放器状态并计算空闲封面**

`app/src/main/java/xyz/asitanokibou/player/ui/HomeScreen.kt`:

Task 2 加的详情拉取代码之后(`detail` 状态之后)加:

```kotlin
    // 播放器状态:空闲(未 prepare)时用于显示封面海报
    var playbackState by remember(controller) {
        mutableStateOf(controller?.playbackState ?: Player.STATE_IDLE)
    }
    val idleCoverUrl = detail?.cover?.takeIf { playbackState == Player.STATE_IDLE }
```

现有 `DisposableEffect(controller)` 中的 listener,把:

```kotlin
            override fun onPlaybackStateChanged(state: Int) {
                status.value = when (state) {
```

改为:

```kotlin
            override fun onPlaybackStateChanged(state: Int) {
                playbackState = state
                status.value = when (state) {
```

- [ ] **Step 3: 三处 HlsPlayerView 调用传入 idleCoverUrl**

`HomeScreen` 中三处 `HlsPlayerView(` 调用(全屏分支、横屏分支、竖屏分支)都加参数 `idleCoverUrl = idleCoverUrl,`(跟在 `resizeMode = ...` 之后)。全屏分支示例:

```kotlin
            HlsPlayerView(
                controller = controller,
                onToggleFullscreen = { isFullscreen = !isFullscreen },
                modifier = Modifier.fillMaxSize(),
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT,
                idleCoverUrl = idleCoverUrl,
            )
```

- [ ] **Step 4: 编译验证**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/xyz/asitanokibou/player/ui/PlayerView.kt app/src/main/java/xyz/asitanokibou/player/ui/HomeScreen.kt
git commit -m "feat: 播放器空闲态显示封面海报,开始播放后隐藏"
```

---

### Task 4: 手工设备验证

**Files:** 无代码改动(验证任务)

**Interfaces:**
- Consumes: Task 1-3 全部产出
- Produces: 验证结论

前置:设置页已配置有效的百度 access_token 与 movie_api 地址(`https://...` 或设备可达地址);movie_api 已启动且数据库中有封面数据。

- [ ] **Step 1: 安装**

```bash
./gradlew installDebug
```

- [ ] **Step 2: 验证清单(逐项核对)**

1. 列表页进入任一影片播放页:控制面板下方出现详情卡片(封面缩略图、标题、番号·年份、演员)
2. 播放器 16:9 黑框内显示半透明封面海报;点「播放」进入缓冲后海报消失,视频正常播放
3. 播放中卡片持续显示;点「播放」前手动改路径,卡片不变(不重新拉取)
4. 返回再从列表进入另一部影片:卡片与海报切换为新影片
5. 设置里清空 movie_api 地址后进入播放页:无卡片、无海报、无报错,布局与旧版一致
6. 竖屏、横屏(旋转)、全屏三种模式分别检查卡片位置与海报显示
7. 日志无异常刷屏:`adb shell logcat -s MovieInfoClient`

- [ ] **Step 3: 验证结论记录**

在 PR/提交说明或口头汇报中记录以上 7 项的通过情况;任何失败项回到对应 Task 修复。
