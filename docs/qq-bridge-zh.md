# LianYu ↔ QQ 桥接（NapCat 正向 WebSocket）

让 LianYu 通过 [NapCat](https://github.com/NapNeko/NapCatQQ) 接入 QQ，把 QQ 私聊 / 群 @ 消息路由进 `ConversationService.sendMessage`，由角色 AI 回复后再发回 QQ。

> **当前阶段：Phase 1 —— 单人模式。** 一个 LianYu 用户 ↔ 一个 QQ 号 ↔ 一个会话。
> Phase 2（多人公开机器人）见文末路线图，未实现。

---

## 0. 一句话架构

```
QQ 客户端 ──(QQ 协议)──> NapCat ──(OneBot 11 / 正向 WS)──> lianyu-qq-bridge（本进程内）
                                                                   │
                                                                   │ ApplicationEvent(异步)
                                                                   ▼
                                                        QqBridgeTurnHandler
                                                                   │
                                                                   │ ConversationService.sendMessage(userId, conversationId, req)
                                                                   │   —— 直接传 Long userId，绕开 HTTP / Sa-Token / lianyu-token
                                                                   ▼
                                                        AiChatService.chatBlocking → 多段回复
                                                                   │
                                                                   ▼
                                                        NapCatClient.sendPrivateMsg / sendGroupMsg
```

**核心设计：进程内桥接，零侵入主链路。** 桥接器是一个普通 Spring 模块（`lianyu-qq-bridge`），被 `LianYuApplication` 的 `scanBasePackages = "com.lianyu"` 自动扫描。它直接调用 `ConversationService` 的 Service 层方法（这些方法本来就接受 `Long userId` 显式参数），**完全不经过** Controller / `HeaderOnlyTokenFilter` / Sa-Token / `lianyu-token` / nginx。因此对 [安全耦合地图](./security-coupling-map-zh.md) 里的 8 条 lockstep 规则**全部无影响**（见 §3 影响表）。

---

## 1. 依赖与外部组件

| 组件 | 说明 | 许可 / 风险 |
|------|------|------------|
| **NapCat** | QQ 协议端，跑在本机或内网，对外暴露一个正向 WebSocket Server。 | 自定义 `NOASSERTION` 许可：禁止修改 / 再分发 / 商用。我们**只运行它 + 使用标准 OneBot 11 协议**，不引入其代码、不分发它，故**无传染**。 |
| **OneBot 11** | NapCat 实现的即时通讯协议标准（消息事件 / API 调用 / 消息段）。 | 公开标准，无许可约束。 |
| **JDK `java.net.http.WebSocket`** | 桥接器用的 WS 客户端。 | **零第三方依赖**，不往项目加任何 jar。 |

> ⚠️ **QQ 封号风险（务必先读）**：NapCat 是第三方协议客户端，**违反 QQ 用户协议**，用于协议机器人可能导致该 QQ 账号被腾讯风控、限制登录甚至**永久封号**。请**务必使用一次性小号**，不要用主号。本桥接器无法规避此风险，风险由部署者自负。

---

## 2. 模块结构

```
backend/lianyu-qq-bridge/
├── pom.xml                      # 依赖：lianyu-service + spring-boot-starter（无新第三方库）
└── src/main/java/com/lianyu/qqbridge/
    ├── config/
    │   ├── QqBridgeComponent.java     # 元注解：@Component + @ConditionalOnProperty(enabled=true)
    │   └── QqBridgeProperties.java    # @ConfigurationProperties(prefix="lianyu.qq-bridge")
    ├── napcat/
    │   ├── OneBotModels.java          # record：Segment / Sender / MessageEvent / ApiResponse
    │   ├── OneBotMessageEvent.java    # extends ApplicationEvent，解耦 WS 客户端 ↔ 回调
    │   └── NapCatClient.java          # 正向 WS 客户端：连接 / 心跳 / 重连 / API 调用 / 事件分发
    └── bridge/
        ├── MessageSegmentExtractor.java  # OneBot 消息段 → 纯文本（剥 @自己）
        └── QqBridgeTurnHandler.java      # @Async @EventListener：过滤 → sendMessage → 回发
```

**装配门控**：所有活跃 Bean 都标了 `@QqBridgeComponent`（即 `@ConditionalOnProperty(prefix="lianyu.qq-bridge", name="enabled", havingValue="true", matchIfMissing=false)`）。
→ `lianyu.qq-bridge.enabled=false`（默认）时，**整个模块一个 Bean 都不装配**，对主流程零影响，等于没这个模块。

**解耦设计**：
- `NapCatClient` ↔ `QqBridgeTurnHandler` 存在循环依赖（handler 要回发消息需注入 client）。用 **Spring `ApplicationEvent`** 打断：`NapCatClient` 收到消息事件后 `publishEvent(OneBotMessageEvent)`，`QqBridgeTurnHandler` 用 `@EventListener` + `@Async` 异步消费，单向注入 `NapCatClient`。
- `@Async` 让多秒级 LLM 回合**不阻塞** WebSocket 帧线程。
- 每会话一把 `ReentrantLock`（`ConcurrentHashMap<Long,ReentrantLock>`），**串行化**同一会话的回合，防止消息交错。

---

## 3. 安全耦合影响表（8 条 lockstep 规则）

对照 [安全耦合地图 §0 速查表](./security-coupling-map-zh.md)。**Phase 1 全部「未触碰」**：

| # | 规则 | 本桥接器是否触碰 | 说明 |
|---|------|:---:|------|
| 1 | `RUNTIME_SECRETS_PEPPER` + `PINNED_SPKI` 双端硬编码一致 | ❌ 未触碰 | 桥接器不经 pack / runtimeSecrets，纯后端进程内调用。 |
| 2 | 换 TLS 证书 → 同步 SPKI 两处 + 重打 bin | ❌ 未触碰 | 同上，不涉及证书 pin。 |
| 3 | 换 apiOrigin → 同步 nginx CORS + 证书 pin + 重打 bin | ❌ 未触碰 | 桥接器**不走 HTTP / nginx**，直接调 Service。 |
| 4 | `lianyu-token` 名字贯穿 6 处 | ❌ 未触碰 | 桥接器**不读 token、不发 token、不校验 token**。 |
| 5 | observe 鉴权链前后端同发 | ❌ 未触碰 | 与 observe 无关。 |
| 6 | 绝不改已发布 Flyway 迁移 | ❌ 未触碰 | Phase 1 **无迁移**（复用已有用户 / 会话）。Phase 2 仅**新增** `V35__`。 |
| 7 | 轮换 `LIANYU_MASTER_KEY` 须重加密 vault 密文 | ❌ 未触碰 | 桥接器用 `provider=platform`，复用运营方共享 key，不碰个人 vault。 |
| 8 | 改对象 key 前缀须同步 `PublicFileController` | ❌ 未触碰 | Phase 1 不处理 QQ 图片（纯文本）。 |

### 🚫 绝对禁止（给后续维护者）

> 这些操作会让上面的「未触碰」失效，触发 §4 / §5 耦合链断裂：

1. **禁止给 `lianyu-qq-bridge` 加任何 HTTP 端点**（Controller / `@RestController`）。一旦暴露 HTTP，就接入 nginx + `lianyu-token` + Sa-Token 链，违反 §3 / §4。
2. **禁止把桥接路径加进 Sa-Token `notMatch` 列表**。桥接根本不经过 Sa-Token，加进去只会制造「为什么 notMatch 里有却没路由」的困惑，并暗示这条路径受 Sa-Token 管辖（其实不是）。
3. **禁止让桥接器读取 / 生成 / 校验 `lianyu-token`**。`ConversationService` 的 Service 层方法签名是 `(Long userId, ...)`，直接传 Long，这就是绕开 token 的合法入口。
4. **禁止把 NapCat 二进制打进 LianYu 发行包**。NapCat 许可禁止再分发；部署者自行安装 NapCat。

---

## 4. 配置项（`lianyu.qq-bridge.*`）

全部位于 `backend/lianyu-app/src/main/resources/application.yml` 的 `lianyu.qq-bridge` 下。**默认 `enabled: false`**。所有敏感值走 `${ENV}` 占位符。

```yaml
lianyu:
  qq-bridge:
    enabled: ${LIANYU_QQ_BRIDGE_ENABLED:false}      # 总开关，默认关
    napcat:
      ws-url: ${LIANYU_QQ_NAPCAT_WS_URL:ws://127.0.0.1:3001}   # NapCat 正向 WS 地址
      access-token: ${LIANYU_QQ_NAPCAT_ACCESS_TOKEN:}          # 与 NapCat 一致；空=不携带
      connect-timeout-seconds: 10                  # 连接超时
      heartbeat-timeout-seconds: 90                # 心跳判活阈值（秒）
    binding:
      qq-user-id: ${LIANYU_QQ_BINDING_QQ_USER_ID:}            # 【单人】白名单 QQ 号
      lianyu-user-id: ${LIANYU_QQ_BINDING_LIANYU_USER_ID:}    # 映射到的 LianYu 用户
      conversation-id: ${LIANYU_QQ_BINDING_CONVERSATION_ID:}  # 固定复用的会话
      provider: platform                           # 固定 platform（共享运营 key）
      model: ${LIANYU_QQ_BINDING_MODEL:}           # 指定模型；空=走 provider 默认
      allow-groups: ${LIANYU_QQ_BINDING_ALLOW_GROUPS:}        # 逗号分隔群号；空=不接群
    reply:
      send-all-pieces: true                        # 多段回复全发；false=只发首段
      max-piece-gap-ms: 800                        # 段间间隔（毫秒）
      max-piece-chars: 2000                        # 单段最大字符（截断）
      fallback-text: "（……她似乎走神了，稍后再试试）"   # 异常兜底
```

| 字段 | 必填 | 默认 | 说明 |
|------|:---:|------|------|
| `enabled` | — | `false` | 总开关。关 = 模块不装配。 |
| `napcat.ws-url` | 开时必填 | `ws://127.0.0.1:3001` | NapCat 的 WebSocket Server 地址。 |
| `napcat.access-token` | 否 | 空 | 与 NapCat `network.websocketServers[].token` 一致；空则连接不带 `access_token`。 |
| `napcat.connect-timeout-seconds` | — | `10` | 单次连接 / 重连超时。 |
| `napcat.heartbeat-timeout-seconds` | — | `90` | 超此秒数未收任何帧即判死并重连（NapCat 默认 30s 心跳，90s = 漏 3 跳）。 |
| `binding.qq-user-id` | 单人模式必填 | 空 | 仅处理来自此 QQ 的私聊。 |
| `binding.lianyu-user-id` | 开时必填 | 空 | 映射到的真实 LianYu 用户（其角色 = 对外角色）。 |
| `binding.conversation-id` | 开时必填 | 空 | 复用的会话 ID（单人模式固定一个）。 |
| `binding.provider` | — | `platform` | 固定 `platform`，走运营方共享 key，QQ 用户无需个人 key。 |
| `binding.model` | 否 | 空 | 指定模型；空走 provider 默认。 |
| `binding.allow-groups` | 否 | 空 | 逗号分隔群号（如 `111,222`）；空 = 不处理任何群。 |
| `reply.send-all-pieces` | — | `true` | 是否发全部段。 |
| `reply.max-piece-gap-ms` | — | `800` | 段间间隔。 |
| `reply.max-piece-chars` | — | `2000` | 单段截断上限。 |
| `reply.fallback-text` | — | 见上 | 异常 / 无回复兜底。 |

---

## 5. NapCat 安装与网络配置（WebUI）

### 5.1 安装 NapCat

按 [NapCat 官方文档](https://napneko.github.io/) 在本机或内网机器安装并登录**一次性小号**。登录后打开 NapCat WebUI（默认 `http://127.0.0.1:6099`）。

### 5.2 网络配置：新建一个正向 WebSocket Server

在 WebUI「网络配置」里**新增**一条，类型选 **WebSocket 服务器（正向 WS）**，按下表填：

| 字段 | 推荐值 | 说明 |
|------|--------|------|
| 名称 | `lianyu-bridge` | 任意，仅标识。 |
| 启用 | ✅ | |
| 监听 host | `0.0.0.0` 或 `127.0.0.1` | 桥接器同机用 `127.0.0.1`；跨机用 `0.0.0.0` + 防火墙。 |
| 监听端口 | `3001` | 与 `ws-url` 对应。 |
| 消息上报格式 | **array** | 桥接器按消息段数组解析（`message:[{type,data}]`）。**不要选 string**。 |
| 报告自身消息 | `false` | 不需要机器人自己发的消息回环。 |
| 心跳间隔 | `30000` | 毫秒，NapCat 默认 30s。 |
| Token | 自定义一串 | 填了就把同一串放进 `napcat.access-token`；不填则两侧都空。 |

保存后 NapCat 即开始监听该端口，等待桥接器连接。

### 5.3 让桥接器连上来

在 LianYu 侧设置环境变量并启用：

```bash
# .env（示例，单人模式）
LIANYU_QQ_BRIDGE_ENABLED=true
LIANYU_QQ_NAPCAT_WS_URL=ws://127.0.0.1:3001
LIANYU_QQ_NAPCAT_ACCESS_TOKEN=你在NapCat里填的token
LIANYU_QQ_BINDING_QQ_USER_ID=10001            # 你的小号 QQ
LIANYU_QQ_BINDING_LIANYU_USER_ID=1            # LianYu 里已存在的用户
LIANYU_QQ_BINDING_CONVERSATION_ID=42          # 已存在的会话 ID
# 群聊（可选，逗号分隔）：
# LIANYU_QQ_BINDING_ALLOW_GROUPS=111,222
```

启动 LianYu 后端。日志出现 `NapCatClient connected ... selfId=...` 即成功。用小号给机器人发私聊，角色应回复。

---

## 6. 消息处理流程（单人模式）

1. **收消息**：`NapCatClient` 的 WS 监听器收到 OneBot `post_type=message` 事件 → `publishEvent(OneBotMessageEvent)`。
2. **过滤**（`QqBridgeTurnHandler.handle`）：
   - 私聊：仅当 `user_id == binding.qq-user-id` 才处理，其余忽略。
   - 群聊：仅当 `group_id ∈ binding.allow-groups` **且** 消息 @了机器人自己才处理。
3. **提文本**：`MessageSegmentExtractor.toPlainText` 把消息段数组转纯文本（`@机器人` 被剥离，`@他人` 保留为 `@<qq> `，图片 / 表情 / 回复 / 合并转发等忽略）。
4. **记锚点**：`conversationService.getMessages(userId, conversationId, null, 1)` 取回复前最新一条的 `seq` 作为 `prevMaxSeq`。
5. **跑回合**：构造 `SendMessageRequest(provider=platform, model, content=文本)`，调 `conversationService.sendMessage(userId, conversationId, req)` —— 走完整记忆检索 → 关系更新 → `AiChatService.chatBlocking` → 持久化全部分段回复。
6. **取新回复**：`getMessages(userId, conversationId, null, 20)` 过滤 `seq > prevMaxSeq && role==ASSISTANT`，按 seq 升序。
7. **回发**：按 `max-piece-gap-ms` 节奏逐段 `sendPrivateMsg` / `sendGroupMsg`，单段超 `max-piece-chars` 截断。
8. **异常兜底**：任何步骤抛异常 → 发 `reply.fallback-text`，不让用户对着静默发呆。

> 多段回复问题**未改 `ConversationService`**：`sendMessage` 内部已把多段全部持久化，但只返回首段。这里用「记锚点 seq → 事后按 seq 增量取 ASSISTANT 段」的方式拿到全部段，零侵入核心代码。

---

## 7. 运维要点

- **断线重连**：`NapCatClient` 用指数退避（1s → 30s 封顶）自动重连；连接成功后 `attempt` 归零。
- **心跳判活**：调度任务每 30s 检查「距上次收到任意帧的时长」，超 `heartbeat-timeout-seconds` 即主动断开走重连。
- **`selfId`**：连接成功后调 `get_login_info` 拿到机器人自身 QQ 号，存为 `selfId`，用于剥离 `@机器人`。
- **优雅关闭**：`@PreDestroy` 关 WS + 关线程池。
- **日志**：包路径 `com.lianyu.qqbridge` 走全局 `logging.level.com.lianyu: DEBUG`，连接 / 重连 / 每回合均有日志。

---

## 8. Phase 2 路线图（多人公开机器人，未实现）

> 仅作规划记录，**当前未实现**。Phase 2 仍须守住 §3 耦合表「未触碰」与 §3 禁止项。

1. **Flyway `V35__create_qq_user_binding.sql`**（**只新增**，符合规则 6）：表 `qq_user_binding(qq_user_id BIGINT PK, lianyu_user_id BIGINT, conversation_id BIGINT, created_at ...)`。
2. **`QqUserProvisioner`**：首次收到某 QQ 私聊时，自动建 LianYu 用户 + 会话 + 角色克隆，写绑定表。建用户逻辑镜像 `AuthServiceImpl` 现有用户插入（**不改 AuthService**，独立 mapper 调用）。
3. **角色克隆**：从模板角色复制一份给新用户，避免共用一个角色导致多人记忆串台。
4. **群 @ 触发**：群消息 @机器人即触发该 QQ 绑定用户的回合。
5. **限流 / 黑名单**：防刷；多人下 AI 并发受 `resilience4j.bulkhead.ai-chat` 全局池约束（已存在，无需新增）。

---

## 9. 故障排查

| 现象 | 排查 |
|------|------|
| 启动后无 `connected` 日志 | 检查 `enabled=true`、`ws-url` 可达、NapCat 端口 / token 一致；看 `NapCatClient` 日志的连接异常。 |
| 连上但发消息无回复 | 私聊：确认发送方 QQ == `binding.qq-user-id`。群聊：确认群号在 `allow-groups` 且消息 @ 了机器人。看 DEBUG 日志是否被过滤。 |
| 回复只有一段 | `reply.send-all-pieces` 是否 `true`；或 AI 确实只回了一段。 |
| 一直发兜底文案 | LianYu 侧 AI 调用失败：查 `AiChatService` / 平台 key / `resilience4j` 熔断状态。 |
| NapCat 离线 / QQ 掉线 | NapCat WebUI 看登录状态；小号可能被风控，换号。 |

---

## 10. 参考

- [OneBot 11 标准](https://github.com/botuniverse/onebot-11)
- [NapCat 文档](https://napneko.github.io/)
- [LianYu 安全耦合地图](./security-coupling-map-zh.md)
