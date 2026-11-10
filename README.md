# hls_pan_player

把百度网盘里的影片目录当影片库来用的 Android 播放器:影片库在网盘,播放经手机上的本地 HLS 代理,观看进度、稍后再看、下载文件都留在本机。

- 系统要求:Android 11(minSdk 30)及以上
- 界面:Jetpack Compose(Material 3)
- 播放:Media3 ExoPlayer(HLS) + MediaSession 前台服务
- 本地代理:Ktor CIO Server,只监听 `127.0.0.1`

---

## 一、核心:HLS 代理是怎么工作的

网盘上的视频是"一个播放列表 + 一堆 `.ts` 分片"。ExoPlayer 并不直接访问百度,所有取流请求都指向手机自己的一个本地 HTTP 服务,由它去网盘取数据再转回来。这样做的好处:

- 百度下载直链需要 `access_token`、需要特定的 User-Agent、还会过期和限速。把这些麻烦全部关在代理里,播放器只看见一个普通的 m3u8 地址。
- 播放列表里的相对路径可以在代理里一次性改写,播放器不需要了解网盘目录结构。
- 错误码翻译、fsid 缓存这类策略有唯一落点,不必散落到 UI 和播放器里。

### 播放链路

```
用户输入番号(目录名)
      │
      ▼
PlaybackService 拼出 http://127.0.0.1:<port>/hls/<番号>/playlist.m3u8
      │
      ▼
ExoPlayer 请求 .m3u8 ──► ProxyServer(Ktor CIO,127.0.0.1)
                              │
                              ├─ /health          健康检查
                              └─ /hls/{path...}   HlsProxyHandler
                                     │
                    ┌────────────────┴────────────────┐
                    ▼                                 ▼
             HlsM3u8Handler                     HlsChunkHandler
          (playlist.m3u8)                      (*.ts 分片)
                    │                                 │
                    └──────────► YunIndex ◄───────────┘
                          路径 → fsid 解析(内存缓存 + TTL + 每目录锁)
                                     │
                                     ▼
                        BaiduClient / BaiduYunClient
                   (filemetas 换 dlink → 带 pan.baidu.com UA 下载)
```

### 代理内部各组件

| 组件 | 文件 | 做什么 |
|---|---|---|
| `ProxyServer` | `proxy/ProxyServer.kt` | 启动/停止 Ktor CIO 服务,只绑 `127.0.0.1`;端口 0 时自动挑空闲端口,也可在配置里固定。暴露 `/health` 与 `/hls/{path...}` 两个路由。 |
| `HlsProxyHandler` | `proxy/HlsProxyHandler.kt` | 路由分发:`.m3u8` 交给播放列表处理器,`.ts` 交给分片处理器,无扩展名时按请求头 `Accept` 判断。 |
| `HlsM3u8Handler` | `proxy/HlsM3u8Handler.kt` | 取到播放列表原始内容 → 交给 `M3u8Rewriter` 改写 → 以 `application/vnd.apple.mpegurl` 返回(可缓存 1 小时)。 |
| `HlsChunkHandler` | `proxy/HlsChunkHandler.kt` | 按 fsid 打开百度下载流,64 KB 缓冲**边读边写**给播放器,不整包落内存、不写磁盘,也不做分片缓存。响应头 `no-cache`。 |
| `M3u8Rewriter` | `proxy/M3u8Rewriter.kt` | 把播放列表里的相对分片路径改写为"基于当前请求目录的根相对路径",例如 `/hls/video1/playlist.m3u8` 里的 `segment_0001` 改成 `/hls/video1/segment_0001`;`http(s)` 绝对地址保持不变,`#` 开头的标签行原样保留。 |
| `YunIndex` | `proxy/YunIndex.kt` | 代理的"目录索引":把 `/hls/<番号>/<文件>` 映射成 `/apps/<BAIDU_APP_NAME>/movies/<番号>/<文件>`,再解析出百度 `fsid`。缓存是内存里的"路径 → fsid",条目带 TTL(默认 1 小时),同一目录并发加载用互斥锁串行化,避免重复请求网盘。缓存未命中(或已过期)才去加载所属目录,所以 m3u8 与分片走的是同一套解析逻辑。 |
| `BaiduYunClient` | `baidu/BaiduYunClient.kt` | 百度网盘 REST 客户端:列目录(浏览器 UA)、`filemetas` 换下载直链、下载时必须带 `pan.baidu.com` UA 和 `access_token`;同时负责删除、重命名等文件管理操作。 |
| `ProxyErrors` | `core/ProxyErrors.kt` | 统一错误映射:未配置 token → 401,百度业务错误 → 502,其他 → 500。播放器只凭状态码就能区分"没填 token / 网盘报错 / 本地出问题"。 |

### 一个必须知道的约定

代理把请求路径映射到固定的网盘根目录:

```
/apps/<BAIDU_APP_NAME>/movies/<番号>/playlist.m3u8
```

`BAIDU_APP_NAME` 在 `app/build.gradle.kts` 里配置(当前为 `asitanokibou`),必须与你在百度网盘开放平台注册的应用名一致。改动它需要重新构建。

