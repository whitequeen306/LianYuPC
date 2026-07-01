# Electron 桌面客户端安全能力手册（红/蓝队 · 可复用）

> **文档类型：** 能力规范（Capability Spec），不是某次迭代的变更日志。  
> **用法：** 给其它项目的智能体/工程师作**参考**——按能力模块选型、实现蓝队、用红队步骤验收。  
> **边界：** 桌面客户端无法绝对防逆向；目标是**抬高攻击成本** + **服务端决定是否放行**。

---

## 0. 给智能体怎么用

```text
读 docs/reusable-capabilities/electron-client-hardening-zh.md。
按能力模块（§2）为目标 Electron 项目做蓝队适配：先摸清打包链与 API 注入点，再实现并跑 §3 红队验收。
只复用机制与顺序，禁止照搬参考项目的 PEPPER、HTTP 头前缀、路径。
```

**适配时必改：** 项目专属 `PEPPER`、HTTP 头前缀、pack 用 env 文件名、审计脚本里的 hostname 模式、`appId`。

---

## 1. 能力总览

| ID | 能力 | 红队目标 | 蓝队要点 | 优先级 |
|----|------|----------|----------|--------|
| C1 | 静态拆包与搜密 | 解压 asar、grep IP/密钥 | 强混淆 + secrets 二进制 + 无 stale 产物 | 必做 |
| C2 | 主进程逻辑还原 | 读 main/preload 明文 | V8 字节码 + 极小 stub | 必做 |
| C3 | 配置与 API 地址提取 | 从 JS/env 拿后端地址 | API 不进 renderer；主进程 IPC 下发 | 必做 |
| C4 | 中间人 / 抓包 | 改 API、解密 HTTPS | 证书 Pinning（SPKI+指纹） | 推荐 |
| C5 | 动态调试 | DevTools 改逻辑、看内存 | 反调试 + 禁 DevTools + Fuses | 推荐 |
| C6 | 本地凭据窃取 | dump token / deviceSecret | Token 加密存储 + safeStorage | 推荐 |
| C7 | 脚本调 API | curl/Postman + token | Client Attestation（HMAC） | 核心（需后端） |
| C8 | 重放与暴力试签 | 重复请求 / 撞签名 | nonce 一次性 + 时间窗 + 封禁 | 随 C7 |
| C9 | 篡改安装包 | 改 asar 再运行 | asarmor patch + 启动 integrity 校验 | 必做 |
| C10 | IPC 滥用 | 非信任页面调敏感 IPC | guardTrusted + contextIsolation | 必做 |
| C11 | 敏感业务数据外泄 | 拆客户端拿 prompt 模板 | 未 attested 响应脱敏 | 随 C7 |
| C12 | WebSocket 劫持 | 跨站连 WS | Origin 白名单 | 推荐 |
| C13 | AI 辅助批量读码 | LLM 读混淆 JS 出架构图 | 混淆+字节码抬成本；**真正的门在 C7** | 认知 |
| C14 | 基础设施暴露 | 扫源站 IP 打服务器 | 域名 + CDN/WAF（非客户端） | 运维 |

---

## 2. 能力模块（红队 · 蓝队）

每个模块：**攻击怎么做 → 成功标志 → 防护怎么建 → 怎么验**。

---

### C1 静态拆包与字符串搜密

**红队（攻击）**

1. 解 NSIS/Inno 安装包（7-Zip 等）→ 得到 `resources/app.asar`
2. `npx @electron/asar extract` 或 `extract-file` 逐文件导出
3. 对 `dist/assets/*.js`、`dist-electron/*` 做：
   - `strings` / ripgrep 搜 API 域名、IP、`VITE_*`、`sk-`、`password=`、证书 pin 片段
   - 混淆启发式打分（控制流平坦化、`_0x` 密度、string array）
4. 检查是否有多余旧入口（如未混淆的 `main.cjs` 与正式 `main.js` 并存）

**攻击成功标志：** 分钟级拿到可读字符串或完整目录树；直接看到生产 hostname。

**蓝队（修复）**

