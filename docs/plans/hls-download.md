# HLS 下载功能规划

> 状态:已决策完毕,本文件是唯一规划来源。相关决策由 wayfinder 会话敲定(见 git 历史)。

## 目的地

播放页新增「下载」入口,将目标番号的全部 HLS 分片按顺序下载并合并为单个媒体文件;新增「下载管理」页面,支持查看进行中/已完成任务、暂停/恢复/取消、删除已完成文件。

## 决策记录

| # | 决策点 | 结论 |
|---|---|---|
| 1 | 存储位置 | MediaStore `Movies/HlsPan/`,无需存储权限 |
| 2 | 容器格式 | 分片直接顺序拼接为 `<番号>.ts`(MPEG-TS),零转码 |
| 3 | 下载引擎 | 独立下载器,复用 `BaiduClient.openDownloadStream` + `YunIndex`(分片名解析),不经本地代理 |
| 4 | 生命周期 | 独立前台 `DownloadService` + 通知栏进度,与 `PlaybackService` 分离 |
| 5 | 断点续传 | 分片粒度:任务文件记录已完成分片索引,重启跳过 |
| 6 | 临时文件 | 私有目录 `.part` 追加写;完成后经 MediaStore `IS_PENDING` 原子发布 |
| 7 | 速度展示 | 每任务显示实时下载速度(字节/秒,指数滑动平均) |
| 8 | 重复下载 | 拒绝:已存在提示「已下载」;进行中提示进度入口;重下需先删 |
| 9 | 管理页入口 | 影片列表页 toolbar;进行中/已完成两 section;进行中支持暂停/恢复/取消,已完成仅删除 |
| 10 | 状态存储 | 每任务一个 JSON 文件(元数据+分片进度),与 `.part` 同目录,启动扫描恢复;不引 Room |
| 11 | 重试/并发 | 分片失败自动重试 3 次(1s/2s/4s 退避)后任务 FAILED 可手动重试;任务串行队列(同时 1 个);分片严格顺序下载 |
| 12 | 播放/下载冲突 | 不处理,双流并行 |
| 13 | 本地播放 | 不做,完成项仅查看/删除 |

## 模块划分

```
download/
├── DownloadTask.kt        # 任务模型 + 状态机 + JSON 持久化(task.json)
├── DownloadManager.kt     # 单例调度器:串行队列、任务生命周期、通知、MediaStore 发布
├── MediaStoreWriter.kt    # MediaStore Movies/HlsPan/ 写入与删除(DownloadedStore)
└── DownloadService.kt     # 前台 service,只做宿主与通知;队列逻辑在 DownloadManager
ui/
├── DownloadManagerScreen.kt  # 下载管理页(进行中/已完成)
└── MovieDetailCard.kt 等     # 播放页加「下载」按钮
```

- `HlsSegmentList`:拉取 m3u8 → 解析出顺序分片名列表(复用重写逻辑)。
- 分片 fsid 解析:直接 `BaiduClient.getFileListAll(dir)` 一次拿全目录(含 fsid),不走 YunIndex(那是给代理的,按需惰性加载;下载需要全表)。

## 关键实现细节

### 分片解析
m3u8 为单层 media playlist,分片行为相对文件名。解析:非 `#` 开头、非空行 = 分片,顺序即列表序。分片 fsid 通过目录列表(`BaiduFile.path` 精确匹配)解析。

### 任务文件(task.json)布局
```
<filesDir>/downloads/<fanCode>/
├── task.json     # 元数据 + 已完成分片索引(JSON 数组)
└── media.ts.part # 追加式分片拼接
```
task.json 与 .part 同目录;删除任务 = 删目录。启动时 DownloadManager 扫描 downloads/ 目录重建队列(FAILED/PAUSED 保留,COMPLETED 的目录清理)。

### 合并发布流程
1. 顺序下载分片 → 追加写 `media.ts.part`
2. 全部完成 → `MediaStore IS_PENDING=1` 条目,复制内容,`IS_PENDING=0` 原子发布为 `Movies/HlsPan/<番号>.ts`
3. 删除私有工作目录

### UI
- 播放页 ControlsPanel:「下载」按钮 + 进行中进度(百分比),点击可跳下载管理页
- MovieListScreen 顶栏:「下载管理」入口
- DownloadManagerScreen:进行中列表(番号、进度、速度、暂停/恢复、取消、失败重试)+ 已完成列表(MediaStore 查询 Movies/HlsPan/,显示番号/大小/时间、删除)

### Manifest
DownloadService 声明 `foregroundServiceType="dataSync"`,权限 `FOREGROUND_SERVICE_DATA_SYNC`。
