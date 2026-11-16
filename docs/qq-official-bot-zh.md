# QQ 官方机器人接入（方案 B：绑定在后端，网关不持凭据）

> 现状（2026-11-14）：**真号跑通并已部署生产**。手机 QQ 里给机器人发消息，回复来自予念的角色，
> 记忆、人设、关系全在云端；用户不装任何东西，电脑关着也照回。
> 这天验证的链路：App 里说过「下周三要去医院拔智齿」→ 把 App 侧会话消息全删空 → 在 QQ 里问
> 「我下周三是有什么事来着？」仍答出「下周三上午，你要去医院拔智齿」。

## 1. 它解决了什么

| 老路（桌面 NapCat） | 官方机器人 |
|---|---|
| 用户装 QQNT + 下载 28MB NapCat | 什么都不用装，手机 QQ 就是客户端 |
| 要一个小号，扫码登录 | 扫码添加「予念」即可 |
| 电脑必须开着 | 云端 7×24 |
| 违反 QQ 协议，可能封号 | 官方通道 |
| 换 QQ 版就崩（构建号耦合） | 腾讯自己维护 |

代价也明确：**角色外壳是统一的**（所有用户看到同一个机器人昵称/头像），自建角色只能「借这张嘴说话」；
发送侧有限制，见 §7。

## 2. 链路（两跳，中间是签名内网接口）

```text
手机 QQ ──腾讯 WS 网关──▶ lianyu-qqbot 容器（node:22-alpine，--network host）
                            ├─ src/qqbot/gateway.js   Hello / Identify / 心跳 / Resume
                            ├─ src/qqbot/handler.js   事件 → 翻译：6 位码走 bind，其余走 turn
                            ├─ src/qqbot/api.js       取 access_token、发 C2C / 群消息
                            └─ src/qqbot/backend.js   HMAC 签名 → http://127.0.0.1:8080/internal/qq/*
                                                        │
                                       Spring backend（InternalQqController）
                                                        │
                              QqChannelService：openid → (userId, characterId, conversationId)
                                                        │
                                           予念回合（prompt / 记忆 / 人设 / 关系）
```

**为什么要两跳而不是让网关直连 `/api/conversation`**：

1. 网关在公网，是最容易被攻击的一层。它一旦持有用户 `lianyu-token`，等于把一堆账号凭据摊在公网机器上。
2. 绑定关系是事实数据。按仓库三层边界（`AGENTS.md` §2）它属于 Java + MySQL，不属于网关的某个 json 文件。
3. 顺带把「换号 / 解绑 / 一个角色多渠道 / 后台排查」全留在 Java 侧，网关不用管。

网关启动日志会明说这一点：

```
[qqbot] 已启动：appId=1906414199 后端=http://127.0.0.1:8080（无用户凭据，身份由后端解析）
```

记忆的键始终是 `(userId, characterId)`，与入口无关——这是「换电话线不丢记忆」的原因。

## 3. 绑定：唯一新增的用户动作

```text
App（#/app/qq-chat）→ 选角色 → 生成 6 位码（10 分钟有效）
                                   ↓ 用户把它发给机器人
腾讯 → 网关 → POST /internal/qq/bind {channel, externalId, code}
                                   ↓
                qq_bind_code 抢占式消费 → 落 qq_channel_binding → 复用/新建 SINGLE 会话
```

- 绑定成功回：「绑好了。接下来你发的话，会由「小满」回你。」
- 码错 / 过期 / 已被用：`9001` / `9002`，后端给的是人话，网关原样转给用户。
- **码错不会吞掉这句话**：绑定失败后仍把它当普通消息继续走对话，否则用户会以为机器人死了。
- 未绑定就说话：回引导语（去 App 里生成绑定码）。
- 绑定不新建会话：`ConversationService.create` 对 SINGLE 会复用已有会话（线上验证 conv 仍是 953）。

## 4. 表与接口

| 对象 | 说明 |
|---|---|
| `qq_channel_binding` | `channel` + `external_id` 唯一；存 `user_id` / `character_id` / `conversation_id` |
| `qq_bind_code` | 6 位码，10 分钟过期，`consumed_at` 抢占式消费（并发只成功一次） |
| `POST /api/qq/bind-code` | App 侧签发绑定码（需登录态） |
| `GET /api/qq/bindings` | 已绑定列表（openid 掩码展示） |
| `DELETE /api/qq/bindings/{id}` | 解绑 |
| `POST /internal/qq/turn` | 网关调：`{channel, externalId, text}` → `{reply, conversationId}` |
| `POST /internal/qq/bind` | 网关调：`{channel, externalId, code}` |

