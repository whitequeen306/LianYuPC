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

## 9. 还没做的

1. **主动消息**：用户没说话时角色先开口（官方叫「互动召回」，需申请额度，且用户可在 QQ 里关掉）。
2. **一个 QQ 绑多个角色 / 会话内切换**：现在一个外部身份只对应一个角色。
3. **群聊策略**：群白名单、是否只认 @、群里多角色怎么分（目前群按 `group:member` 各算一个人）。
4. **可观测**：只有 stdout 日志，缺回复耗时、失败率、`trace_id` 关联；线上目前靠 `docker logs`。
5. **网关单点**：一个 bot 一条 WS 连接；要扩容得用 shard + 多实例，且事件分片后处理要无状态。
6. **桌面端入口未发版**：`#/app/qq-chat` 现在只在本地构建里，用户装的客户端还没有。
