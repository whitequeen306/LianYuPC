# TTS / 音色克隆迁移计划（下一步执行清单）

> 2026-10-20。本文件是「把语音相关能力从 Java 搬到 Python」的执行清单。
> 之所以先写计划再动手：这块是**实时双向音频**，比聊天链路更容易出静默故障，
> 必须在有完整上下文时一次做完，不能拆成半截。

## 一、现状清点

Python 侧目前**完全没有 TTS 代码**（`backend-agent/app` 下无任何 tts 模块）。

Java 侧（含 tts / voice 字样的非测试文件，行数为整文件）：

| 文件 | 行数 | 职责（推断，执行前需确认） |
|---|---|---|
| `VoiceCallDuplexSession.java` | 590 | 语音通话双工会话（客户端 ↔ 模型） |
| `DashScopeTtsRealtimeService.java` | 401 | 实时 TTS（WebSocket 双向） |
| `DashScopeTtsService.java` | 275 | 非实时 TTS（HTTP） |
| `CustomVoiceService.java` | 275 | 音色克隆编排（录制→校验→注册→使用） |
| `VoiceDuplexHandler.java` | 241 | 双工 handler（连接生命周期、鉴权） |
| `DashScopeVoiceEnrollmentClient.java` | 135 | 音色注册 API 客户端 |
| `LocalTtsEndpointValidator.java` | 73 | 本地 TTS 端点校验（SSRF 面） |
| `DashScopeCloudEndpointValidator.java` | 63 | 云端端点校验 |
| `CustomVoiceAudioTrimmer.java` / `CustomVoiceAudioValidator.java` | — | 音频裁剪/校验 |

相关但不必迁的：`FileStorageService`（对象存储）、`PetVoiceRegistry`（产品配置）、
`ConversationService` / `VoiceCallService` / `ConversationController`（业务编排）。

## 二、迁移顺序（按风险从低到高）

### 第 1 步：音色注册（纯 HTTP，最低风险）
- Python 新增 `app/voice_enrollment.py` + `/agent/voice/enroll`、`/agent/voice/delete`
- Java `DashScopeVoiceEnrollmentClient` → 换成端口调用，然后删除客户端
- 校验点：注册成功返回的音色 ID 与原实现一致；删除幂等

### 第 2 步：非实时 TTS（HTTP 单次合成）
- Python `app/tts.py`：`POST /agent/tts/synthesize`，返回音频字节（base64 或直接 bytes）
- Java `DashScopeTtsService` → 端口转发 → 删除
- 校验点：同一文本同一音色的输出可播放、时长接近、失败码映射一致

### 第 3 步：实时 TTS（WebSocket，最高风险）
- Python 建 `/agent/tts/realtime` 的 WS 端点，转发 DashScope realtime 协议
- Java `DashScopeTtsRealtimeService` → 保留连接管理，删掉协议实现
- 这一层建议**最后做**，并且先在本地用真实通话跑通再动线上

### 第 4 步：补检查器
- `scripts/check-java-ai-free.ps1` 增加模式：`dashscope`、`tts`、`embedding`、
  `rerank`、`DashScope*`、`realtime` 等
- 目标：让「Java 里还有 AI 调用」这件事无法再隐形（本轮已因此漏掉 embedding/rerank）

## 三、执行前必须先确认的三件事

1. ~~`CustomVoiceService` 到底调了哪几个外部接口~~ **已确认**：它只依赖
   `UserCustomVoiceMapper`（DB）、`CharacterMapper`、`FileStorageService`（MinIO）、
   `DashScopeVoiceEnrollmentClient`（**唯一 AI 调用**）、`JasyptUtil`。
   → 第 1 步的边界就是这一个客户端，`CustomVoiceService` 自身可以原地不动，
   只把 `enrollmentClient.xxx(...)` 换成端口调用。这比预想的干净得多。
2. 实时链路的终止条件与错误码（客户端断连、上游超时、额度不足），
   这些是 Java 现在用 `SseEmitter`/`WebSocketSession` 生命周期兜住的，换实现要逐条对齐。
3. 语音通话是否与文字回合共享 prompt 组装（`ChatTurnScene.VOICE_CALL` 已在 Python），
   若是，TTS 迁移后要保证 voice call 仍走同一条 LangGraph 回合。

## 四、验收标准

- `mvn test` / `pytest` 全绿
- `check-java-ai-free.ps1` 新增模式后仍 0/0/0（说明真删干净了，而不是扫不到）
- 本地容器：发一条语音消息 → 能播放；发起一次语音通话 → 双向通、能挂断
- 线上灰度：先单实例（`agent` only），观察 30 分钟错误率再放到 `agent2`

## 五、为什么不在这一轮做

本轮上下文已接近耗尽。上一轮「删 Java 代码 + 大改」在上下文不足时留下了 4 个 bug
（其中一个是所有回复变空），代价远大于等待一轮。这块涉及实时音频，
半截状态比不开始更危险。