`/internal/**` **不在 Sa-Token 范围内**（`SaTokenConfig` 只 match `/api/**`），所以新增
`InternalSignatureFilter` 兜底，fail-closed：

```text
x-qq-sign = HMAC-SHA256(secret, ts \n nonce \n METHOD \n path \n sha256hex(body))
请求头：x-qq-ts / x-qq-nonce / x-qq-sign     时间窗 ±300s，nonce 去重
```

- 不带 body 摘要就会允许「换 body 重放」——绑定码能被别人捡走，所以必须带。
- `secret` 未配置时一律 **503**（而不是放行）。
- 验证方式：`POST /internal/qq/turn` 不带签名 → **401**。

## 5. 开放平台要配的东西

| 位置 | 值 |
|---|---|
| 开发设置 → AppID / AppSecret | 取授权用；Secret 点「查看手动连接参数」，只显示一次 |
| **开发设置 → 事件订阅与回调地址** | 必须选 **WebSocket**（选 Webhook 则连上网关也收不到事件） |
| 服务器 IP 白名单 | **留空 = 全部放行**；设了就只有白名单能调 OpenAPI（≤50 个 IPv4，不支持 CIDR） |
| 内部体验号码 | 上线前测对话能力，最多 20 个 |
| 服务范围 | 未认证只能加自己当群主的群 |

## 6. 部署

生产上是单容器 + 宿主网络（后端发布在 `127.0.0.1:8080`，容器直连即可，不用绕公网与自签证书）：

```bash
docker run -d --name lianyu-qqbot --restart unless-stopped --network host \
  --env-file /opt/lianyu-qqbot/qqbot.env \
  -v /opt/lianyu-qqbot/app:/app:ro -v /opt/lianyu-data/qqbot:/data -w /app \
  node:22-alpine node scripts/qqbot-run.mjs
```

`qqbot.env`（chmod 600）：`QQ_APPID` / `QQ_SECRET` / `QQ_GATEWAY_SECRET` /
`LIANYU_API_ORIGIN=http://127.0.0.1:8080`。后端侧同名密钥叫 `LIANYU_QQ_GATEWAY_SECRET`
（compose 的 `env_file: .env` 带进容器，宽松绑定到 `lianyu.qq.gateway-secret`）。

- **env 只在容器创建时读一次**：改密钥必须 `docker rm -f` 再 `docker run`。
- **同一个 bot 只能有一个 WS 连接**：本机再起一个会抢事件（表现为回复时有时无）。切环境前先杀旧的。
- 后端重建走「build 与 up 两步」（`docker compose build backend` → `up -d --no-build`），
  单条 `up --build` 在服务器上会挂死（`AGENTS.md` §6 有记录）。

只想验证「QQ → 我 → QQ」而不接云端、不烧模型：`node scripts/qqbot-run.mjs --echo`，收到什么原样回。

## 7. 官方限制（设计时要低头的地方）

- **被动回复窗口**：单聊 60 分钟 / 群 5 分钟（发消息参数表另写 5 分钟，双口径并存 → 按「尽快回」做）。
- **同一条用户消息最多回 4 次**（C2C）/ 5 次（群）。多气泡拟人化最多 4 段。
- 相同 `msg_id + msg_seq` 重发会被去重拒掉（`40054005`）→ 多段回复必须递增 `msg_seq`。
- 主动消息（`is_wakeup`）是「互动召回」：用户主动对话后 30 天内 4 个周期各 1 条，且用户可在 QQ 里关掉。
- 内容受官方审核（`1100101` 内容敏感）；群聊要 @ 机器人，或群主授权「接收所有消息」。

## 8. 已踩过的坑（都写进了测试）

