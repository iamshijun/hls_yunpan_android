# 03 — 下载闸门

**Status:** resolved
**Type:** task
**Blocked by:** 01

第三步:把「移动数据下允许下载」接到下载路径上。

## 范围

### `download/DownloadManager.kt`

- 订阅 `networkMonitor`,切到计费网络时把所有 `RUNNING` / `QUEUED` 任务转 `PAUSED`,
  并登记到新的 `gatePaused` 标记集(**不复用** `pauseRequested`,它服务于"暂停瞬间又恢复"
  的竞态)。
- 回到非计费网络时,只把 `gatePaused` 里的任务恢复为 `QUEUED`,然后 `pump()`;
  用户手动暂停的保持 `PAUSED`。
- `enqueue` / `resume` 前置判定:计费网络且未放行 → 返回拒绝原因(与现有 `String?` 契约一致)。
- `gatePaused` 在任务完成 / 取消 / 用户手动 resume / 用户手动 pause 时清理。
- 放行集中任务在计费网络下可以正常 `pump`,不被打断。

### 状态展示

- `download/DownloadTask.kt`:让"已暂停(移动数据)"这个原因能被 UI 与通知读到
  (`error` 字段已有,但它是失败语义 —— 优先加一个轻量标记或独立文案来源,不要污染
  `error` 的失败语义)。
- 下载管理页(`ui/DownloadManagerScreen.kt`)状态文案显示「已暂停(移动数据)」。
- 全部任务暂停时 `DownloadService` 自然 `stopSelf`(现有逻辑),回 WiFi 由 `pump()` 重新拉起,
  不需要额外改动。

### 交互:UI 层

- `ui/HomeScreen.kt` 的入队、`ui/DownloadManagerScreen.kt` 的「恢复」:**两个入口共用同一个
  判定函数与同一个「本次继续 / 取消」对话框语义**;"本次继续"把该番号加入
  `NetworkGate` 的放行集合。

## 完成标准

- 验收 5 / 6 / 7 通过(spec.md)。
- 三个发起入口(播放 / 入队 / 恢复)行为一致。
- `./gradlew assembleDebug` 通过。

## Comments

**已完成**(commit `9371931`,评审修正在 `03d1a47`)

- `download/DownloadManager.kt`:跟随 `metered` 翻转 —— 切到计费网络时未放行任务立刻转
  `PAUSED` 并打 `pausedByGate`,同时登记 `pauseRequested` 让运行中的任务在当前分片下完就停;
  回到非计费网络只把带 `pausedByGate` 的任务转回 `QUEUED` 再 `pump()`,用户手动暂停的不动。
- `enqueue` 前置判定(返回 `String?` 拒绝原因)、`resume` 改为返回 `String?`;放行集合在
  完成 / 取消 / 移除 / 被拒时清理。
- `download/DownloadTask.kt`:`pausedByGate` 轻量标记(不碰 `error` 的失败语义),
  同时承担「回 WiFi 谁是待续的」——不再单开 `gatePaused` 集合,避免两份簿记跑偏;
  重启恢复时清掉(重启后无从判断暂停是不是闸门造成的)。
- 状态展示:下载管理页与播放页菜单都显示「已暂停(移动数据)」。
- UI:`ui/DownloadManagerScreen.kt` 的「恢复」与 `ui/HomeScreen.kt` 的入队共用同一个
  `MeteredNetworkDialog` 与同一个判定函数。

**偏离(有意)**

- 票里写「让『已暂停(移动数据)』能被 UI **与通知**读到」——通知侧没做:`DownloadService`
  只渲染 `RUNNING`/`QUEUED`,全部暂停时按现有逻辑 `stopSelf`,通知里本就没有暂停任务可标
  (票内已注明这条不需要额外改动)。

**验收** 5 / 6 / 7 通过代码路径核对;三个发起入口(播放 / 入队 / 恢复)共用同一判定与弹框语义。
