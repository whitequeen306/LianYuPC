# QQ Bridge Coordinator Extraction Design

## Goal

在不改变当前 QQ 桥接可用行为的前提下，把 `frontend/electron/main.js` 中与 QQ bridge / NapCat host 相关的业务编排职责抽离到独立 coordinator 模块，降低主进程入口复杂度，并为后续更细粒度拆分 `qqBridge.js` 打基础。

## Current Context

### Current architecture in practice

- 当前实际主路径是 Electron 桌面端桥接：`frontend/electron/qqBridge/qqBridge.js` 通过本地 NapCat WebSocket 收发 QQ 消息，再调用予念云端 `/api/conversation/...` 接口完成角色回复。
- `frontend/electron/main.js` 不仅负责 Electron app/bootstrap，还负责 QQ 桥接相关的业务编排，包括：
  - QQ bridge 状态推送
  - NapCat host 状态与下载进度推送
  - 掉线/被踢告警与自动重启
  - 自动启动 bridge
  - 自动启动 NapCat host
  - 自动绑定 conversation/character
  - 打开 NapCat WebUI 登录窗
  - 桥接日志读取过滤
  - 对应 IPC handler 注册
- `frontend/src/stores/qqBridge.js` 和 `frontend/src/pages/QqBridgePage.vue` 已围绕这些 IPC 能力建立了现有 UI 行为。

### Why change now

- `main.js` 目前同时承担 Electron 壳层职责和 QQ 业务编排职责，导致阅读、测试和后续改造成本偏高。
- 现有逻辑虽然可用，但大量 QQ 相关流程散落在 `main.js` 的多个位置，新增功能时更容易引入回归。
- 用户已明确说明当前 QQ 桥接“是正常的”，所以这次重构必须以低风险、行为保持不变为第一原则。

## Constraints

- 不改变 QQ 桥接现有可用行为。
- 不改变予念角色接入方式，仍由当前 Electron bridge 调用现有予念会话接口。
- 不改变现有本地配置结构与字段语义。
- 不改变现有 IPC channel 名称，对渲染进程保持兼容。
- 不顺手推进第二阶段拆分 `frontend/electron/qqBridge/qqBridge.js`。
- 必须先补或新增测试，再改实现。

## Chosen Approach

采用最小可行的 coordinator 抽离：

- 新建一个独立的 Electron 主进程模块，例如 `frontend/electron/qqBridge/qqBridgeCoordinator.js`。
- 把 `main.js` 里 QQ 相关的纯业务编排函数迁入 coordinator。
- coordinator 不直接持有 BrowserWindow 全局变量，而是通过依赖注入接收需要的能力：
  - `getWindows()` 或同等窗口访问器
  - `startQqBridge` / `stopQqBridge` / `getQqBridgeStatus`
  - `startNapCatHost` / `stopNapCatHost` / `getNapCatHostStatus`
  - `readQqBridgeSettings` / `writeQqBridgeSettings`
  - `resolveDesktopAuthToken` / `resolveApiOrigin`
  - `performApiRequest`
  - `Notification` / `BrowserWindow` / `shell`
  - `logger` / `isAllowedExternalUrl` / `resolveDistPath`
- `main.js` 保留：
  - Electron app 生命周期
  - BrowserWindow 创建与全局持有
  - coordinator 初始化
  - IPC 注册，但 IPC handler 内部尽量转调 coordinator 方法

这样做的目的是只重整边界，不重写业务流程。

## Design

### 1. New coordinator boundary

#### Intent

把 `main.js` 中 QQ 业务编排相关的函数集中到一个可测试模块中，保留原始行为与时序。

#### Coordinator responsibilities

- `pushQqBridgeStatus(status)`
- `pushQqHostStatus(status)`
- `pushQqHostDownload(progress)`
- `autoStartQqBridgeIfNeeded()`
- `autoStartNapCatHostIfNeeded()`
- `ensureBridgeBinding({ apiOrigin, authToken, characterId })`
- `makeNapCatBridgeStarter()`
- `openQqLoginWindow()`
- `getQqBridgeLogs()`
- 内部使用的告警与掉线恢复逻辑：
  - `sendQqBridgeAlert()`
  - `restartNapCatForReconnect()`

#### main.js responsibilities after extraction

- 保留主窗口、启动器窗口、登录窗等全局生命周期托管。
- 初始化 coordinator，并把窗口访问器和主进程依赖注入进去。
- IPC handler 只做：
  - trusted sender 校验
  - 轻量参数规范化
  - 调 coordinator / runtime 方法

### 2. Dependency injection instead of hidden globals

#### Intent

避免 coordinator 再复制一套 `main.js` 全局状态依赖，保证其能被单测替换依赖。

#### Changes