| 现象 | 根因 | 修法 |
|---|---|---|
| QQ 一直「正在输入」，日志 `bad_response_400` | `ensureConversation()` 返回的是会话对象，被整块当 id 落库 → `POST /api/conversation/[object Object]/messages` | `normalizeConversationId()` 收敛成数字，写坏的老数据自愈 |
| 机器人回一句 `[object Object]` | `/messages` 返回整条消息对象，`String(reply)` 把对象当字符串 | `replyText()` 只取 `content`，取不到按 `empty_reply` 跳过 |
| `token_rejected_100016` | AppSecret 里的数字 `0` 被抄成字母 `O` | 逐字符核对 |
| 取 token 报错却拿不到原因 | **失败也返回 HTTP 200**，原因在 `body.code` | 只认 `body.code`（10004/10007/10016/100001） |
| 改完文件整片乱码 / 提交标题带 `﻿` | PowerShell 5.1 按 GBK 读 UTF-8，`Set-Content` 又加 BOM | 文本一律用编辑器工具改，提交信息文件也用无 BOM 方式写 |
| 消息时有时无 | 同一个 bot 起了两个网关实例抢事件 | 切环境前先杀旧进程/旧容器 |

## 9. 表情包：先说话，再甩一张图

角色能在回完文字后补发一张图。三个环节各归其位：

| 环节 | 在哪 | 做什么 |
|---|---|---|
| 素材是事实 | `character_sticker` 表 + `StickerService.candidatesFor()` | 按角色取候选（≤20 张），并附上图片字节（≤8 张、单张 ≤200KB） |
| 发不发、发哪张 | Python `app/sticker_review.py` | 让视觉模型看着候选图 + 当前两句对话，选一张或者一张都不发 |
| 落库与投递 | `ConversationService.saveAssistantReplies()`（图挂最后一段）+ 网关 `sendImageIfAny()` | 图只跟着最后一句走，QQ 侧当普通图片发 |

选择顺序（`choose_sticker()`）：

1. 用户明确要图（「发个表情包」「来张图」）→ 记 `explicit`，**即使模型说别发也照样发一张**；
2. 视觉复核给出结论 → 按结论发/不发；
3. 复核不可用（没配视觉模型、模型报错、候选没带字节）→ 退回情绪词表（`app/stickers.py`）。

开关：`LIANYU_AGENT_STICKER_REVIEW_ENABLED` / `_MAX_CANDIDATES` / `_MAX_TOKENS`（生产三个都在 agent 容器里）。
日志：每轮一行 `sticker.chosen source=review|keywords url=… explicit=… frequency=… reason=…`，或 `sticker.skipped … reason=…`。

### 9.1 频率三档（角色设置）

角色设置里的「表情包频率」存 `character.settings.stickerFrequency`，与免打扰/内心独白同一套 JSON，
不需要迁移；解析只有一处：`CharacterPreferenceResolver.stickerFrequency()`。

| 档位 | 值 | 行为 |
|---|---|---|
| 从不发 | `never` | 直接不发，**连用户点名要也不发**，且不跑复核（用户自己关的） |
| 正常 | `normal` | 默认：复核判断 + 词表阈值 2 分 |
| 非常爱发 | `frequent` | 复核提示追加「勉强贴得上就发」，词表阈值降到 1 分（只命中标签也发） |

改档位后下一轮就生效（每轮都从角色设置里读），不需要重启服务。

已知限制：

- 每轮最多 1 张，且与文字共用「同一用户消息最多回 4 条」的额度；
- 流式回合（桌面端聊天）目前不落 sticker，只有非流式回合（QQ 渠道）会把图存进 `message.image_url`；
- 图必须公网可达：网关会用后端域名把 `/api/public/files/...` 补成绝对 URL，再走官方「先上传换 file_info」两步发图。

## 10. 还没做的

1. **主动消息**：用户没说话时角色先开口（官方叫「互动召回」，需申请额度，且用户可在 QQ 里关掉）。
2. **一个 QQ 绑多个角色 / 会话内切换**：现在一个外部身份只对应一个角色。
3. **群聊策略**：群白名单、是否只认 @、群里多角色怎么分（目前群按 `group:member` 各算一个人）。
4. **可观测**：只有 stdout 日志，缺回复耗时、失败率、`trace_id` 关联；线上目前靠 `docker logs`。
5. **网关单点**：一个 bot 一条 WS 连接；要扩容得用 shard + 多实例，且事件分片后处理要无状态。
6. **桌面端入口未发版**：`#/app/qq-chat` 现在只在本地构建里，用户装的客户端还没有。

---

## 11. 每人一个机器人：扫码连接（方案 1，2026-11-16 上线）

§1–§10 写的是**一个公共机器人**（「甘雨」）服务所有人：用户只需要发一个 6 位绑定码。
上线后改成**用户自己建一个机器人**，他手机 QQ 里显示的是他自己起的名字（「小满」「某某」），
我们服务的是这些机器人背后的用户。

### 11.1 为什么换

