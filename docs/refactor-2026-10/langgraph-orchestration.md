# 编排层迁移：Spring AI Alibaba Graph → LangGraph

> 2026-10-20。本文件记录「主聊天回合编排归 Python」这一步的形态、验证与剩余缺口。

## 一、目标形态（迁移前后）

```
迁移前
  Java  ChatTurnFacade
          └─ Spring AI Alibaba Graph（assemblePrompt → assembleMessages → invokeModel）
                └─ AgentStreamPort / AgentBlockingPort ──→ Python 只做「调模型」

迁移后
  Java  ChatTurnFacade ── 取事实（场景开关/时间/工具开关/回复条数/语言）
          └─ ChatTurnPort ──→ POST /agent/chat-turn/{inspect,stream}
                                  └─ LangGraph（assemble_prompt → assemble_messages → invoke）
                                        └─ 记忆/情绪/工具/城市全部由 Python 自读
```

关键判据：**Java 侧再也拿不到 `StateGraph`/`CompiledGraph`**。
`spring-ai-alibaba-graph-core` 已从 `lianyu-ai/pom.xml` 移除，想加一个编排步骤，
唯一的地方是 `backend-agent/app/chat_turn.py`。

## 二、删除清单

| 文件 | 行数 | 原职责 |
|---|---|---|
| `graph/ChatTurnGraphConfig.java` | 82 | 建图、注册 KeyStrategy |
| `graph/nodes/AssemblePromptNode.java` | 133 | 组装 prompt 节点 |
| `graph/nodes/AssembleMessagesNode.java` | 41 | 组装 messages 节点 |
| `graph/nodes/InvokeModelNode.java` | 127 | 调模型节点 |
| `graph/ChatTurnMessageAssembler.java` | 95 | system + 历史 → 模型输入 |
| `lianyu-ai/graph/ChatTurnKeys.java` | 34 | 图状态键 |
| `lianyu-ai/graph/ChatTurnNodes.java` | — | 节点名常量 |
| `lianyu-ai/graph/ChatTurnState.java` | 93 | `OverAllState` 包装 |

合计约 **1014 行**编排代码。`ChatTurnScene`（场景开关枚举）保留 —— 它是事实，不是编排。

## 三、新增接缝

| 类型 | 位置 | 职责 |
|---|---|---|
| `ChatTurnPort` | `lianyu-service/.../agent/` | 三个方法：`assemble` / `invoke` / `stream` |
| `ChatTurnRequest` | 同上 | **原始输入**（不含 prompt / messages） |
| `ChatTurnOutcome` | 同上 | prompt + messages + content + usage |
| `ChatTurnAdapter` | `lianyu-web/.../agent/` | 解析 vault → 转发；SSE 交给 `AgentStreamBridge` |

`ChatTurnFacade` 只做三件事：翻译场景开关、读时间/工具/语言/回复条数、抛错。
它**不接收也不传递** systemPrompt 或拼好的消息 —— 一旦能传，组装就会悄悄回到 Java。

## 四、验证记录（本机真实链路）

| 项 | 结果 |
|---|---|
| 流式单聊 | `frames: 7`，含 `{"content":…}` 与 `{"pieces":…}`，落库 7 字 `在呢，`（hex 校验 UTF-8 正确） |
| 非流式单聊 | 返回 `晚安，明天出门记得带伞。`，`tokens: 2698`（usage 来自 Python） |
| 群聊 preparedMessages 分支 | system 被替换（2857 字）、群聊规则含 `@小雨`、`extraSystemSuffix` 在末尾 |
| Java 启动自检 | `chatTurn(ChatTurnPort)=true` |
| pytest | 1131 passed |
| mvn test | 86 passed（lianyu-web） |
| `check-java-ai-free.ps1` | prompt 0 / 模型调用 0 / 已知 AI 类 0 |

踩到的坑：只重启 `agent` 没重建 `agent2`，`agent-lb` 把请求轮询到旧镜像 → **404 Not Found
from POST http://agent-lb:8001/agent/chat-turn/stream**。生产部署同理，两个实例都要更新。

## 五、剩余缺口（下一步）

Java 里仍有**群聊场景的 prompt 文本**，strict 检查器没覆盖到（它只扫已知模式）：

| 位置 | 内容 |
|---|---|
| `GroupChatService:243-266` | 群聊主动发言指令（zh/en/ja/zh-TW 四套） |
| `GroupChatService:280-287` | `extraSystemSuffix` 外壳（标题、`最近气氛参考`、`禁止假装…`） |
| `GroupChatService:268` | `（系统触发主动发言，用户未发送新消息。）` |
| `GroupChatService:237` | `formatOtherCharacterHistoryLine`（其他角色历史行的中文格式） |

要彻底清零，需要把 `preparedMessages` 的构造也搬进 Python（Python 自己查群成员与历史）。
在此之前，群聊链路的「事实」在 Java、「指令文本」也在 Java，属于**半迁移**状态。

检查器也要补：把 `群聊主动发言`、`最近气氛参考`、`系统触发主动发言` 这类中文指令片段
加进 `scripts/check-java-ai-free.ps1` 的模式表，否则这类残留会一直隐形。

## 五之二、验证时暴露的既有缺陷：单聊工具没有执行器（已修）

切换后按「真实用户会问什么」验证时发现：**问「现在几点了」这类会触发工具的问题，
流式回复为空**，前端显示「对方没有回复内容，请重试」。这不是本次迁移引入的，
是同一条调用链原本就有的：

| 环节 | 事实 |
|---|---|
| `gateway._stream_once` | 只 `yield delta.content`，**丢弃 `tool_calls`** |
| `stream_chat_events` | 无工具执行；收不到文本就 `sse_error("对方没有回复内容，请重试")` |
| `run_tool_loop` | 存在且有测试，但**只有 `graphs/proactive_turn.py` 在用** |
| 阻塞侧 | 不传工具 schema，模型把 `<｜｜DSML｜｜ calls>` 当正文吐出来（实测落库成脏文本） |

即「工具」在单聊里只有提示词、没有执行器。修法（commit `fd50a05` / `4ea7176`）：

| 层 | 改法 |
|---|---|
| 流式 | 流完仍无文本且本轮挂了工具 → 退化成 `run_tool_loop`，把最终文本作为单帧下发 |
| 阻塞 | 场景挂工具时直接走 `run_tool_loop`（与主动发言同一条有界循环） |
| usage | `ToolLoopResult` 累加各轮 token，避免非流式回合丢统计 |
| 上限 | 仍由 `settings.max_tool_rounds` 控制，审计 A4 的「有界」要求不变 |

实测（本机）：`现在几点了？` → `八点三十五，星期二晚上。`
`郑州今天天气怎么样，要带伞吗` → `郑州现在21度，体感才16度…不用带伞`，
日志 `stream tool fallback: rounds=1 tools=['get_current_time']`。

## 六、回滚

`ChatTurnFacade` 的公开签名未变，回滚只需把 `ChatTurnAdapter` 换回旧实现；
但旧图已删除，真正的回滚点是 git（`b46b637` 之前）。
线上回滚应走镜像回退 + `agent`/`agent2` 一起回。
