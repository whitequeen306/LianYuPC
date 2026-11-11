# 语音通话「陪伴化」改造方案

## Context

当前语音通话是「端点 → 请求 → 回复」的回合制：`VoiceCallDuplexSession` 用 `turnBusy/bargeInAllowed/pendingUserText` 三个散落标志控制麦克风，朗读期直接丢帧，打断前的约 1 秒语音从未进过 ASR；接起来没有开场，冷场没有存在感；情绪只进 prompt 不进声音；通话中不写记忆，上一通摘要对下一次通话不可见；下行音频是 base64 JSON。

目标：把通话从「语音助手」改成「持续在线的陪伴对话」。四条线全做，主动程度取「大胆」，通话中记忆取「轻量写入」。原则是**不抢话、不冷场、不重复**，且所有新能力都能单独关掉。

---

## 1. 目标架构

### 1.1 服务端权威通话状态机

新增 `CallStateMachine`（纯逻辑，无 IO），状态：

```
LISTENING / USER_SPEAKING / THINKING / SPEAKING / BACKCHANNEL / INTERRUPTED / CLOSED
```

| From | 事件 | To |
|---|---|---|
| start | session.start(call) | LISTENING（开场开着则 THINKING） |
| LISTENING | vad.start / asr.partial 非空 | USER_SPEAKING |
| USER_SPEAKING | endpoint / vad.end | THINKING |
| THINKING | 首帧 tts.audio / tts.clip | SPEAKING |
| THINKING | barge_in / turn.cancelled | LISTENING |
| SPEAKING | barge_in | INTERRUPTED → LISTENING |
| SPEAKING | play.drained 或发送字节推算排空 | LISTENING |
| LISTENING | 静音超时（可选） | THINKING（idle nudge） |
| LISTENING/USER_SPEAKING | backchannel 触发（默认关） | BACKCHANNEL → 原状态 |
| any | session.end / close | CLOSED |

关键改动：`onPcm` 的门控从 `turnBusy` 改为**状态**——
- `SPEAKING/BACKCHANNEL`：只写入环形缓冲，不下发 ASR（防回声）
- `LISTENING/USER_SPEAKING/THINKING`：直接下发 ASR（思考期可插话）

下行新增 `call.state {seq, state, reason}`；客户端 `phase` 只做展示，打断门控只认状态机。旧客户端 800ms 内收不到 `call.state` 时回退到现有事件推导。

### 1.2 事件契约（全部附加，不删旧字段）

上行新增：`vad {phase:start|end}`、`play.drained {seq}`、`call.metrics {...}`、`barge_in {preRollMs}`、`session.start {caps:[...], clientVersion}`。

下行新增：`call.state {seq,state,reason}`、`tts.clip {petId,kind,fallbackMs,seq}`；`session.started` 增 `petId/protocol/caps`；`turn.start/turn.done` 增 `kind: reply|opening|idle`。

兼容性：`useVoiceDuplex.handleServerMessage` 的 default 分支无副作用，旧客户端会静默忽略未知事件；不新增必填字段。

### 1.3 下行二进制音频（默认关，能力协商）

仅在 `session.start.caps` 含 `binary.audio` 且开关开启时使用；否则继续 base64 JSON。

```
0-1  'L','Y'   2  version=1   3  type(0x01=PCM16)   4  flags
5    headerLen=16   6-7 reserved   8-11 seq   12-15 sampleRate
16.. payload（s16le / 24kHz / mono）
```

客户端 `binaryType` 已是 `arraybuffer`，只需在 `onmessage` 增加 `ArrayBuffer` 分支。**不做 Opus**（本轮）。

### 1.4 打断 pre-roll（P0 核心）

- 客户端环形缓冲最近 ~1.5s PCM + 语音起点（从 `vad.start` 起只收 `peak>=0.12` 的帧）。
- `barge_in {preRollMs}` 先发，紧接回灌缓冲帧，再发实时帧；同一 WS 保序。
- 服务端 `bargeIn()`：取消 LLM/TTS → 状态置 LISTENING → **清 `lastTurnText/lastTurnAtMs`**（否则回灌文本会被 `isDuplicateOfLastTurn` 当重复丢掉）→ 发 `turn.cancelled`。
- 缓冲帧到达时状态已是 LISTENING，`onPcm` 自然放行。