| 措施 | 说明 |
|------|------|
| Renderer 强混淆 | pack 阶段单点配置；`stringArrayEncoding: ['rc4']` 等 |
| runtime-secrets.bin | API/证书 XOR 进 bin，不进 JS |
| 打包清空 renderer env | 构建模式禁止 `define` API 相关 VITE 变量 |
| asarmor header patch | 拖垮 naive extract，非全量 encrypt |
| 清理 stale 产物 | pack 前/后删 `main.cjs`、`*-src.cjs`、旧 `.jsc` |
| 发布审计脚本 | 自动扫 asar：hostname、env 键名、stale 文件 → FAIL |

**蓝队验收：** 红队步骤 3 对生产 hostname **零命中**；extract 仍可成功但 JS 难读且无多余明文入口。

---

### C2 主进程 / Preload 逻辑还原

**红队**

1. 读 `dist-electron/main.js`、`preload.cjs`——若为 stub，追踪 `require('*.jsc')`
2. 尝试 bytenode 反编译 / V8 调试 / 内存 dump
3. 对比 stub 体积（>500B 可能夹带明文逻辑）

**成功标志：** 读到证书 pin、IPC 注册、secrets 解码逻辑明文。

**蓝队**

| 措施 | 说明 |
|------|------|
| esbuild 打 CJS | main 先 bundle 再编译，external electron/bytenode |
| bytenode → `.jsc` | **禁止** obfuscator 输出再喂 bytenode |
| stub 仅 3～10 行 | `require('bytenode'); require('./main.jsc')` |
| Windows 编译 | `electron.exe` + `ELECTRON_RUN_AS_NODE=1` |

**验收：** asar 内 main/preload 为 stub + jsc；stub 小于约定阈值；混淆分低。

---

### C3 配置与 API 地址隔离

**红队**

1. 在 renderer bundle 搜 `http://`、`https://`、WebSocket URL
2. 查 `import.meta.env` / 构建注入常量
3. 运行时 hook axios baseURL（若已动态注入则改 IPC 返回值）

**成功标志：** 不运行程序仅从静态文件得到后端根地址。

**蓝队**

| 措施 | 说明 |
|------|------|
| secrets 仅主进程 | `loadRuntimeSecrets()` 启动最早执行 |
| IPC `runtime:get-config` | renderer `await init*()` 后再 mount |
| axios 占位 base | 如 `http://127.0.0.1:0`，请求前再换真实 origin |
| buildId 绑密钥 | `SHA256(version:buildId:PEPPER)` 派生 XOR 密钥 |

**secrets 格式 v1：**

```text
[1B ver=1][16B nonce][2B len BE][XOR(JSON)]
key = SHA256("{version}:{buildId}:{PEPPER}")
```

**验收：** renderer JS 无生产 URL；dev 模式仍可用 env fallback 联调。

---

### C4 中间人 / HTTPS 抓包

**红队**

1. 系统安装 Charles/Fiddler CA
2. 代理抓 Electron 的 HTTPS/WSS
3. 尝试改响应、换 API  host

**成功标志：** 明文看到 API 流量或成功连到伪造服务器。

**蓝队**

| 措施 | 说明 |
|------|------|
| `setCertificateVerifyProc` | 仅对 API hostname 自定义校验 |
| SPKI SHA-256（Base64）为主 | 写入 secrets bin |
| 证书指纹 hex 兜底 | `certificate-error` 二次放行 |
| 可选 | 企业 MITM 环境变量回退系统 CA（慎用） |

**验收：** 非 pin 证书连接 API 域名失败；合法自签/固定证书可连。

---

### C5 动态调试

**红队**

1. `--inspect` / 远程调试端口
2. F12 / Ctrl+Shift+I
3. renderer 里下断点、改 `localStorage`

**成功标志：** 稳定打开 DevTools 或附加调试器。

**蓝队**

| 措施 | 说明 |
|------|------|
| Electron Fuses | `runAsNode: false`、禁 inspect 相关 |
| `lockDownDevTools` | 禁快捷键、`devtools-opened` 立即关 |
| renderer 反调试 | debugger 时间差检测；`document.hidden` 时暂停 |
| 生产 drop console | 构建去掉 console + antiDebug 覆写 |
| CSP | script-src 'self'（按框架需求放宽 unsafe-eval） |

