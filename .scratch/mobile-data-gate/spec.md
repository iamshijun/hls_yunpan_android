# 移动数据闸门(播放 / 下载)

**Status:** resolved
**Origin:** 用户需求 —— 增加"移动数据下是否允许播放/下载"的设置,默认不允许。

## 问题

App 在移动数据下会无条件开始播放(列表点播/深链自动播放)和下载(入队即 pump),
用户可能在不知情的情况下消耗大量流量。当前代码库**完全没有** ConnectivityManager /
NetworkCallback 相关代码,连判定基础设施都要新建。

关键事实(已勘察):

- 播放取流路径:ExoPlayer → 本机代理 `127.0.0.1:<port>` → `HlsProxyHandler` →
  `HlsM3u8Handler` / `HlsChunkHandler` → 百度网盘。`HlsProxyHandler.handle` 是 m3u8 与
  `.ts` 的**唯一汇聚点**。
- 下载走 `DownloadManager`(3 并发、分片续传),`enqueue` 已有 `String?` 拒绝原因钩子,
  `pause` / `resume` / `retry` 已存在。
- 设置层是 DataStore 布尔开关;`AppContainer.backgroundPlayback: StateFlow<Boolean>` 是
  "改完立即生效"的现成样板。
- 元数据请求(列表、详情 API、封面)不走代理,直接由 UI/仓库层发起。

## 决策

| # | 决策 |
|---|---|
| 1 | 两个独立开关:「移动数据下允许播放」「移动数据下允许下载」,**默认都关闭** |
| 2 | 判定口径用 `ConnectivityManager.isActiveNetworkMetered`(含计费 WiFi、热点共享、漫游);UI 文案仍写"移动数据",说明里补全 |
| 3 | 两层闸门:UI 层负责交互(弹框/提示/自动暂停),代理层 + `DownloadManager` 负责兜底强制 |
| 4 | 播放中切到移动数据 → 自动暂停 + 前台 Snackbar,回到非计费网络自动续播 |
| 5 | 只有"闸门暂停"的才自动续播,用户手动暂停的不动(需与 `DownloadManager.pauseRequested` 语义隔离) |
| 6 | 被拦时弹「本次继续 / 取消」,"本次继续"只对当前播放会话 / 当前下载任务有效;**不做**"以后都允许" |
| 7 | 切到移动数据 → RUNNING/QUEUED 下载全部转 PAUSED 并打"闸门暂停"标记;回 WiFi 只恢复带标记的;下载页文案显示"已暂停(移动数据)" |
| 8 | 元数据请求(列表、详情 API、封面)**不拦**,只拦播放取流和视频下载 |
| 9 | `core/NetworkMonitor.kt`:interface + ConnectivityManager 实现,单例在 `AppContainer`,以 `StateFlow` 暴露,注入代理图与 `DownloadManager` |
| 10 | 放行令牌是**纯内存**态:进程级"播放会话放行"布尔 + "已放行下载任务"集合;离开播放页清播放放行,任务完成/取消清对应下载放行;**绝不落盘** |
| 11 | 设置页位置:紧接「自动播放」之后;底部"立即生效"提示补上这两个开关;不做升级提示 |
| 12 | 兜底命中 → 代理返 `403` + 明文原因,`PlaybackController` 映射为「已暂停:当前为移动数据」 |
| 13 | 被闸门暂停时**不**接管通知;Media3 通知自然呈现暂停态,用户点播放走"本次继续"弹框 |
| 14 | 自动播放路径(列表点播/深链)在移动数据下**不弹框**、不 prepare,停在封面并显示常驻提示「当前为移动数据,未自动播放」;手动点播放才弹框 |
| 15 | 三个发起入口(播放页 enqueue / 下载页 resume / 播放)共用同一判定函数与同一弹框语义 |

## 实现细节(已定,不必再问)

- 放行集合与 `DownloadManager.pauseRequested` 隔离:新增独立的 `gatePaused` 标记集,
  不改动 `pauseRequested` 既有语义(它服务于"暂停瞬间又恢复"的竞态)。
- `SettingsScreen.onSave` 用参数追加,不改成传 `AppConfig`(改动面最小)。
- 无网络 / 无法判定计量属性时按"非计费"处理,不拦截 —— 播放自然会失败,闸门不背这个锅。
- 代理层兜底拦在 `HlsProxyHandler.handle` 的分发之前(一处覆盖 m3u8 + `.ts`),
  而不是在两个 handler 里各写一遍。

## 验收

1. 默认设置下,移动数据中点播放 → 弹「本次继续 / 取消」;取消后不产生任何网盘取流。
2. 移动数据下从列表/深链进入播放页 → 不自动播放,停在封面并显示提示;点播放按钮才弹框。
3. WiFi 播放中切到移动数据 → 播放暂停并提示;切回 WiFi → 自动续播。
4. 用户手动暂停后切到 WiFi → **不**自动续播。
5. 移动数据中点下载 → 弹框;取消后不入队。下载页点「恢复」→ 同样弹框。
6. 下载进行中切到移动数据 → 任务转 PAUSED 且标注"已暂停(移动数据)";切回 WiFi → 自动继续。
7. 用户手动暂停的任务,切到 WiFi 后保持 PAUSED。
8. 两个开关打开后,移动数据下播放/下载行为与旧版本一致。
9. 影片列表、详情、封面在移动数据下正常加载。

## 不做(明确排除)

- 自定义 MediaSessionService 通知 provider(Q13)。
- 元数据请求的流量限制(Q8)。
- "以后都允许"式的第二套持久放行状态(Q6)。
- 自动化测试(仓库无测试源集;`NetworkMonitor` 留 interface 口子即可)。