---

## 二、功能一览

### 影片库浏览

- **从列表选择**:按时间/名称/大小排序,分页加载(每页 20 个,滚到底自动续),下拉刷新,左滑露出删除按钮(移入网盘回收站,有确认框)。
- **从网页浏览**:内嵌 WebView 打开"影片信息服务"首页,浏览、筛选、点片子都在网页里完成;网页里的 `hlspan://` 链接会在应用内直接进入播放页,返回时回到原来的网页(不会重新加载)。
- **直接输入媒体名**:手输番号直接播放,适合分享链接或临时看片。
- 页面上有"稍后再看""下载管理""设置"三个入口。

### 播放

- 播放列表、番号、封面、演员、年份来自影片信息服务;服务不可用时静默降级,只显示番号,不影响播放。
- **自动播放**可关:关闭后进入播放页停在封面,等用户手动点播放。
- **续播**:按番号记住观看位置,再进来自动接着播。
- **后台播放**:默认开启;关闭后退出应用即暂停。
- **播放页手势**:横滑快进/快退(拖动只预览,松手才跳)、左半屏竖滑调亮度、右半屏竖滑调音量、单击显示/隐藏控制栏、长按 2 倍速。双击行为可在设置里切换为"播放/暂停"或"左/右半屏快退/快进 10 秒"。
- **全屏**:竖屏/横屏切换,系统栏隐藏;全屏右侧有截图按钮,截取的是解码后的实际画面,存到相册 `Pictures/Player`。
- 封面可点开整屏大图;演员名可点,跳去网页按该演员筛选。
- 通知栏媒体控制(播放/暂停/进度),音频焦点、耳机拔出自动暂停。

### 观看进度

- 播放中定时采集位置(播放器侧采集,退到后台仍然有效),以番号为主键保存在本机,最多 50 条。
- 不足 30 秒的位置不记;看到剩余不到 2 分钟或已过 95% 视为看完,该条记录自动清除,下次从头播。

### 下载

把某个番号的全部分片在本地拼成一个 `.ts` 文件,存进系统媒体库,方便离线播放或交给别的播放器。

- 队列最多同时下载 3 个任务,其余排队,空出槽位自动补位。
- 分片顺序下载,失败自动重试 3 次(1s / 2s / 4s 退避)。
- **分片粒度续传**:每下完一片就把进度原子写回 `task.json`,暂停、被杀进程、切换网络后都能接着下。
- **缺片处理**:某片在网盘上不存在时任务停下,用户可选择"跳过分片"(画面会有一段不连续)或"重试"。
- 完成时以 `IS_PENDING` 原子方式发布到 `Movies/HlsPan/<番号>.ts`,媒体库里不会出现半成品;下载管理页可直接调系统播放器打开,也能删除。
- 有任务在跑时保持一个前台服务与进度通知,队列空了自动退出。

### 稍后再看

- 播放页菜单里一键加入/取消,列表页按加入时间倒序展示,标题和封面按番号实时向影片信息服务查询(不需要收藏时才拉到详情)。
- 只保存番号,换设备或网盘里有同名目录就能继续看。

### 分享与深链

- 深链格式:`hlspan://play/<番号>`。浏览器、聊天工具、应用内网页都能直接调起播放页。
- 播放页"分享"优先给出 `https://<影片信息服务>/movies/play.html?path=<番号>`:任何设备都能打开,Android 上会自动尝试调起本应用,调不起来时还能在网页里播。未配置影片信息服务地址时回退成 `hlspan://` 原始深链(只在本机有效)。

### 平板横屏分屏

宽大于高且短边 ≥ 600dp 的设备上,播放页会占右半屏,左侧保留你刚才浏览的列表/网页页面(不重建、不重载),可以直接点下一条继续看。

---

## 三、模块说明

| 目录 | 职责 |
|---|---|
| `proxy/` | **核心**。本地 HLS 代理:HTTP 服务、请求分发、m3u8 改写、分片流式转发、路径 → fsid 索引。见上文第一节。 |
| `baidu/` | 百度网盘 API 客户端接口与实现(`BaiduClient` / `BaiduYunClient`)、异常与数据模型。 |
| `service/` | `PlaybackService`:`MediaSessionService`,持有 ExoPlayer 与代理实例(代理随服务启动/停止),负责媒体会话、后台播放策略、观看进度采集。`DownloadService`:下载的前台宿主与进度通知,本身不含下载逻辑。 |
| `download/` | `DownloadManager`(并发队列、重试、缺片、续传)、`DownloadTask`(状态机与磁盘持久化)、`DownloadedStore`(MediaStore 发布/查询/删除)。 |
| `data/` | `MovieRepository`:影片库列表与详情的统一入口;`MovieInfoClient`:影片信息服务(`/api/movies`)客户端,失败一律静默降级。 |
| `config/` | `AppSettings`:DataStore 持久化的全部设置项,以及默认值。 |
| `core/` | 跨模块小工具:`HlsPaths`(网盘路径与提示 URL 约定)、`DeepLink`(深链编解码)、`MovieWebUrls`(影片网页地址)、`ProxyErrors`(错误码映射)。 |
| `progress/` | `PlayProgressStore`:观看进度与续播点;`PlaybackServiceOnly` 用编译期注解把"位置写入"限制在播放服务内。 |
| `watchlater/` | `WatchLaterStore`:稍后再看列表的本地持久化。 |
| `di/` | `AppContainer`:进程级单例装配(设置、客户端、仓库、下载器);`ProxyGraph`:一次代理运行实例(服务 + 索引 + 端口)的句柄。 |
| `ui/` | Compose 界面:首页、影片列表、播放页、设置、下载管理、稍后再看、影片网页(WebView)、播放器手势与浮层、截图、导航栈(`ui/navigation`)。`PlaybackController` 是 UI 与 MediaController 之间的应用侧接缝,UI 只读状态,不脚本化播放器。 |
| `tools/` | 维护用的 Python 脚本(统计影片体积、巡检网盘与影片服务记录缺失),不参与 App 构建。 |