### 1.5 语气词 / 呼吸 / 笑声（clip）

- 扩固定语音库 kind：`pickup/ack/hmm/laugh/sigh`（`breath` 需先探针，生成不好就删）。
- 生成：`scripts/gen_pet_fixed_voices.py` 的 `LINES` 表 + 现有 CosyVoice 合成；产物落 `frontend/public/pet/voice/<pet>/<kind>.wav`。
- 协议：`tts.clip {petId, kind, fallbackMs, seq}`，只对官方音色发；自定义/本地音色退化为 `fallbackMs` 静音。
- 播放：`pcmPlayback` 新增 `enqueueUrl(url)`，与 PCM **走同一条队列**（当前 PCM 绕过 `chain`，clip 的 `decodeAudioData` 会乱序）；404 时插等长静音，绝不阻断。
- 句子标记：`SentenceSplitter` 扩 `<|sigh|>/<|laugh|>/<|breath|>`，产出 `Segment(kind=CLIP)`；TTS 线程遇到 CLIP 直接发 `tts.clip`，不走 TTS 往返。字幕与落库统一剥离。

### 1.6 开场 / 冷场 / 挂断

**开场（opening）**
- `session.start` 后立即发 `tts.clip {kind:"pickup"}`（0 延迟），状态 THINKING。
- 并行调 Python `POST /agent/voice/cue {kind:"opening"}` 生成一句 contextual 开场（时间、距上次通话、上次摘要、关系阶段、情绪）。
- 生成后走现有 TTS 管线，`turn.start/turn.done {kind:"opening"}`；文案落一条 ASSISTANT 消息（`system/voice-call-turn`），保证下一次回合的历史连贯。
- 用户先开口 → 取消 cue，直接进入用户回合；pickup 已播完无妨。

**冷场（idle nudge）**
- 客户端 `vad start/end` 边沿事件 → 服务端记 `lastSpeechEndAt`。
- LISTENING 超过 `idleMs`（默认 12s）触发 `POST /agent/voice/cue {kind:"idle"}`，同样走 TTS 管线并落库。
- 限制：每通最多 2 次、最小间隔 45s、生成期间用户开口立即取消；关系阶段 TESTING 可跳过，INJURED 用更轻的措辞。

**挂断语音留言**：推迟（需要 TTS→MinIO→`message.audioUrl` 的新落盘入口），先做文本摘要。

### 1.7 情绪 → 韵律 / 语速

- 新增 `VoiceProsody(rate, pitch, volume, pauseScale)`。
- `VoiceCallService.resolveProsody(conversationId)` 读 `CharacterStateService`（10 种情绪 × 强度），用静态映射表得到数值；**Java 只做数值映射，不写文案**。
- 映射示例：开心/兴奋 rate +5~8%、pauseScale 0.8；难过/疲惫 rate -8%、pauseScale 1.3、volume -2dB；撒娇/想念 rate -3%、pauseScale 1.15。
- `VoiceSynthesisService` 在音色基础 `rate` 上乘 prosody；`pitch/volume` 先做 DashScope 探针，确认支持后再开（默认关）。
- `SentenceSplitter` 增加 `pauseScale` 实例参数，`jitteredPause` 同步缩放。
- 可选：语速镜像——用用户本轮语音时长/字数算 cps，映射到 ±5% rate（默认关）。

### 1.8 记忆轻量写入

- 新增 Python `POST /agent/memory/moment`：只吃本轮 `userText/replyText/characterId/conversationId/sourceMsgIds`，由 Python 判定是否值得记；只写 `memory_episode`（`summary + quote + valence/arousal/open_loop + scene={"scene":"VOICE_CALL"}`），不写 `memory_meta`。
- Java 在 `turn.done` 后 fire-and-forget，限频：每 3 轮最多 1 次、用户文本 ≥8 字；失败不影响回合。
- 仍由 `app/write.py` 白名单落库；Java 只传事实。

### 1.9 关系 / 上次通话

- `ChatTurnScene.includeRelationship()` 加 `VOICE_CALL`；Python `_SCENES_RELATIONSHIP` 同步。
- `recentVoiceCallTurnsInCurrentCall` 额外带入最近一条 `system/voice-call-summary` 的 `context_content`（作为 ASSISTANT 历史，最多 1 条、7 天内），让下一通电话能接上上次的话题。
- `endCall` 仍然只写摘要、不写关系/情绪；关系写入继续留在文字聊。

