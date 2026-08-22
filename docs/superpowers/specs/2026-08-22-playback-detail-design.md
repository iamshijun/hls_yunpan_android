# 播放页影片详情(title / cast / cover)设计

日期:2026-08-22
状态:已确认(方案 B + A,卡片位于控制面板下方)

## 背景

列表页(`MovieListScreen`)已通过 `MovieInfoClient` 批量拉取影片 title/casts 展示,
但播放页(`HomeScreen`)没有任何详情信息,封面(cover)也从未使用。
movie_api 提供单部影片详情接口 `GET /api/movies/{fan_code}`,返回
`fan_code/title/year/cover/duration/casts/labels/playback_url`,404 表示不存在。

## 目标

从列表页进入播放页时,按目录名(即番号)拉取影片详情,展示:

1. **详情卡片**(方案 B):小尺寸竖版封面缩略图 + 标题 + 番号/年份 + 演员列表
2. **播放器空闲海报**(方案 A):点播放前,封面以低透明度铺在播放器黑框内,开始播放即隐藏

非目标(明确不做):

- 整页模糊背景
- 进入播放页自动播放
- labels / description 展示
- 手动输入路径时的详情联动(仅 `initialPath` 非空时拉取)

## 数据层

### `MovieInfo`(data/MovieInfoClient.kt)

增加字段:

```kotlin
data class MovieInfo(
    val fanCode: String,
    val title: String,
    val casts: List<String> = emptyList(),
    val cover: String? = null,   // 新增
    val year: Int? = null,       // 新增
)
```

`MovieDto.toInfo()` 已解析 `cover`/`year`,补上透传即可。

### `MovieInfoClient`

新增单部详情查询:

```kotlin
suspend fun findDetail(fanCode: String): MovieInfo?
```

- 请求 `GET <baseUrl>/api/movies/{fanCode}`(fanCode 做 URL 编码)
- 任何失败(baseUrl 为空、网络错误、HTTP 404 等)返回 `null`,不抛异常
- 复用现有 `MovieDto` 解析与 `cleanTitle()` 标题清洗逻辑

## 播放页(HomeScreen)

### 数据流

1. `AppRoot` 将已有的 `movieInfo: MovieInfoClient?` 传入 `HomeScreen`
2. `HomeScreen` 于 `LaunchedEffect(initialPath)` 中提取番号(`initialPath.trim('/')`),
   调用 `findDetail()`,结果存入 `detail: MovieInfo?` 状态
3. 失败/未配置 movie_api -> `detail = null` -> 卡片与海报均不显示,页面与现状一致
4. 手动修改路径输入框不触发重新拉取

### 详情卡片(方案 B)

布局:`Row`,左侧封面缩略图 96×135dp(`ContentScale.Crop`,`RoundedCornerShape(8.dp)`),
右侧 `Column`:标题(`titleMedium`,最多 2 行省略)、番号·年份(`bodySmall`,
`onSurfaceVariant`)、演员逗号分隔(`bodySmall`,最多 3 行省略)。

位置(用户选定"控制面板下方"):

- **竖屏**:`ControlsPanel` -> `MovieDetailCard` -> 播放器
- **横屏**:左列 `ControlsPanel` 下方

仅 `detail != null` 时渲染;封面加载失败时缩略图位置显示 `surfaceVariant` 色块。

### 播放器空闲海报(方案 A)

- `HomeScreen` 现有 `onPlaybackStateChanged` 监听基础上记录播放器状态,
  `isIdle = state == STATE_IDLE`
- `HlsPlayerView` 新增可选参数 `idleCoverUrl: String?`;非空且空闲时,
  在 PlayerView 之上、手势层(`PlayerGestureOverlay`)之下渲染封面:
  `fillMaxSize`、`ContentScale.Crop`、居中、约 50% 透明度、不消费触摸事件
- 状态变为 `BUFFERING`/`READY` 后 `idleCoverUrl` 传 `null`,海报隐藏
- 全屏分支同样传入该参数

## 依赖

新增 Coil:`io.coil-kt:coil-compose:2.7.0`(Kotlin 1.9.10 / Compose BOM 2024.02.00 兼容),
加入 `gradle/libs.versions.toml` 与 `app/build.gradle.kts`。

封面为外网 CDN 的 https 链接,不涉及 cleartext;
`network_security_config.xml` 不变。

## 已知限制(不在本次范围)

movie_api 若部署在 `http://内网IP` 地址,现有 cleartext 策略(仅放行 localhost)会
拦截 API 请求,列表页与播放页详情均不可用。此为已存在问题,本次不处理。

## 验证

- 无测试基础设施(项目无测试源集),以手工验证为主:
  1. 配置 movie_api 地址后从列表进入播放页:卡片显示 title/cast/cover,空闲海报出现
  2. 点"播放":海报在进入 BUFFERING 后消失,卡片持续显示
  3. 未配置 movie_api / 番号不存在:页面布局与现状一致,无报错
  4. 竖屏、横屏、全屏三种模式检查布局