**验收：** 生产包无法常驻 DevTools；debug 构建不受影响。

---

### C6 本地凭据窃取

**红队**

1. 读 `%APPDATA%` / 应用 userData 下 JSON、localStorage 导出
2. 搜明文 `token`、`deviceSecret`
3. 复制到新环境复用

**成功标志：** 拿到可独立使用的 session token 或 signing secret。

**蓝队**

| 措施 | 说明 |
|------|------|
| Token AES-GCM | Web Crypto，密钥实例绑定存 localStorage |
| deviceSecret | **仅主进程** `safeStorage` 加密文件 |
| 签名不经 renderer | HMAC 在 main，renderer IPC `signRequest` |

**验收：** localStorage 无明文 token；userData 无明文 deviceSecret。

---

### C7 脚本 / 伪造客户端调 API（核心）

**红队**

1. 注册/登录拿 `lianyu-token`（或项目 token 头）
2. curl/Postman 调 `/api/chat`、`/api/character` 等
3. 无 `X-*-Signature` 或乱填签名

**成功标志：** enforce 开启后仍能用纯 token 调通核心业务。

**蓝队 — 客户端**

| 措施 | 说明 |
|------|------|
| 登录带 `X-{App}-Client` | `electron/{version}/{buildId}` |
| 登录响应 | 下发 `deviceId` + `deviceSecret` |
| 后续请求头 | Client、DeviceId、Timestamp、Nonce、Signature |
| Canonical | `METHOD\npath\ntimestamp\nnonce\nsha256(body)` → HMAC-SHA256 |

**蓝队 — 服务端**

| 措施 | 说明 |
|------|------|
| Filter | skip：login/register/captcha/public |
| enforce 开关 | false：不拦（过渡）；true：已登录 API 必须验签 |
| 最低客户端版本 | 拒绝过旧 build |
| 未 attested | 敏感字段（如 promptTemplate）不返回 |

**上线顺序（蓝队运维）：**

```text
发含 attestation 的新客户端 → 用户重登拿 deviceSecret → 再开 enforce
```

**验收：** enforce 后，无合法签名的 curl 返回 401；官方客户端正常。

---

### C8 重放与暴力试签

**红队：** 重复同一 nonce；快速随机签名撞库。

**蓝队：** Redis 存 nonce 一次性；timestamp ±5min；同 device 失败次数封禁。

**验收：** 重放第二次失败；短时间大量错误签名被封。

---

### C9 篡改安装包

**红队：** 改 asar 内 JS 后再启动；重打包分发。

**蓝队：** after-pack asarmor patch；对**最终** asar 写 SHA256 到 `asar-integrity.hex`；启动比对失败则 exit。

**验收：** 改一个字节 asar 后应用拒绝启动。

---

### C10 IPC 滥用

**红队：** 在 renderer 注入脚本或伪造 webContents 调 `invoke('auth:*')`、`runtime:*`。

**蓝队：** `contextIsolation: true`；preload 最小暴露；`guardTrusted(event)` 校验 sender id + `file://` URL；敏感能力仅 main。

**验收：** 非信任 sender 的 IPC 返回 `untrusted_sender`。

---

### C11 敏感业务数据外泄

**红队：** 调角色/配置 API，拿完整 prompt 模板克隆产品。

**蓝队：** 业务层判断 `REQUEST_ATTR_ATTESTED`；未验签通过则剥离高价值字段。

**验收：** 无签名客户端拿不到完整 prompt。

---

### C12 WebSocket 劫持

**红队：** 恶意页面连 `wss://api/ws`（CSWSH）。

**蓝队：** `setAllowedOriginPatterns` 仅生产域名；CONNECT 鉴权。

**验收：** 非白名单 Origin 握手失败。

---

### C13 AI 辅助批量读码（认知层）

**红队：** asar 解包 → 整包或分块喂 LLM → 输出 API 列表、架构、绕过建议。

**蓝队现实：**

- 混淆 + 字节码：**拖时间**（小时～天），不是阻断
- **有效防线是 C7**：即使读懂前端，没有 deviceSecret 仍调不通核心 API（enforce 后）
- 高价值 prompt 放服务端（C11）