### 1.10 传输与鲁棒性

- 修 `AsrStreamClient` 的 `@Value` 早于构造器问题（连接超时实际只有 1s）：改 `@PostConstruct` 初始化 `HttpClient`。
- `sendPcm` 改为单线程有界发送队列，避免 JDK WebSocket 单飞发送约束抛 `IllegalStateException`。
- 客户端断线重连：通话中 WS 断开 → 1s 后重连一次并重发 `session.start`；历史在 DB，ASR/TTS 重建；失败再提示挂断。
- `call.metrics` 上报 `firstAudioMs / bargeToPartialMs / underruns / droppedMs`，服务端 INFO 日志。
- 客户端 `pcmPlayback` 加 GainNode：响度归一 + 用户说话时 ducking（默认关，先测）。

---

## 2. 分阶段实施

### P0（可独立上线：接得住打断）

**改动**
- 新增 `backend/lianyu-service/src/main/java/com/lianyu/service/voice/CallStateMachine.java`（纯逻辑）。
- 改 `VoiceCallDuplexSession.java`：状态机接入、`onPcm` 按状态门控、环形缓冲 + barge pre-roll、清 dedupe、`play.drained` 排空、估算排空兜底。
- 改 `frontend/src/composables/useVoiceDuplex.js` + `usePcmCapture.js`：`vad` 边沿、pre-roll 缓冲、`call.state` 镜像、`play.drained`、`barge_in {preRollMs}`。
- 改 `VoiceDuplexHandler.java`：`session.start` caps、`vad/play.drained/barge_in` 分发。
- 修 `AsrStreamClient.java` 超时与发送队列；抽 `AsrSession` 接口便于测试。
- prompt 迁移：`VoiceCallService.java` L555-567 的文案搬到 `backend-agent/app/prompts/voice_call_instruction.md`，`prompt_pipeline.py` 在 `scene==VOICE_CALL` 时追加；Java 只传 `maxReplyChars` 事实。

**复用**：`SentenceSplitter`、`PcmFrameAssembler`、`VoiceEventSink`、`voiceTurnTail`。

**测试（先红后绿）**
- 新增 `CallStateMachineTest`（迁移表全覆盖、非法迁移）。
- 新增 `VoiceCallDuplexSessionTest`（Mockito：SPEAKING 丢帧、barge 后 N 帧进 ASR、dedupe 被清、play.drained 回 LISTENING）。
- 新增 `frontend/src/composables/__tests__/useVoiceDuplex.protocol.test.js`（假 WS/AudioContext：call.state、pre-roll、play.drained）。
- prompt golden：`end_to_end_golden.json`、`assembler_golden.json`、`system_prompt_golden.json` 补 VOICE_CALL；`pytest` 锁死。

**验收**：打断后首条 `asr.partial` P50 ≤700ms；朗读期重复转写下降；`check-java-ai-free.ps1 -Mode strict` 仍 0/0/0。

### P1（开口与语气词）

**改动**
- `VoiceEventSink` 加 `sendBinary`；`VoiceCallDuplexSession.sendAudio` 支持二进制帧；`session.started` 下发 caps。
- `frontend/src/composables/pcmPlayback.js`：统一队列 + `enqueueUrl` + clip 顺序保证。
- `SentenceSplitter.java`：新增三种非语音 marker 与 `Segment.Kind`；`stripPauseMarkers` 同步。
- `scripts/gen_pet_fixed_voices.py` 的 `LINES` 扩 kind；`frontend/public/pet/voice` 重新生成；`petFixedVoice.js` 新增 clip 播放函数。
- Python `POST /agent/voice/cue` + `app/prompts/voice_cue_*.md`；Java 新增 `VoiceCuePort` + `AgentVoiceCueAdapter`（走 `AgentRuntimeClient.postJson`）。
- `VoiceCallDuplexSession` 接开场：pickup clip + 异步 cue + 落库 + 用户先说话则取消。
- 开关：`lianyu.voice-call.opening.enabled`、`clip.enabled`、`binary-audio.enabled`。

