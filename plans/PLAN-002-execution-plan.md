# PLAN-002: PC 端 8 阶段执行计划

**Status**: Phase 1-6 complete (2026-05-24)
**Date**: 2026-05-24
**Owner**: planner
**Depends on**: PLAN-001（技术栈终版）
**Project Root**: `C:\Users\hp\Desktop\LianYu-PC\`

> 原则：不设时间表，按出口标准推进；前一阶段未达出口不开下一阶段；每个阶段以 checker 智能体验收作为闭环。**绝不修改安卓端项目目录**，安卓端只作为 read-only 参考资料。

## Phase 总览

| # | 阶段 | 主负责智能体 | 入口依赖 | 出口标准 |
|---|---|---|---|---|
| 0 | 技术栈与计划确认 | planner | — | PLAN-001/002 经 review 通过 |
| 1 | 后端基础设施 | backend | PLAN-001 |  Sa-Token 登录注册 + 各中间件连通 |
| 2 | AI 接入层 | backend | Phase 1 | 流式对话 curl 跑通 |
| 3 | 角色与对话核心 | backend | Phase 2 | 单聊多轮上下文不丢 |
| 4 | 记忆与群聊 | backend | Phase 3 | 群聊三角色互相对话；记忆能召回 |
| 5 | 前端基础 | frontend | Phase 1+2 | 登录后能配 provider 保存 |
| 6 | 前端核心功能 | frontend | Phase 3+4+5 | 单聊/群聊/记忆三个页面用户体验对齐安卓 |
| 7 | 本地模型集成 | backend + frontend | Phase 6 | 离线状态下能用 Ollama 跑对话 |
| 8 | 打磨与发布 | checker + coder | Phase 7 | 测试覆盖率达标 + Docker Compose 一键起 + 文档完整 |

---

## Phase 0 — 技术栈与计划确认

**已完成**

- ✅ 用户确认 13 项技术栈
- ✅ 用户确认 8 阶段结构
- ✅ 用户确认模块映射策略
- ✅ 用户确认 PC 项目根目录：`C:\Users\hp\Desktop\LianYu-PC\`
- ✅ claude-review 审查技术选型合理性（已通过，Conditional Approval，改动已全部落实）
- ✅ 用户授权 Phase 1-4 启动
- ✅ 后端 Phase 1-4 骨架完成 + 细节完善（2026-05-24）

**出口**：PLAN-001 + PLAN-002 通过双 review，用户授权 Phase 1 启动。

---

## Phase 1 — 后端基础设施 ✅

**状态**：骨架完成 + 细节已完善

**目标**：搭好"能跑空架子"，所有中间件接通，登录注册可用。

**模块**：

1. **PC 项目根目录脚手架**：在 `C:\Users\hp\Desktop\LianYu-PC\` 下建 `backend/`、`frontend/` 占位、`docker-compose.yml`、根 `README.md`、`CLAUDE.md`（PC 端工作指引，独立于安卓端那份）
2. **Maven 多模块脚手架**：parent + 7 子模块（见 PLAN-001 §2.1）
3. **MySQL schema 初版**：执行 PLAN-001 §8 的 8 张表 DDL；migration 用 Flyway
4. **Sa-Token 登录注册**：`/auth/register`、`/auth/login`、`/auth/logout`、`/auth/me`
5. **BCryptPasswordEncoder** 注入用户服务
6. **Jasypt 字段加密**：自定义注解 `@EncryptedField`，主密钥从 `LIANYU_MASTER_KEY` 环境变量读取
7. **Redis 接入**：Sa-Token Token 持久化 + 业务缓存
8. **RabbitMQ 接入**：声明 3 个交换机 / 队列骨架（暂不发消息）
9. **MinIO 接入**：默认桶创建 + 健康检查
10. **Milvus 接入**：连接 + 集合 `memory_vectors` schema 定义
11. **Knife4j**：开放 `/doc.html`
12. **Actuator + 健康检查**：`/actuator/health` 验证所有中间件连通
13. **统一异常 + 统一返回**：`Result<T>` + 全局 `@ExceptionHandler`

**出口**：
- ✅ `mvn clean package` 成功
- ✅ Docker Compose 5 个中间件 + backend
- ✅ 注册→登录→me→登出 API 可用
- ✅ Swagger UI 查看 auth 接口
- ✅ MyBatis-Plus MetaObjectHandler 时间戳自动填充
- ✅ 消息 seq 使用 Redis INCR（并发安全）
- ✅ EncryptedStringTypeHandler 体系就绪

**智能体**：backend → checker ✅

---

## Phase 2 — AI 接入层 ✅

**状态**：骨架完成 + Resilience4j 容错已集成

**目标**：把 Spring AI ChatClient 多 provider 跑通；流式 SSE 通道可用。

**模块**：

1. **Spring AI 配置**：`application.yml` 多 provider 配置（OpenAI/Gemini/Ollama/Partner-OpenAI兼容）
2. **`AiChatService`**：封装 ChatClient 流式/非流式调用
3. **`/api/ai/models`**：fetchModels 接口（动态拉 provider 模型列表，对应安卓 `AiService.fetchModels`）
4. **`/api/ai/chat/stream`**：SSE 流式响应（`SseEmitter`）
5. **`api_key_vault` CRUD**：用户管理自己的 provider 配置（API Key 字段走 Jasypt）
6. **provider_model_cache**：缓存 fetchModels 结果，Redis TTL 1h

**出口**：
- ✅ curl 流式 SSE `/api/ai/chat/stream` 可用
- ✅ 多 provider 切换（OpenAI 兼容 / Ollama）
- ✅ API Key 数据库密文存储（Jasypt AES-GCM）
- ✅ Resilience4j bulkhead + timeLimiter + circuitBreaker 容错

**智能体**：backend → checker ✅

---

## Phase 3 — 角色与对话核心 ✅

**状态**：骨架完成 + SSE 消息落库 + 头像上传已修复

**目标**：移植安卓端"输入提示词+性格设定→生成可对话角色"全链路。

**模块**：

1. **角色 CRUD**：`/api/character/*`（CRUD + 角色头像上传到 MinIO）
2. **`CharacterPromptBuilder`**：参照安卓端 prompt 模板（researcher 先审计模板字段，把模板**复制**到 PC 项目内，不再依赖安卓目录）
3. **会话管理**：`/api/conversation/*` 创建/列表/删除
4. **消息收发**：`/api/conversation/{id}/messages` 持久化 + 调用 AiChatService
5. **上下文窗口**：默认保留最近 N 条 message + system prompt（character settings 渲染），N 可配
6. **token 统计**：每条 message 写入 `tokens` 字段（Spring AI 提供 usage 信息）

**出口**：
- ✅ 角色 CRUD + 会话管理 + 消息收发
- ✅ CharacterPromptBuilder 动态模板
- ✅ 流式 SSE 对话 + 消息自动落库
- ✅ 角色头像上传 MinIO
- ✅ 上下文窗口管理（可配 N 条历史）

**智能体**：researcher → backend → checker ✅

---

## Phase 4 — 记忆与群聊 ✅

**状态**：骨架完成 + Redis 分布式锁 + Embedding 用户 provider 支持

**目标**：长期记忆 + 群聊两个安卓端核心功能落地。

**模块**：

### 记忆（feature/memory 移植）

1. **embedding service**：Spring AI EmbeddingClient（OpenAI/本地 BGE 二选一）
2. **MemoryWriter**：每 N 条对话或会话结束时，`@Async` 触发摘要 → embedding → 写 Milvus + memory_meta
3. **MemoryRetriever**：检索时先在 Milvus topK，再回 MySQL 取明文摘要 + 注入 prompt
4. **RabbitMQ 异步通道**：摘要生成走 `memory.summary.queue`，避免阻塞对话主链路

### 群聊（feature/groupchat 移植）

1. **WebSocket（STOMP）**：`/ws/group/{conversationId}`
2. **群聊会话**：mode=GROUP 的 conversation + `group_member` 表关联多角色
3. **多角色排队回复**：用户发言后，按队列顺序让每个角色回复（一个角色回复完才轮下一个；可中断）
4. **角色之间引用**：每个角色的 prompt 里注入"你与其他角色 X、Y 共处群聊，他们刚说了..."

**出口**：
- ✅ MemoryWriter（RabbitMQ 异步摘要） + MemoryRetriever（Milvus 向量检索 + rerank）
- ✅ 群聊多角色排队回复（单队列串行）
- ✅ Redis 分布式锁互斥 + 用户新消息可中断
- ✅ Embedding/Reranker 支持用户 vault provider
- ✅ WebSocket STOMP + Sa-Token 认证

**智能体**：researcher → backend → checker ✅

---

## Phase 5 — 前端基础 ✅

**状态**：已完成（2026-05-24）

**目标**：搭好前端架子 + 登录设置闭环。

**模块**：

1. **Vite + Vue3 + Element Plus 工程**（位于 `LianYu-PC/frontend/`）
2. **目录结构**：`api/ components/ layouts/ pages/ router/ stores/ styles/ utils/`
3. **Pinia store**：user / settings / providers
4. **Axios 拦截器**：注入 Sa-Token、统一错误处理、loading
5. **路由守卫**：未登录跳 `/login`
6. **登录注册页**
7. **设置中心**：provider 列表 + API Key 增删改 + 模型选择（API Key 输入框默认 password 类型）
8. **主题变量参考**：researcher 从安卓 `core/ui-common` 提取颜色/字体/圆角/间距，把数值**复制**到 `frontend/src/styles/variables.scss`，覆盖 Element Plus 主题（不引用安卓项目文件）

**出口**：
- ✅ 用户能注册→登录→进入主页→进设置→添加 OpenAI provider→保存→重启浏览器配置仍在
- ✅ UI 主色调、按钮圆角、卡片阴影 与安卓端肉眼一致
- ✅ Vite + Vue 3.5 + Element Plus 2.8 工程创建完毕
- ✅ SCSS 设计变量体系（colors, typography, spacing, shadows, glass effects）
- ✅ 登录/注册页（带表单验证、密码确认、自动登录）
- ✅ 设置中心页（Provider 增删改查、模型列表获取）
- ✅ 首页（时间感知问候、角色/会话统计）
- ✅ 角色管理/对话页（占位，Phase 6 实现）
- ✅ Axios 拦截器（Sa-Token 注入、Result<T> 解包、401 自动跳转）
- ✅ Pinia 三件套（user / providers / settings，含 localStorage 持久化）
- ✅ 路由守卫（Hash 路由，未登录跳 /login）
- ✅ Element Plus 全暗色主题覆盖
- ✅ 布局（可折叠侧边栏 + 玻璃态头部 + 过渡动画）
- ✅ `npm run build` 通过（1674 modules, 5.77s）

**智能体**：frontend → checker

---

## Phase 6 — 前端核心功能 ✅

**状态**：已完成（2026-05-24）

**目标**：把单聊、群聊、记忆三个安卓端核心页面移植到 Web。

**模块**：

1. **角色管理页**：列表 + 创建对话框（含性格设定文本框 + 头像上传）+ 编辑
2. **单聊页**：
   - 左侧角色/会话列表
   - 中间消息流（SSE 流式打字机效果，气泡风格对齐安卓）
   - 右侧角色信息面板
3. **群聊页**：
   - WebSocket 连接 + 自动重连
   - 多角色头像横向排列 + 谁在发言时高亮
4. **记忆查看/编辑页**：
   - 按角色分组展示记忆摘要
   - 支持删除单条记忆
   - 显示该记忆的来源消息

**出口**：
- ✅ 角色管理页：列表 + 创建/编辑对话框（性格设定文本框 + 头像上传 + JSON 设置）
- ✅ 单聊页：三栏布局（对话列表 + SSE 流式消息气泡 + 角色信息面板）
- ✅ 群聊页：WebSocket STOMP 连接 + 自动重连 + 多角色头像发言高亮 + 流式队列
- ✅ 记忆查看页：按角色过滤 + 记忆摘要列表 + 展开查看来源消息 + 删除
- ✅ 路由守卫 + 侧边栏导航更新（群聊、记忆入口）
- ✅ 后端 MemoryController REST API 新增
- ✅ `npm run build` 通过（1681 modules, 5.57s）
- ✅ `mvn package` 通过（编译修复：Resilience4j 2.x API 适配 + JsonProcessingException 处理）

**智能体**：frontend → checker ✅

---

## Phase 7 — 本地模型集成

**目标**：替代安卓端 llama.cpp，用 Ollama 在 PC 上跑本地模型。

**模块**：

1. **后端**：Spring AI 的 Ollama provider 配置 + `/api/ollama/models` (list/pull/delete)
2. **前端**：本地模型管理页（拉取进度条 + 已安装列表 + 一键切换为默认 provider）
3. **离线模式**：在设置里勾选"仅本地模型"后，云端 provider 全部隐藏

**出口**：
- 全程拔掉外网，仍能用本地 Llama3 完成单聊
- 拉取模型时进度条平滑、可取消

**智能体**：backend + frontend → checker

---

## Phase 8 — 打磨与发布

**目标**：交付可用产品。

**模块**：

1. **测试**：
   - 后端 JUnit 5 单元测试（service 层覆盖率 ≥ 70%）
   - 集成测试用 Testcontainers（mysql/redis/rabbitmq/milvus 真实容器）
   - 前端 Vitest 关键组件测试
2. **压测**：
   - 100 并发 SSE 流式对话
   - 50 并发 WebSocket 群聊
3. **Docker Compose 一键编排**：`docker-compose.yml` 含全部 6 个服务
4. **用户文档**：`LianYu-PC/README.md`、部署手册、API 文档（Knife4j 自动生成）
5. **codex-review 终审 + claude-review 架构终审**

**出口**：
- 一行 `docker-compose up -d` 在干净机器上跑通
- 所有 P0 bug 关闭
- 测试覆盖率达标

**智能体**：checker + coder + claude-review + codex-review

---

## 跨阶段横切关注点

| 关注点 | 何时落地 |
|---|---|
| 日志规范（@Slf4j + traceId） | Phase 1 一次到位 |
| 统一错误码 | Phase 1 |
| Knife4j 注解 | 每个新接口当场补 |
| README 同步 | 每阶段结束更新 |
| 安全审查 | 每阶段 checker + 必要时 claude-review |
| OSV-Scanner | 每次 `pom.xml`/`package.json` 变更后必跑 |
| 与安卓端隔离 | 全程，所有读取安卓端文件均为 read-only，禁止写入 |

## 模块映射（与 PLAN-001 §3 对齐）

| 安卓端模块（read-only 参考） | PC 端处置 |
|---|---|
| `app` | 重做（Vue Router 主 Layout） |
| `core/common` | 重写（Java 工具类） |
| `core/database` (Room) | 重做（MySQL + MyBatis-Plus） |
| `core/network` (AiService) | 逻辑参考、Spring AI 重写 |
| `core/security` (KMS) | **废弃**（PC 用 Jasypt + Sa-Token + BCrypt） |
| `core/ui-common` | 设计变量数值参考（**复制到 PC 项目**，不引用） |
| `feature/chat` | Phase 3 重做 |
| `feature/companion` | Phase 3（创建流程合并到角色 CRUD） |
| `feature/groupchat` | Phase 4 重做 |
| `feature/localmodel` | Phase 7 替换为 Ollama |
| `feature/memory` | Phase 4 重做 + 增强（向量检索） |
| `feature/notification` | Phase 6 替换为 Web Notification API |
| `feature/profile` | Phase 6 重做 |
| `feature/settings` | Phase 5 重做 |
| `feature/wechat` | **废弃**（PC 端不实现，PLAN-001 §11 已确认） |

## 下一步行动

当前处于 **Phase 5 → Phase 6 交界**。Phase 1-5 已完成。

已完成（2026-05-24）：
- Phase 1-4 后端：Sa-Token 认证、AI 流式对话、角色 CRUD、消息 Redis INCR seq、Resilience4j 熔断、SSE 消息落库、MinIO 头像上传、群聊 Redis 分布式锁、记忆/Embedding 用户 vault 支持
- Phase 5 前端：Vite + Vue 3 + Element Plus 工程、登录/注册、设置中心（Provider 管理）、首页、路由守卫、暗色主题

下一步：**Phase 7 — 本地模型集成**：
- 后端：Ollama provider 配置 + `/api/ollama/models` (list/pull/delete)
- 前端：本地模型管理页（拉取进度条 + 已安装列表 + 一键切换默认 provider）
- 离线模式：设置里勾选"仅本地模型"后隐藏云端 provider

---

## 智能体调度速查

| 场景 | 智能体 |
|---|---|
| 阶段开始前模板/实现细节调研 | researcher（read-only 读安卓项目） |
| 复杂阶段拆分子计划 | planner（递归调用） |
| 后端编码 | backend |
| 前端编码 | frontend |
| 通用编码/工具脚本 | coder |
| 阶段验收/测试/UI 校验 | checker |
| 计划完整性审查 | codex-review |
| 技术选型/架构合理性审查 | claude-review |
| 依赖安装前 | OSV-Scanner（强制） |