| 维度 | 公共机器人 | 每人一个（现在） |
|---|---|---|
| QQ 里显示的名字/头像 | 固定「甘雨」，所有人一样 | 用户自己起的，能叫角色名 |
| 用户动作 | 发 6 位码 | 扫码（10 秒，腾讯页面上就能建号） |
| 我们的凭据风险 | 只有我们自己的一把 secret | 存的是**用户自己的** secret，泄露就是他的号 |
| 封号/内容风险 | 全平台共担一个号 | 各担各的，一个被封不影响别人 |
| 连接数 | 1 条 | 每个 bot 一条 WS（见 §11.6 规模提醒） |

### 11.2 扫码协议（从官方 SDK 反查，不用装 SDK）

1. `POST https://q.qq.com/lite/create_bind_task`，body `{key: base64(32 随机字节)}` → `{task_id}`。
   这个 `key` 是我们临时生成的 AES 钥匙，**只留在后端内存**。
2. 把 `https://q.qq.com/qqbot/openclaw/connect.html?task_id=<id>&source=yuni-pc&_wv=2` 画成二维码。
3. `POST https://q.qq.com/lite/poll_bind_result`，body `{task_id}` →
   `{status: 0|1|2|3, bot_appid, bot_encrypt_secret, user_openid}`（0 NONE / 1 PENDING / 2 COMPLETED / 3 EXPIRED）。
4. COMPLETED 时用第 1 步的钥匙解 `bot_encrypt_secret`：AES-256-GCM，payload = `base64(IV(12)+ciphertext+tag(16))`。

拿到 `appId / appSecret / user_openid` 之后，这个用户和这个机器人的关系就确定了 ——
**6 位绑定码整个删掉了**，因为腾讯在扫码那一刻就把「谁在说话」告诉了我们。

### 11.3 凭据放哪

`qq_bot_account`（V62）：`user_id / app_id / app_secret_cipher / external_openid / character_id / status`。
`app_secret_cipher` 是 Jasypt 加密后的密文，**只有 Java 能解**；网关要发消息时调
`POST /internal/qq/token`（签名内网接口）换 access_token，secret 不出后端。

> 重新扫同一个 bot 会**重置它的 secret**，旧 token 立刻失效 —— 所以 `completed` 时必须覆盖密文，
> 且网关侧遇到 401 要强制换 token 重试（已经这么做了，见 §11.5）。

### 11.4 连接池（`channel-gateway/src/qqbot/pool.js`）

网关不再只知道一个 bot：它每 30 秒 `POST /internal/qq/accounts` 拉一次「谁该在线」，多退少补。

- 新增账号 → 起一条 WS；账号消失（用户解绑）→ 停掉那条 WS。
- 换绑角色 → 只更新 `characterId`，**不重开连接**（换了连接用户正在打的字就断了）。
- 后端抖动 → 只记日志，保留现有连接（不能让一次 502 把所有人踢下线）。
- 单个机器人连不上 → 只影响它自己，失败项先从 `live` 摘掉，下一轮 30 秒自动重试。

启动形态：`QQ_POOL=1` + `QQ_GATEWAY_SECRET`，容器 `lianyu-qqbot-pool`。

### 11.5 踩过的坑（都已加测试）

1. **`onEvent` 签名错位**：`gateway` 是 `onEvent(type, event)`，池子里写成 `(event) => handler.handle(event)`，
   handler 收到的是字符串 `"C2C_MESSAGE_CREATE"` → `event.openid` 为空 → 每条消息被**静默丢弃**。
   现象是「WS 明明在线、事件也打出来了，就是不回」。现在缺 openid 会打日志。
2. **token 是 Promise**：`accessToken()` 没 await 就拼进请求头 → `QQBot [object Promise]` → 401。
3. **一个挂全挂**：某 bot 启动失败让整个 node 进程退出（其它用户跟着掉线），现在被 catch 隔离。
4. **重连不排队**：401 之后不再重试 → 用户在 QQ 里永远看到「连接中」，现在退避重排队。
5. **nginx 缓存 upstream IP**：重建 backend 后全站 502，`nginx -s reload` 才恢复（见 `AGENTS.md` §7）。

### 11.6 规模提醒

一个 bot 一条 WS 连接，池子是**单进程**。几百个 bot 以内没问题；再往上要 shard + 多实例，
且事件分片后处理必须无状态（目前 handler 的 msgId 去重环在内存里，届时得换 Redis）。