**测试**：`SentenceSplitterTest` 扩 marker/clip 用例；`pcmPlayback.test.js` 扩 clip 与 PCM 交错顺序；`test_voice_cue.py`；`AgentVoiceAdaptersTest` 加 cue 降级。

**验收**：clip 首声 ≤300ms；缺文件插静音；无 caps 客户端全 base64；开场文案落库且下一轮历史可见。

### P2（主动、关系、韵律）

**改动**
- `VoiceCallDuplexSession` 静音计时 + idle nudge（`vad` 边沿驱动）；限次/间隔/取消。
- `VoiceCallService` 接 `CharacterStateService`，新增 `resolveProsody`；`VoiceSynthesisService` 透传 `rate/pauseScale`（pitch/volume 先探针）。
- `ChatTurnScene.includeRelationship()` + `prompt_pipeline._SCENES_RELATIONSHIP` 加 VOICE_CALL；上次通话摘要进历史。
- `VoiceCallDuplexSession` 传 prosody 到每句 TTS；`SentenceSplitter(pauseScale)`。
- 客户端断线重连；`pcmPlayback` GainNode 响度归一/ducking（默认关）。
- 开关：`idle-nudge.enabled`、`relationship-context.enabled`、`emotion-prosody.enabled`、`reconnect.enabled`。

**测试**：`VoiceProsodyTest` 映射表；`VoiceCallDuplexSessionTest` 静音触发/取消；`ChatTurnSceneTest`/`test_prompt_pipeline.py` 关系块；`pcmPlayback.test.js` GainNode。

**验收**：静音 12s 触发、每通≤2；关系块出现在 VOICE_CALL prompt；TTS 参数进日志且无 4xx；断线 1s 内重连成功。

### P3（记忆与打磨）

**改动**
- Python `POST /agent/memory/moment` + `app/memory/moment.py`（只走 `episode_store` → `app.write` 白名单）。
- `AgentMemoryPort` 加 `default writeVoiceMomentIfActive(...)`（默认 false），`AgentMemoryAdapter` 实现；`VoiceCallDuplexSession` 在 `turn.done` 后限频调用。
- 可选：主动来电邀请（不发起来电 UI，只发 `VOICE_CALL_INVITE` 通知/卡片，点击进通话；DND + 关系阶段 + 每周上限）。
- 可选：语速镜像、挂断语音留言、多语言 TTS 路由（先探针）。
- 开关：`memory-moment.enabled`、`call-invite.enabled`、`rate-mirror.enabled`。

**测试**：`test_memory_moment.py`（必带 quote、重复不写、失败不影响）；`AgentMemoryAdapterTest`；前端通知卡片测试。

**验收**：通话中有信号的一轮写出 `memory_episode` 且能在下次文本聊召回；写入失败不影响通话；主动邀请受 DND 与频率限制。

---

## 3. 边界与红线

| 层 | 本轮只做 | 绝不做 |
|---|---|---|
| Java | 状态机、取事实（时间/摘要/关系/情绪）、事务落库、TTS 参数映射、WS 转发 | 拼 prompt、调模型、定义 clip 文案 |
| Python | 开场/冷场文案、情绪 prompt、记忆判定、TTS 请求、记忆写库 | 直接操作桌面、绕过 `write.py` 白名单 |
| Electron/前端 | 采集、VAD、播放队列、clip 播放、动画联动、指标上报 | 调模型、决定记忆 |

prompt 迁移后 `scripts/java_bare_prompt_allowlist.txt` 保持为空；`check-java-ai-free.ps1 -Mode strict` 作为 P0 门禁。

---

## 4. 开关与灰度

| 开关 | 代码默认 | 说明 |
|---|---|---|
| `lianyu.voice-call.state-machine.enabled` | true | 内部重构，行为保持 |
| `lianyu.voice-call.barge-preroll.enabled` | true | 只在打断时改变 ASR 输入 |
| `opening.enabled` / `clip.enabled` | false | 验收后在部署 `.env` 打开 |
| `idle-nudge.enabled` | false | 每通≤2、间隔≥45s |
| `emotion-prosody.enabled` | false | 先 rate+pauseScale，pitch/volume 探针后 |
| `memory-moment.enabled` | false | 只写 episode、每 3 轮≤1 |
| `binary-audio.enabled` | false | 需客户端 caps |
| `backchannel.enabled` | false | 回声风险，最后再评估 |
| `reconnect.enabled` / `call-invite.enabled` | false | P2/P3 |

