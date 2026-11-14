# 桌面端 QQ 桥接（已下线，历史留档）

> **状态：2026-11-14 整块删除。** 本文件不再描述现存代码，只留「为什么消失」和「踩过的坑」。
> 想接着做 QQ 这条线，看 [`channel-gateway/README.md`](../channel-gateway/README.md)。

## 1. 它曾经是什么

予念桌面客户端里的一条旁路：用户本机装 QQNT + [NapCat](https://github.com/NapNeko/NapCatQQ)，
NapCat 用 OneBot 11 把 QQ 私聊/群消息经正向 WS 推给 Electron 主进程的 `qqBridge`，
`qqBridge` 以用户自己的登录态调云端 `/api/conversation/{id}/messages/stream`，
再把 SSE 里拆出的回复逐段发回 QQ（含双向图片、会话隔离、主动消息 fanout）。

代码曾位于：

| 路径 | 作用 |
|---|---|
| `frontend/electron/qqBridge/qqBridge.js` | 编排器：收消息 → 调云端 → 回发 |
| `frontend/electron/qqBridge/napCatClient.js` | OneBot 11 WS 客户端（echo 配对 / 重连 / selfId 探测） |
| `frontend/electron/qqBridge/messageExtractor.js` | 消息段解析 |
| `frontend/electron/qqBridge/qqBridgeSettings.js` | 配置读写 + 放行判定 |
| `frontend/electron/qqBridge/qqBridgeCoordinator.js` | 状态广播 / 告警 / 自动启动 / 扫码窗 |
| `frontend/electron/napcatRuntime/napcatHost.js` | 托管编排：解析发行 → 下载 → 配置 → 拉起 |
| `frontend/electron/napcatRuntime/napcat{Release,Downloader,Config,Process}.js` | 发行解析 / 多源下载 / 配置生成 / 子进程 |
| `frontend/src/pages/QqBridgePage.vue` + `frontend/src/stores/qqBridge.js` | 渲染端页面与状态 |

其中三支**纯 node 模块已平移到云端网关**（唯一仍在用的部分）：

| 原路径 | 现路径 |
|---|---|
| `frontend/electron/napcatRuntime/napcatProcess.js` | `channel-gateway/src/supervisor.js` |
| `frontend/electron/qqBridge/napCatClient.js` | `channel-gateway/src/onebotClient.js` |
| `frontend/electron/qqBridge/messageExtractor.js` | `channel-gateway/src/onebotExtract.js` |

## 2. 为什么下线

1. **用户成本高**：每个用户都要装 QQNT、下 ~28MB NapCat、注册一个额外小号并扫码；桥接只在桌面端进程活着时工作，电脑一关 QQ 就不回。
2. **协议端死在上游**：NapCat 仓库 2026-11-12 被清空（README 只剩 `## Ending`，releases 为空，文档站 `napneko.github.io` 变成 Site not found，`v4.18.7` 资产 404）。桌面端内置的 pinned 发行号也随之失效——「重装/修复运行时」按钮已经不可能成功。
3. **构建号强耦合**：`NapCatWinBootMain.exe` + `qqnt.json` 必须与 QQ 的构建号成对。QQ 自动升级（本例 9.9.21-39038 → 9.9.37-54395）后，旧 shell 注入被 QQ 自身反篡改校验杀掉，用户看到「文件已损坏，请重新安装QQ。」。
4. **合规风险**：第三方协议端违反 QQ 用户协议，随时可能被风控/封号。

## 3. 教训（比代码更值得留）

- **兜底判定会把「启动失败」伪装成「运行中」。** `napcatHost.js` 曾用「stdout 解析出 WebUI，否则 8s 后直接用已知 token 拼一个 URL 报 running」来兜住慢启动。结果是 NapCat 根本没进 QQ，UI 却显示「托管已启动」，用户据此认为「更新前一直是好的」。**没有真实证据的成功状态，比失败更贵。**
- **一键下载第三方协议端 = 把上游的生死写进自己的发版节奏。** 上游清空仓库后，客户端里所有「重装」「升级」路径同时变成死代码。
- **换一个协议端不等于换掉风险。** 同期试过 LLBot（原 LLOneBot）与 Lagrange：前者现在要厂商 auth token（`auth.luckylillia.com`）才能跑，是授权门槛产品；后者 V1（OneBot）已 sunset，主线转 Lagrange.Milky（Milky 协议），OneBot 侧只剩 2025-08 的 nightly。
- **桌面端托管的天花板是「用户必须开着电脑」**，这一点无论怎么优化安装流程都改不掉。

## 4. 现在往哪走

- 保留并继续做 **`channel-gateway/`**：运营方在云主机上托管多个 QQ 号，用户只扫码、不装软件、7×24 在线（W1 PoC 已完成，协议端待定）。
- 协议端的候选与限制见 `channel-gateway/README.md` 的「协议端现状」一节。
- 渲染端「QQ 桥接」页面、IPC 通道、i18n 词条、`napcatRuntime/` 与 `qqBridge/` 目录已全部删除；若要回归，请按新协议端重新设计，不要从 git 历史里整体 revert——里面同时躺着上面那条「假 running」的坑。
