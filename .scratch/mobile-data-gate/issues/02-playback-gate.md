# 02 — 播放闸门

**Status:** resolved
**Type:** task
**Blocked by:** 01

第二步:把「移动数据下允许播放」接到播放路径上。下载不受影响。

## 范围

### 兜底:代理层

- 拦在 `proxy/HlsProxyHandler.handle` 的分发**之前**(一处覆盖 m3u8 与 `.ts`),
  不要在两个 handler 里各写一遍。
- 判定源由 `AppContainer.createProxyGraph` 注入(`ProxyGraph` 增加对应字段)。
- 命中时返回 `403` + 明文原因(如「移动数据下禁止代理取流」),并 `Log.w`。

### 交互:UI 层

- `ui/HomeScreen.kt`
  - **自动播放路径**(列表点播 / 深链直达):移动数据下**不弹框**、不 `prepare`,
    停在封面海报态,显示常驻提示「当前为移动数据,未自动播放」。
  - **手动路径**(播放按钮 / 输入框播放):移动数据下弹「本次继续 / 取消」对话框;
    "本次继续"置 `NetworkGate` 的播放会话放行后开始播放。
  - 开关打开时不走任何闸门,行为与现在一致。

### 播放中切网

- 播放中切到计费网络且未放行 → `pause()` + Snackbar「已切到移动数据,播放已暂停」,
  并标记该暂停来自闸门。
- 切回非计费网络 → 只续播"闸门暂停"的那次,用户手动暂停的保持暂停。
- 放行中的播放会话切网不打断。

### 错误呈现

- `ui/PlaybackController.kt`:把代理返回的 `403` 映射成「已暂停:当前为移动数据」
  用户文案,不要把 `ERROR_CODE_IO_BAD_HTTP_STATUS` 这类技术错误直接甩给用户。

### 服务侧

- `service/PlaybackService.kt` 能读 `networkMonitor` / `networkGate`(服务侧也要能判定,
  后台切网无 UI 时行为仍然正确)。

## 完成标准

- 验收 1 / 2 / 3 / 4 / 8 / 9 通过(spec.md)。
- 后台播放中切网同样暂停(不依赖 UI 存活)。
- `./gradlew assembleDebug` 通过。

## Comments

**已完成**(commit `753d7cb`,评审修正在 `03d1a47`)

- 兜底:`HlsProxyHandler.handle` 分发前判定,`403` + 明文原因 + `Log.w`;判定源由
  `AppContainer.createProxyGraph` 注入 handler。
- 交互:`ui/HomeScreen.kt` — 自动播放路径(列表/深链)在计费网络下不 prepare、停在封面并显示
  常驻提示「当前为移动数据,未自动播放」;手动播放(播放按钮/输入框)弹
  `ui/MeteredNetworkDialog.kt` 的「本次继续 / 取消」,继续后置会话放行;离开播放页
  (HomeScreen 离开组合)清会话放行。
- 播放中切网:`service/PlaybackService.kt` 跟随 `metered` 翻转暂停/续播(后台无 UI 也生效),
  只续播「闸门暂停」的那次;`onIsPlayingChanged(true)` 时清闸门标记,避免
  「闸门暂停 → 用户自己播过 → 用户手动暂停 → 回 WiFi」被误续。
- 错误呈现:`PlaybackController` 把代理的 `403` 映射为「已暂停:当前为移动数据」。

**偏离(有意)**

- 票里写「`ProxyGraph` 增加对应字段」——gate 直接注入 `HlsProxyHandler`,`ProxyGraph` 不加
  没人读的字段(投递点仍是 `createProxyGraph`,票的意图达成)。
- `403` 只按状态码识别,不再附带「当前仍是计费网络」的条件:代理是本 App 唯一的 403 来源
  (百度侧错误一律转 502/500),错误上浮时用户可能已经切回 WiFi。

**已知限制**

- 通知栏点播放没有 UI 参与,走不到「本次继续」弹框,落到代理 403 + 上面那句文案
  (即 spec 决策 12 的兜底)。真要弹框得接管通知/会话回调,spec 决策 13 已明确不做。

**验收** 1 / 2 / 3 / 4 / 8 / 9 通过代码路径核对(`assembleDebug` 通过;仓库无测试源集,
按 spec「不做」条款未加自动化测试)。