**验收：** 红队可在 LLM 帮助下理解大致流程，但无法在 enforce 下用脚本完成同等业务。

---

### C14 源站 IP 暴露（非客户端范畴）

**红队：** 拆包、DNS、抓包、扫段 → 直接打服务器。

**蓝队：** 域名 + CDN 代理源站；WAF；服务器 `.env` 与客户端完全分离。

**认知：** 客户端**不能**隐藏最终连接 IP（抓包必见）；IP 可见 ≠ 后端源码泄露 ≠ 数据库密钥泄露。

---

## 3. 红队验收 playbook（发布前跑一遍）

按顺序执行，记录每项 **PASS / FAIL / N/A**：

```text
1. 解包安装包 → 列 asar 树
2. grep 生产 hostname、API env 键名、sk-/password=（C1）
3. 确认 main/preload 为 jsc+stub，无 stale main.cjs（C1/C2）
4. 混淆分：renderer ≥ 阈值；main/preload stub 分低（C1/C2）
5. 改 asar 一字节 → 启动应失败（C9）
6. Charles 抓 API 域名 → 非 pin 应失败（C4）
7. 生产包 F12 → 应不可用（C5）
8. enforce 关：curl+token 可能通；enforce 开：无签名应 401（C7）
9. 未 attested 调角色详情 → 无完整 prompt（C11）
```

可脚本化部分：解包、grep、stub 大小、hostname 扫描（参考 `_audit_installer_unpack.py`、`_verify_electron_release.py`，迁移时改 env 键名与 hostname 源）。

---

## 4. 蓝队打包流水线（单脚本串起 C1/C2/C3/C9）

目标项目应有一个 **pack 入口**（参考：`frontend/scripts/electron-pack.mjs`）：

```text
清理 stale
→ 前端生产构建（不注入 API env）
→ esbuild main → CJS
→ 写 build 元数据（version + buildId）
→ pack runtime-secrets.bin
→ 强混淆 renderer 产物
→ bytenode main/preload → stub
→ electron-builder
→ after-pack：asarmor + integrity 哈希
```

**硬性约束：**

- obfuscate **后再** bytenode → ❌  
- asarmor **全量** encrypt + contextIsolation → ❌  
- API 域名进 renderer bundle → ❌  

---

## 5. 迁移到其它项目（能力裁剪）

| 场景 | 建议能力集 |
|------|------------|
| 只有桌面客户端、后端不能改 | C1 C2 C3 C9 C10（+ C4 C5 C6 推荐） |
| 要防脚本白嫖 API | 上述 + **C7 C8 C11** + 后端 Filter |
| 还要防 WS / 扫站 | + C12 C14 |

**从参考实现拷贝职责（路径按目标项目改）：**

| 职责 | 参考路径（LianYu） |
|------|---------------------|
| 打包总控 | `frontend/scripts/electron-pack.mjs` |
| 字节码 | `frontend/scripts/compile-bytecode.mjs` |
| Secrets 编解码 | `pack-runtime-secrets.mjs` / `electron/runtimeSecrets.js` |
| 主进程安全 | `electron/main.js` |
| Attestation | `electron/clientAttestation.js` + 后端 `ClientAttestationFilter` |
| 凭据存储 | `authSessionStore.js`、`secureToken.js` |
| 反调试 | `antiDebug.js` |
| Renderer 配置 | `src/utils/runtime.js` |
| after-pack | `scripts/after-pack.mjs` |
| 红队审计 | `scripts/_audit_installer_unpack.py` |

---

## 6. 能力边界（写进方案避免误解）

| 误区 | 事实 |
|------|------|
| 加密前端 = 后端安全 | 主密钥只在服务器；客户端没有 |
| 藏 IP = 防 API 滥用 | 抓包仍见；靠 attestation + 服务端 |
| 混淆 = 无法逆向 | 只能拖时间；determined 攻击者仍可还原 |
| 先开 enforce 再发客户端 | 旧客户端无 deviceSecret → 大面积不可用 |

---

*Electron 桌面客户端安全能力手册 · 红/蓝队对照 · 供跨项目复用*
