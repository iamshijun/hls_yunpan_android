# 下载吞吐、限速规律与失败重试

> 一次真机排查的记录（2026-11-15）与落地后的规则。**改下载器之前先读这一篇**；
> 只想知道「日志怎么读」跳到 [§5](#5-怎么自证--怎么看日志)。

## 0. 一句话结论

慢的根源不是「账号被整号限速」，而是百度网盘**按对象大小分两条通道**：`< 2MiB` 的对象不限速（单连接 1.1~1.4 MB/s，随并发叠加），`≥ 2MiB` 的对象被**账号级**限到几十 KB/s，加并发只会摊薄每一条。
App 侧原本「每片一次换直链 + 单连接串行」把这部分差距又放大了一倍多：本该跑 1 MB/s 的小分片被压到 ~350 KB/s。

## 1. 结论速查

| 现象 | 真正原因 | 现在的处理 | 代码位置 |
|---|---|---|---|
| 下载总速度只有几百 KB/s | 每片一次 `filemetas`（0.45~0.8s）+ 单连接串行 | 批量换直链 + 预取窗口 + 双车道并行 | `BaiduYunClient.prefetchDownloadUrls`、`SegmentFetcher`、`DownloadManager.downloadAll` |
| 某些片子慢得离谱（几小时） | 该番号里 `≥ 2MiB` 的分片占比高 | 无解（服务端），只保证不浪费其它带宽 | — 见 [§6](#6-影响面哪些片子会慢) |
| `java.net.ProtocolException: unexpected end of stream` | CDN 主动断连接；OkHttp 故意不重试 `ProtocolException` | 判为「连接级」失败：不作废直链、50ms 后换连接重来，额外 5 次机会 | `DownloadManager.fetchSegment` + `isTransientConnectionError` |
| 分片偶尔少一截但被当成成功 | 读到 EOF 就当读完，没核对 `Content-Length` | 与 `Content-Length` 不符即 `TruncatedBodyException`，**绝不落盘** | `DownloadManager.downloadSegment` |
| 「等响应头」1.5s、偶发被断 | 连接数过多（原先最多 21 条） | 全局 10 条连接预算 | `DownloadManager.segmentConnections` |

## 2. 实测：两条通道，阈值正好 2 MiB

同一账号、同一部片（`nacr-849`）、同一条 WiFi，手机与 Mac 结论一致。

紧贴阈值的两个分片（同一部片子，索引相邻）：

| 分片 | 字节数 | 结果 |
|---|---|---|
| `seg_000371` | 2,096,952 | **1449 KB/s** |
| `seg_001541` | 2,098,268 | **74 KB/s** |

差 1316 字节，速度差 20 倍。该片 1811 个分片里，`≥ 2MiB` 的 450 个全部慢、`< 2MiB` 的 1361 个全部快，**零例外**。

并发扩容（同一批数据、每次 16 片）：

| 并发 | 小片（< 2MiB）| 大片（≥ 2MiB）|
|---|---|---|
| 1 | 1149 KB/s | 39~85 KB/s（随时间恶化，见下）|
| 3 | — | 56 KB/s |
| 4 | 3610 KB/s | 139 KB/s |
| 8 | — | 174 KB/s |
| 10 | 5870 KB/s | 94 KB/s |
| 16 | 4190 KB/s | 91 KB/s |

读数：**小片随并发线性叠加到 ~5.8 MB/s 饱和；大片不管几条连接，聚合就锁在 ~50~175 KB/s**，而且每条会更慢（10 条时每条只有 9.4 KB/s）。

### 大片那一档会随时间恶化

同一天同一台机器、同一账号：

| 时间 | 单连接 | 备注 |
|---|---|---|
| 17:00 | 76~85 KB/s | 4 并发聚合 139 KB/s |
| 17:35 | 39 KB/s | 3 并发聚合 56 KB/s |
| 17:52 | 15~26 KB/s | 10 并发聚合 94 KB/s |

结论：不是固定阈值，是**账号级的软限速，会随当日用量收紧**。所以「改完能快几倍」的承诺只对 `< 2MiB` 那部分成立。

### 已排除的怀疑（都实测过）

| 怀疑 | 实测 | 结论 |
|---|---|---|
| 账号整体限速 | 同一个 token 下小片 1.1~1.4 MB/s | ✗ |
| 手机网络/WiFi | Mac（同出口）下同一个慢片也是 80 KB/s；1201 Mbps / RSSI −66 | ✗ |
| CDN 节点差异 | 同一慢片换 3 个节点 IP，仍 33~83 KB/s | ✗ |
| IPv4 vs IPv6 | 44（v4）/ 60（v6）KB/s，都慢 | ✗ |
| 换 API 端点 | `d.pcs.baidu.com` dlink 与 `/rest/2.0/pcs/file?method=download` 都是 80 KB/s | ✗ |
| 单请求字节数（分块） | 对 2.19 MB 对象发 1 MiB Range：79 KB/s | ✗ |
| 同一对象并发抢带宽 | 8 条连接下同一对象聚合 97 KB/s | ✗ |
| 客户端 HTTP 栈 | 系统 `curl` 与 App 完全复现 | ✗ |
| TCP 丢包 | 重传率 0.9%，60s 内 45 次超时（有波动，但不是主因）| ✗ |

### `filemetas` 的 RTT（可以摊薄）

| 一次请求带几个 fsid | 耗时 |
|---|---|
| 1 | 450~860 ms |
| 24 | 490 ms |
| 100 | 520~680 ms |
| 120 | 620 ms |

即「换直链」的成本与批量大小几乎无关，可以一批 100 个。开启前每片都付这 0.45~0.8s，1575 片就是十几分钟纯等待。

## 3. App 侧原本的放大项

| 项 | 改前 | 改后 |
|---|---|---|
| 每片换直链 | 429~751 ms/片 | **0~1 ms**（批量预取命中缓存）|
| 分片并发 | 1（严格串行） | 小片 4 条 / 大片 3 条车道，全局上限 10 条 |
| 小分片单连接均速 | ~350~400 KB/s（被 RTT 拖）| 1.0~1.7 MB/s |
| 失败重试 | 任何失败都作废直链 + 1s/2s 退避 | 连接级失败不动直链、50ms 重连 |
| 截断的响应体 | 静默写入 `.part`（坏文件）| 核对 `Content-Length`，不符即重试 |

## 4. 落地后的设计

```
DownloadManager.downloadAll
├── resolveRemotes()            目录列表一次 → 分片名 → (fsid, size)
├── prefetchAhead()             往前 240 片窗口 / 攒够 24 条 → 一次 filemetas
├── SegmentFetcher              双车道:认领 → 下载 → 交结果
│   ├── 小片车道 × 4            (≤ 2MiB, 实测 4 条已到聚合上限)
│   └── 大片车道 × 3            (> 2MiB, 账号级限速, 再多只是摊薄)
└── 顺序落盘                    只把「连续前缀」追加进 .part
```

三条不变量：

1. **顺序不变**：`.part` 只追加连续前缀，`completedSegments` 逐片持久化 → 续传语义与改前一致。
2. **不写坏数据**：`Content-Length` 不符或读空闲超时（60s）都当失败重试，绝不落盘。
3. **两条车道各有独立槽位**：某条车道占满不会饿死另一条车道的队头（否则会死锁）。

失败分流（`fetchSegment`）：

| 类型 | 判据 | 处理 |
|---|---|---|
| 连接级 | `ProtocolException` / `EOFException` / `SocketException` / `ClosedChannelException` / `TruncatedBodyException` | 不作废直链；`delay(50ms)`；额外 5 次机会 |
| 直链级 | 其它（403、直链为空、业务 errno）| 作废直链重新换；1s/2s 退避；共 3 次 |

为什么连接级要单独分流：OkHttp 把 `ProtocolException` 归为「不可恢复」而**拒绝重试**，所以这层兜底只能自己做；而直链是好的，作废它等于白花一次 `filemetas` 的 RTT（0.5s）。

## 5. 怎么自证／怎么看日志

```bash
adb logcat -s DownloadManager:I BaiduYunClient:D
```

三类关键行：

```
开始下载 [nacr-849] 共 1811 片(已处理 769);本次 1042 片:小 774 / 大 268 (阈值 2MiB),车道 小=4 大=3
分片 [nacr-849] #771 seg_000771 1967044B 换直链=0ms 响应头=1187ms 首字节=1189ms 单片=1496ms 传输 6216KB/s 有效 1284KB/s
耗时统计 [nacr-849] 20/1811 片, 32.1 MB, 墙钟均速 187 KB/s | 均:换直链 0 ms / 等响应头 1191 ms / 等首字节 1206 ms / 单片 20352 ms, 重试 0 次(连接级 0) | 小 15 片 21.4 MB 单片均速 1025 KB/s, 大 5 片 10.7 MB 单片均速 28 KB/s
```

判读表：

| 看到什么 | 说明 |
|---|---|
| `换直链` 不是 0~2ms | 预取窗口没生效或被作废（看 `BaiduYunClient` 的 `filemetas N 个 fsid` 行）|
| `小 … 单片均速` < 800 KB/s | App 侧还有问题（正常 1.0~1.7 MB/s）|
| `大 … 单片均速` 几十 KB/s | 服务端限速，正常现象，不是 bug |
| `等响应头` > 1.5s | 连接数/排队问题（全局预算 10 条是否被绕开？）|
| `重试 N 次(连接级 M)` 大，但任务仍在推进 | CDN 在掐连接，已被兜住 |
| `单片` 时间 ≈ 80~170s 且是小分片 | 异常，看是不是被大片车道串行化 |

**落盘正确性校验**（任何一次改动下载器后都该跑一遍）：

```bash
# .part 的字节数必须等于 completedSegments 里那些分片的尺寸之和，且索引无空洞
adb shell "cat /sdcard/Android/data/xyz.asitanokibou.player/files/downloads/<番号>/task.json"
adb shell "ls -l /sdcard/Android/data/xyz.asitanokibou.player/files/downloads/<番号>/media.ts.part"
```

## 6. 影响面：哪些片子会慢

快慢取决于**该番号里 `≥ 2MiB` 分片的字节占比**（2026-11-15 实测库存）：

| 番号 | 分片数 | < 2MiB | ≥ 2MiB |
|---|---|---|---|
| gvh-874 | 1840 | 1840 片 / 1.47 GB | 0 片 / 0 |
| cawd-298 | 1873 | 1854 片 / 1.71 GB | 19 片 / 0.04 GB |
| nacr-849 | 1811 | 1361 片 / 2.22 GB | 450 片 / 0.97 GB |
| blor-252 | 1811 | 1243 片 / 2.12 GB | 568 片 / 1.23 GB |
| jur-798 | 2030 | 1188 片 / 1.92 GB | 842 片 / 1.92 GB |

观察到的样本：`cawd-298`（几乎全是小片）1.87 GB 用 **13 分钟** 完成（约 2.3 MB/s 平均）；`nacr-849` 处在连续大片段落时，单片 80~90s、墙钟 187 KB/s。

## 7. 已知硬墙与没做的事

**硬墙**

- `≥ 2MiB` 对象的账号级限速：客户端无解（Range、并发、换端点、换 IP 族、换客户端都试过）。
- 唯一的真杠杆在**转码侧**：把 HLS 分片稳定控制在 `< 2MiB`，就是 20~50 倍的差距（`gvh-874` 是现成样本）。已存在的片子改不了。

**没做（可选后续）**

- 播放路径（`HlsChunkHandler`）只加了时间日志，没有双车道 —— 播放由 ExoPlayer 自己的预读管线驱动，实测够用。
- 缓冲仍是内存（每条车道 `ahead=2`，峰值约 20 MB）；若把 `ahead` 调大，应改成先落临时文件再按序拼接（当前没必要：大片才是关键路径，小片车道本来就有富余）。
- 汇总行没有按类的 ETA（改前的 ETA 用混合平均，会给出离谱的「剩余 777 小时」，已删掉）。

## 8. 复现测量（不含 token 的最小方法）

```bash
# 1) 批量换直链：一次带 100 个 fsid，仍是 ~0.5s
curl -s -G 'https://pan.baidu.com/rest/2.0/xpan/multimedia' \
  -A '<WEB_UA>' --data-urlencode "access_token=$TOKEN" \
  --data-urlencode 'method=filemetas' --data-urlencode 'dlink=1' \
  --data-urlencode "fsids=[$F1,$F2,...]"

# 2) 单对象测速（跟随 302；注意 UA 必须是 pan.baidu.com）
curl -sL -o /dev/null -A 'pan.baidu.com' \
  -w '%{size_download}B %{time_total}s %{speed_download}B/s ip=%{remote_ip}\n' "$DLINK&access_token=$TOKEN"

# 3) 判断是不是「按大小」而不是「按文件」：取该番号里紧贴 2 MiB 的两片各测一次
```

注意：`filemetas` 返回的 dlink 是 JSON 字符串，`&` 被转义成 `\u0026`，手工解析时要还原。

## 9. 与既有规划的关系

`docs/plans/hls-download.md` 的决策 #11（「分片严格顺序下载」「分片失败 1s/2s/4s 退避」）已被本文的改动取代：顺序**落盘**不变，但**下载**改为并行 + 双车道，重试按失败类型分流。其余决策（存储位置、`.part` 追加写、分片粒度续传、MediaStore 原子发布）不变。
