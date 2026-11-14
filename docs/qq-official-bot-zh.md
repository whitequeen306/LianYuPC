# QQ 官方机器人接入（已验证跑通）

> 现状：**已用真号跑通**——在手机 QQ 里给机器人发消息，回复来自予念的角色（人设 + 记忆都在云端）。
> 2026-11-14 验证：App 里说过「下周三要去医院拔智齿」，把 App 侧会话消息全部删空后，
> 在 QQ 里问「我下周三是有什么事来着？」仍答出「下周三上午，你要去医院拔智齿」。
> 这条链不依赖第三方协议端（NapCat 已停服），也不需要用户装任何东西、不需要电脑开机。

## 1. 它解决了什么

| 老路（桌面 NapCat） | 官方机器人 |
|---|---|
| 用户装 QQNT + 下载 28MB NapCat | 什么都不用装，手机 QQ 就是客户端 |
| 要一个小号，扫码登录 | 扫码添加「予念」即可 |
| 电脑必须开着 | 云端 7×24 |
| 违反 QQ 协议，可能封号 | 官方通道 |
| 换 QQ 版就崩（构建号耦合） | 腾讯自己维护 |

代价也明确：**角色外壳是统一的**（所有用户看到的是同一个机器人昵称/头像），
用户自建角色只能「借这张嘴说话」；发送侧有限制（见 §5）。

## 2. 链路

```
手机 QQ ──腾讯 WS 网关──> channel-gateway/src/qqbot/
                              ├─ gateway.js  事件流（Hello/Identify/心跳/Resume）
                              ├─ handler.js  事件 → bindings.json → 予念 → 回复
                              ├─ api.js      取 access_token、发消息
                              └─ ../lianyu.js 云端 /api/conversation（记忆、人设、关系都在这边）
```

身份映射是关键：腾讯给的是 **openid**（每个 bot 每人一个，不等于 QQ 号，也不跨 bot 通用），
予念要的是 `(userId, characterId)`。桥就是 `bindings.json`：

```json
{ "qqbot-c2c:<openid>": { "characterId": 1002, "conversationId": 953 } }
```

记忆的键始终是 `(userId, characterId)`，与入口无关——这是「换电话线不丢记忆」的原因。

## 3. 开放平台要配的东西

| 位置 | 值 |
|---|---|
| 开发设置 → AppID / AppSecret | 取授权用；Secret 点「查看手动连接参数」 |
| **开发设置 → 事件订阅与回调地址** | 必须选 **WebSocket**（选 Webhook 则连上网关也收不到事件） |
| 服务器 IP 白名单 | **留空 = 全部放行**；设了就只有白名单能调 OpenAPI（≤50 个 IPv4，不支持 CIDR） |
| 内部体验号码 | 上线前测对话能力，最多 20 个 |
| 服务范围 | 未认证只能加自己当群主的群 |

## 4. 跑起来

```powershell
$env:QQ_APPID  = '<AppID>'
$env:QQ_SECRET = '<AppSecret>'
$env:LIANYU_API_ORIGIN = 'https://downloadlianyu.asia'
$env:LIANYU_TOKEN = '<用户的 lianyu-token>'
$env:LIANYU_PROVIDER = 'dashscope'
$env:LIANYU_MODEL = 'qwen-plus'
$env:LIANYU_INSECURE_TLS = '1'   # 云端是自签证书
$env:DEFAULT_CHARACTER_ID = '1002'
node channel-gateway/scripts/qqbot-run.mjs
```

只想验证「QQ → 我 → QQ」而不接云端、不烧模型：加 `--echo`，收到什么原样回。

日志里要看到这三行才算通：

```
[qqbot] access_token 就绪：…
[qqbot] 已就绪（session=…）        ← READY，说明鉴权与 intents 都对
[qqbot] 已回 15 字（conv=953）      ← 回复真的发出去了
```

## 5. 官方限制（设计时要低头的地方）

- **被动回复窗口**：单聊 60 分钟 / 群 5 分钟（发消息参数表另写 5 分钟，双口径并存→按「尽快回」做）。
- **同一条用户消息最多回 4 次**（C2C）/ 5 次（群）。多气泡拟人化最多 4 段。
- 相同 `msg_id + msg_seq` 重发会被去重拒掉（`40054005`）→ 多段回复必须递增 `msg_seq`。
- 主动消息（`is_wakeup`）是「互动召回」：用户主动对话后 30 天内 4 个周期各 1 条，且用户可在 QQ 里关掉。
- 内容受官方审核（`1100101` 内容敏感）；群聊要 @ 机器人，或群主授权「接收所有消息」。

## 6. 已踩过的坑（都写进了测试）

| 现象 | 根因 | 修法 |
|---|---|---|
| QQ 一直「正在输入」，日志 `bad_response_400` | `ensureConversation()` 返回的是会话对象，被整块当 id 落库 → `POST /api/conversation/[object Object]/messages` | `normalizeConversationId()` 收敛成数字，写坏的老数据自愈 |
| 机器人回一句 `[object Object]` | `/messages` 返回整条消息对象，`String(reply)` 把对象当字符串 | `replyText()` 只取 `content`，取不到按 `empty_reply` 跳过 |
| `token_rejected_100016` | AppSecret 里的数字 `0` 被抄成字母 `O` | 逐字符核对（`z790f5…` 第一位是零） |
| 取 token 报错却拿不到原因 | **失败也返回 HTTP 200**，原因在 body.code | 只认 `body.code`（10004/10007/10016/100001） |

## 7. 还没做的（生产化差距）

1. **一个 token 服务所有人**：现在网关用单个 `LIANYU_TOKEN` 跑，只能给一个人用。
   生产要做「绑定码」：App 内登录 → 生成 6 位码 → 用户在 QQ 里发码 → 网关把 openid 落到该 userId，
   之后每轮对话按 userId 取该用户的会话（token 不应停在网关里，应由后端签发短时凭证）。
2. **角色切换**：绑定后允许「换一个角色聊」，群里/私聊各自独立。
3. **多用户并发**：`SO_REUSEPORT` 无关，主要是 token 池与限频（腾讯侧每 bot 有频控）。
4. **可观测**：现在只有 stdout 日志，缺回复耗时、失败率、`trace_id` 关联。
