# 01 — 设置项 + NetworkMonitor 接缝

**Status:** resolved
**Type:** task
**Blocked by:** —

第一步。只建立判定基础设施与两个设置开关,**不接任何闸门行为**;做完这一步应用行为与现在完全一致。

## 范围

### 新增 `core/NetworkMonitor.kt`

- `interface NetworkMonitor { val isMetered: StateFlow<Boolean> }`(或在同一个文件里给出
  `data class NetworkState(val metered: Boolean)`,以 `StateFlow<NetworkState>` 暴露 ——
  选一种,保持单文件、单职责)。
- `ConnectivityNetworkMonitor(context)`:用 `registerDefaultNetworkCallback` 跟随默认网络
  变化更新 StateFlow;初值取 `isActiveNetworkMetered`。
- 无网络 / 无法判定时按**非计费**处理。回调要处理 `onLost` 后无默认网络的情形。
- 留 interface 口子即可,不写测试。

### 新增 `core/NetworkGate.kt`

- 判定函数:`fun canUseMeteredPlayback(): Boolean` / `fun canUseMeteredDownload(): Boolean`
  形态的**唯一入口**(供 UI、`DownloadManager`、代理层共用)。
- 放行令牌(纯内存):
  - 播放会话放行:`var meteredPlaybackAllowed: Boolean`,离开播放页时清。
  - 已放行下载任务:`Set<fanCode>`,任务完成/取消时移除。
  - 绝不写 DataStore。

### 改 `config/AppSettings.kt`

- `AppConfig` 加 `allowPlaybackOnMetered: Boolean = false`、`allowDownloadOnMetered: Boolean = false`。
- 加对应 preference key、`configFlow` 读取、`update()` 写回。

### 改 `di/AppContainer.kt`

- 持有 `networkMonitor`、`networkGate`(进程级单例)。
- 把 `allowPlaybackOnMetered` / `allowDownloadOnMetered` 以 `StateFlow` 形式暴露(照
  `backgroundPlayback` 的样板,collect `configFlow`)。

### 改 `ui/SettingsScreen.kt` + `ui/AppRoot.kt`

- 两个 `Switch` 行,紧接「自动播放」之后、「播放记录」之前:
  - 「移动数据下允许播放」/「关闭时,移动数据(含计费 WiFi、热点共享)下不会开始播放;
    正在播放的会暂停,回到 WiFi 后自动续播。」
  - 「移动数据下允许下载」/「关闭时,移动数据下不会开始下载;进行中的任务会暂停,
    回到 WiFi 后自动继续。」
- `onSave` 追加两个参数(不改成传 `AppConfig`)。
- 底部"修改后立即生效"的提示文案补上这两个开关。

## 完成标准

- 两个开关能保存、能读取、默认 false。
- 应用行为与改动前无差异(还没有消费方)。
- `./gradlew assembleDebug` 通过。

## Comments

**已完成**(分支 `feat/mobile-data-gate`,commit `e6b5829`)

- `core/NetworkMonitor.kt`:`interface NetworkMonitor { val isMetered: StateFlow<Boolean> }` +
  `ConnectivityNetworkMonitor`(`registerDefaultNetworkCallback`;`onLost` 后按系统当前默认网络重判,
  避免新网络已切到移动数据时被旧网络的 `onLost` 改回「非计费」;无网络/读不到一律非计费)。
- `core/NetworkGate.kt`:`NetworkGateState`(metered + 两个开关 + 会话放行 + 任务放行)与
  `NetworkGate`(`canUseMeteredPlayback()` / `canUseMeteredDownload(番号)` 唯一判定入口,
  加放行令牌与 `observeMeteredTransitions()`)。
- `config/AppSettings.kt`:两个字段 + preference key + `configFlow` + `update()`,默认 false。
- `di/AppContainer.kt`:持有 `networkMonitor` / `networkGate`,由 `configFlow` 与 `isMetered` 灌入。
- `ui/SettingsScreen.kt` + `ui/AppRoot.kt`:两个开关紧接「自动播放」,`onSave` 追加两个参数,
  底部「立即生效」文案补上这两个开关。

**偏离(有意)**

- 票里写「把 `allowPlaybackOnMetered` / `allowDownloadOnMetered` 以 `StateFlow` 形式暴露」——
  实际收在 `NetworkGate.state` 里统一暴露,没有在 `AppContainer` 上再开两个平行的 `StateFlow`:
  判定条件分散成多份流会引出「哪份是真相」的问题,而现在只有一个 `NetworkGateState`。