---

## 四、准备与运行

1. 用 Android Studio 打开本仓库根目录(不是子目录),等待 Gradle Sync 完成。
2. 连接真机或模拟器,`./gradlew installDebug`(或直接在 IDE 运行 `app`)。
3. 首次启动授予通知权限(Android 13+ 需要,否则前台播放/下载通知不显示)。
4. 进入「设置」填写百度网盘 `access_token`(保存即生效,换 token 不必重启)。
5. 确认网盘上已有符合约定的目录结构:

   ```
   /apps/asitanokibou/movies/
   └── <番号>/
       ├── playlist.m3u8
       ├── segment_0001.ts
       └── ...
   ```

6. 回到首页,用列表 / 网页 / 直接输入任一方式进入播放。

「影片信息服务地址」是可选项:填了才有标题、封面、演员、演员筛选、网页浏览与 https 分享链接;不填只影响这些展示,播放本身照常。

---

## 五、设置项

| 设置 | 默认 | 说明 |
|---|---|---|
| 百度网盘 access_token | 空 | 网盘开放平台的 access_token;过期后重新填一次即可 |
| 影片信息服务地址 |  | 详情、封面、网页入口、分享链接都挂在这个地址下 |
| 后台播放 | 开 | 关闭后离开应用即暂停 |
| 双击快进/后退 | 关 | 关闭时双击为播放/暂停;打开后左/右半屏双击快退/快进 10 秒 |
| 自动播放 | 开 | 关闭后进入播放页停在封面,等手动点播放 |
| 播放记录 | — | 显示条数,可一键清除 |
| 清除缓存 | — | 立即清空 fsid 内存缓存与播放记录,下一次请求按需重新拉取 |

两处不在界面里、改了需要重新构建或重启进程的配置:`BAIDU_APP_NAME`(网盘根目录前缀,`app/build.gradle.kts`)与 fsid 缓存 TTL / 代理端口(代码默认 1 小时 / 自动选端口,在 `PlaybackService` 启动时快照)。

---

## 六、构建与发布

```bash
./gradlew assembleDebug    # 调试包
./gradlew installDebug     # 装到已连接设备
./gradlew assembleRelease  # 发布包
```

工具链:Gradle 8.6、AGP 8.1.4、Kotlin 1.9.10、Compose BOM 2024.02.00、Media3 1.3.1、Ktor 2.3.13。

发布签名是**可选**的:项目根目录存在 `keystore.properties`(本地,已 git-ignore),或环境变量 `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD` 齐备时,才会生成带签名的 release APK;否则 `assembleRelease` 产出的是未签名包(不能直接安装)。

GitHub Actions(`.github/workflows/release.yml`)只在推送 `v*` 标签时触发,把 APK 上传到对应的 Release:配了签名 secrets 就传签名包,没配则退回传 debug 包。

调试日志的 tag 都用类名,例如:

```bash
adb shell logcat -s ProxyServer:HlsProxyHandler:HlsM3u8Handler:HlsChunkHandler:YunIndex:BaiduYunClient:PlaybackService
```

---

## 七、已知限制

- fsid 缓存只有内存一份(没有 Redis / 跨设备共享);进程重启后第一次取流会重新拉目录。
- `access_token` 不会自动刷新,只在百度返回错误时暴露;需要手动去设置里更新。
- 播放列表改写只处理裸 URI 行(分片、子播放列表),`#EXT-X-KEY` / `#EXT-X-MAP` 里的 URI 不会改写,因此加密片源或 fMP4(需要 init 分片)的片源目前不支持。
- 代理随播放服务启动/停止:服务被系统回收后,「清除缓存」暂时无可清理的对象,下次播放时重新建立。
- 下载是把分片字节原样拼成一个 `.ts`,不做转码或 MP4 封装;合并后的文件依赖分片本身的连续性,跳过缺片会有画面跳变。
- 没有自动化测试,回归依赖手动验证。