按 AGENTS §3.4「新增自动能力默认取最保守一侧」：代码默认关；部署 `.env` 按验收结果逐项打开。新增键同步 `.env.example`（顺带补上缺失的 `LIANYU_VOICE_DUPLEX_ENABLED`）。

---

## 5. 验证

```powershell
cd backend-agent; pytest
cd backend; mvn -o test
cd frontend; npx vitest run --no-file-parallelism
cd frontend; npx vite build
powershell -File scripts/check-java-ai-free.ps1 -Mode strict
python scripts/check_release_asset_contract.py
```

手工场景：
1. 接起 → 300ms 内听到 pickup，1.5s 内听到 contextual 开场；
2. 角色朗读中打断 → 无「前半句丢失」，首条 partial ≤700ms；
3. 思考期说话 → 能取消并进入新回合；
4. 静音 12s → 只触发一次 idle nudge，用户开口立即停；
5. 挂断 → 摘要落库；下一通开场能引用上次话题；
6. 通话中说出可记事实 → `memory_episode` 落一行带 quote；
7. 断网 1s → 重连后继续；
8. 老客户端（无 caps）→ 全程 base64，功能不退化。

日志口径沿用现有 `stage=asr_endpoint/first_audio/turn_done`，新增 `call.state`、`barge preRollMs`、`idle nudge`、`memory moment`。

---

## 6. 风险与回滚

| 风险 | 缓解 / kill switch |
|---|---|
| 回声自激 | pre-roll 只回灌语音起点后高能量帧；backchannel 默认关；保留 `onNoise`；`barge-preroll.enabled` |
| 自定义音色无 clip | 服务端不发 clip，退化为静音；客户端 404 也静音 |
| 老客户端 | 事件全附加；二进制走 caps；`call.state` 超时回退 |
| 延迟回退 | pickup clip 兜底；cue 失败只保留 clip；TTS 预热不变 |
| 主动打扰 | 每通限次、间隔、关系门槛、DND、`idle-nudge.enabled` |
| 记忆污染 | 只写 episode、必带 quote、7 天同句去重、每 3 轮≤1、kill switch |
| prompt 迁移破坏 golden | 同步三份 golden；`pytest` 锁死；先红后绿 |
| 多语言 | 本轮不动 ASR 模型；TTS `language-type` 仍 Chinese；日语/英语通话质量列为已知缺口，P3 探针后再改 |

---

## 7. 明确不做 / 推迟

- **不做 Opus**：PCM 二进制已省 base64，Opus 需要客户端解码器，收益/成本不划算。
- **不做服务端 AEC**：依赖浏览器 `echoCancellation` + 高阈值 + 默认关的 backchannel。
- **不新增桌宠 talking/listening 动画**：atlas 是 8×9 全端契约，先复用 `wave/waiting/review/jump`。
- **不实时写关系/情绪**：只读关系块 + 写 episode；关系/情绪仍在挂断后结算。
- **不做真·全双工重叠说话**：只做受控 backchannel（默认关）。
- **不做自动接入的来电 UI**：主动来电用「邀请卡片/通知」，不自动开麦。
- **不换 TTS 模型**：先用垫场音与开场解决体感。

---

## 8. 第一批要动的 8 个文件

1. `backend/lianyu-service/src/main/java/com/lianyu/service/voice/CallStateMachine.java`（新）
2. `backend/lianyu-service/src/main/java/com/lianyu/service/voice/VoiceCallDuplexSession.java`
3. `backend/lianyu-web/src/main/java/com/lianyu/web/voice/VoiceDuplexHandler.java`
4. `backend/lianyu-service/src/main/java/com/lianyu/service/ai/AsrStreamClient.java`
5. `backend/lianyu-service/src/main/java/com/lianyu/service/conversation/VoiceCallService.java`
6. `backend-agent/app/prompt_pipeline.py` + `backend-agent/app/prompts/voice_call_instruction.md`（新）
7. `frontend/src/composables/useVoiceDuplex.js`
8. `frontend/src/composables/usePcmCapture.js`

配套新测试：`CallStateMachineTest`、`VoiceCallDuplexSessionTest`、`useVoiceDuplex.protocol.test.js`。