- coordinator 暴露 `createQqBridgeCoordinator(deps)` 工厂。
- 通过 `deps` 注入所有外部依赖和副作用出口。
- coordinator 内部仅保留它自己的运行态状态，例如：
  - `prevBridgeState`
  - `notLoggedInTimer`
  - `lastDisconnectNotifyTs`
  - `lastKickedTs`
  - `napcatRestarting`
  - `qqLoginWindowRef` 或可替代的 getter/setter
- 所有窗口消息发送统一通过单一帮助函数完成，避免在多处手写 `[mainWindow, launcherWindow]` 遍历。

#### Outcome

- `main.js` 不再既持有窗口对象又直接编码 QQ 业务细节。
- coordinator 可以在 Vitest 下用 mock 依赖做状态测试。

### 3. Preserve the existing state semantics

#### Intent

这次不重写状态机，只把现有隐式状态语义搬到可测试模块里，避免行为回归。

#### Explicit non-goal

- 不把当前 bridge/host 状态机改造成新的统一状态枚举。
- 不调整现有定时器、节流窗口或通知文案语义，除非测试证明现有迁移过程中必须修正明显缺陷。

#### Changes

- 保持以下行为完全一致：
  - `ready` 时清掉“未登录”计时器
  - `connected` 后 15 秒未 `ready` 弹“未登录”提示
  - `kicked` 且 `connected` 时弹告警并尝试重启 NapCat
  - `disconnected` 时按现有 30 秒 / 60 秒抑制规则发告警
  - `stopped` 时清理计时器
- 用测试把这些行为钉住，再迁移实现位置。

### 4. Preserve IPC and UI contract

#### Intent

渲染层不应该感知这次重构。

#### Changes

- 保持这些 IPC 返回结构不变：
  - `desktop:get-qq-bridge-settings`
  - `desktop:set-qq-bridge-settings`
  - `desktop:start-qq-bridge`
  - `desktop:stop-qq-bridge`
  - `desktop:get-qq-bridge-status`
  - `desktop:qq-bridge-resolve-conversation`
  - `desktop:qq-bridge-get-logs`
  - `desktop:start-qq-host`
  - `desktop:stop-qq-host`
  - `desktop:get-qq-host-status`
  - `desktop:reinstall-qq-host`
  - `desktop:open-qq-login-window`
- `QqBridgePage.vue`、`stores/qqBridge.js` 不需要因本次改造改接口。

### 5. Testing strategy

#### Intent

先用测试锁住当前行为，再做抽离，确保“不改废”。

#### Planned unit coverage

- coordinator 状态告警逻辑：
  - `connected -> 15s 未 ready` 触发未登录告警
  - `ready` 清计时器，不再误报
  - `kicked + connected` 触发 kicked 告警并尝试重启
  - `disconnected` 按抑制规则发告警
- `openQqLoginWindow()`：
  - 未 consented 拒绝
  - 无 token 拒绝
  - 已有窗口复用
  - 新窗口时设置本地导航限制
- `autoStartQqBridgeIfNeeded()`：
  - auto 模式跳过
  - 缺少 enabled / binding / authToken 跳过
  - 满足条件时调 `startQqBridge`
- `makeNapCatBridgeStarter()`：
  - 未登录跳过
  - 无 conversation/character 时触发自动绑定
  - 自动绑定失败时不启动 bridge
  - 自动绑定成功后按现有设置合成启动参数

#### Verification strategy

- 运行新增 coordinator 单测。
- 运行现有 QQ bridge 与 NapCat host 相关单测，确认未破坏既有行为。
- 仅在相关范围内做最小代码变更，不顺带重构其它模块。

## Files Likely To Change

- Create: `frontend/electron/qqBridge/qqBridgeCoordinator.js`
- Create: `frontend/electron/qqBridge/__tests__/qqBridgeCoordinator.test.js`
- Modify: `frontend/electron/main.js`
- Possibly modify: `frontend/electron/preload.js` only if a testable seam is strictly needed, otherwise avoid

## Risks And Mitigations

### Risk: hidden coupling to main.js globals

- Mitigation: 通过工厂函数 + 依赖注入替换直接读写外部变量。

### Risk: timer-based behavior changes subtly during extraction

- Mitigation: 先写状态告警测试，再搬代码；搬代码时尽量保留原逻辑顺序。

### Risk: login window behavior regresses

- Mitigation: 单测覆盖 not_consented / not_running / reuse / navigation restrictions 四类路径。

### Risk: auto-start and auto-bind behavior regresses

- Mitigation: 为 `autoStartQqBridgeIfNeeded()` 和 `makeNapCatBridgeStarter()` 写直接单测，避免只靠人工回归。

## Out Of Scope

- 拆分 `frontend/electron/qqBridge/qqBridge.js`
- 调整 QQ bridge 与予念后端的接口协议
- 改造现有渲染层页面和 store 结构
- 清理文档里旧的后端 `lianyu-qq-bridge` 方案说明
- 引入 asrbot 或替换 NapCat
