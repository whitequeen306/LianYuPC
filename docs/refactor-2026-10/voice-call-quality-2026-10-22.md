# 语音通话质量回归（2026-10-22）：四个现象、四个根因、一次横评

用户在一次真实通话后反馈四件事：

1. 角色回复语音有**电子杂音**
2. **该停顿的地方连读**，语气平淡
3. **语音转文字变慢**
4. 角色回复和**出声变慢**

下面每一条都有生产实测数据支撑，不是推测。取证脚本见 `scripts/_probe_tts_forensics.py`、
`scripts/_probe_v35_latency.py`（一次性诊断用，按惯例不进仓库）。

---

## 0. 先排除的怀疑：采样率没问题

第一反应是"PCM 采样率和播放端不一致 → 变调 + 杂音"。生产容器内取证否掉了：

| 项 | 结果 |
| --- | --- |
| DashScope 整句 `format=wav` 头 | `format=1 channels=1 sample_rate=24000 bits=16` |
| 同文本 `format=pcm` 流式总字节 | 192000 字节 = 96000 样本 = **4.000s** |
| 同文本 wav 数据段 | 192000 字节，**4.000s** |

两者逐字节等长 → 流出的确实是 s16le / 24k / 单声道，客户端 `createPcmPlayback({sampleRate:24000})`
也对得上。**问题不在采样率。**

---

## 1. 电子杂音 = 分帧错位（不是音质问题）

### 根因

音频通路上每一帧是这样来的：

```
Python tts.synthesize_stream  →  SSE 里每块 base64 解出的 bytes（长度任意）
Java AgentRuntimeClient       →  WebClient DataBuffer（长度/偏移由 TCP 分块决定）
VoiceCallDuplexSession        →  一块一个 tts.audio 帧
前端 pcmPlayback              →  每帧独立 new Int16Array(buffer, offset, floor(len/2))
```

最后一步是致命的：PCM16 一个样本两字节，而**帧边界从来不看样本边界**。

* 帧起始偏移是**奇数** → 整帧高低字节全部对调 → 整帧变成刺耳高频噪声
* 帧长度是**奇数** → `floor(len/2)` 丢掉尾部半个样本 → 帧尾一个台阶（"啪"）

一帧可以有 65KB（0.68 秒），所以一次错位就是小半秒的噪声。

### 修复

* **服务端**（主修复）：新增 `PcmFrameAssembler`，把任意切片拼成 **100ms / 4800 字节定长、
  样本对齐**的帧；句末 `flush()` 丢掉最后半个样本，不让半字节串到下一句。
* **客户端**（第二道防线，随下个版本发布）：`pcmPlayback` 增加 carry，奇数帧的多余字节留到
  下一帧再拼；`clear()` 清 carry。

单测：`PcmFrameAssemblerTest`（7）+ `pcmPlayback.test.js`（5）。

---

## 2. 连读 = 按句独立合成把句间停顿砍掉一半

### 根因（实测标定）

同一次合成里，模型自己在句号处的停顿：

| 方式 | 句间静音 |
| --- | --- |
| **一次合成两句** `你好，我是可莉。今天也要一起去冒险吗？` | **585ms**（内部实测） |
| **拆成两句分别合成再拼接** | **304ms** = 前句尾 175ms + 后句头 129ms |

差值 281ms 就是听感上"连读"的来源。而且独立合成时每句语调都重新起头，所以不只是短，
还平。另外首句之后的每一句都要再付一次 TTS 往返（1-1.5s）。

### 修复

* **首句之后合并短句**：`SentenceSplitter.MIN_LATER_COMMIT = 20`，攒够 20 字才在下一个句号切。
  首句仍然只要 4 字就切（**首包延迟优先，之后音质优先**）。副作用是 TTS 往返次数直接变少。
* **片段之间补静音**：`Segment` 带 `pauseAfterMs`（句号 280ms / 分号 220ms / `<|pause|>` 400ms），
  TTS 线程在两段之间插入等长静音，把边界补回模型的自然停顿。最后一段为 0，不拖慢 `turn.done`。
* **顺带修掉一个真卡顿**：切在 `<|pause|>` 处时标记会留在缓冲区头部，`indexOf == 0` 分支让该回合
  **后续再也不切**，全部堆到 flush 才合成。现在先清开头标记再找切点。

单测：`SentenceSplitterTest`（18）。

---

## 3. 出声变慢 = 每句重开一条 TLS 连接 + 首包本来就这样

### 实测

同一句 26 字文本、同一音色：

| 场景 | 首包 |
| --- | --- |
| **冷**（新连接，含 TCP+TLS 握手 + 模型冷启） | **1163ms**（另一次测得 2162ms） |
| **热**（复用 keep-alive） | **588ms** |

而原来 `tts.py` 每句都 `async with new_client()` → **每句都是冷的**。一句话三片就白送三倍握手。

另外从真实通话日志看，`tts_first_audio` 的 `afterLlmMs` 是 1150-1396ms，其中 ~0.6s 是上游首包、
~0.3s 是握手、剩下的等句子攒够一个字。

### 修复

* `http_client.shared_client(name)`：按名字复用长连接（跨事件循环自动重建），仍然走 `new_client()`
  构造，出站策略保持一份实现。
* `tts stream done` 日志补 `firstChunkMs` / `totalMs`：下次变慢能直接分辨是上游慢还是我们排队慢。

## 3.5 "识别变慢" = 回合忙时把用户这句话丢了

读代码发现比"模型慢"严重得多的问题：上一回合还在发音频时，新一句进 `startTurn` 会在
`turnBusy.compareAndSet` 失败处**直接 return**，这半句被静默丢弃。体感就是"我说了它没听见"。

修：存进 `pendingUserText`，当前回合 `finally` 里立刻接力。

同时补两行埋点，把 ASR 这一段拆开量化（原来只有 `turn_start` 之后的数据，根本分不清是 VAD 慢
还是识别慢）：

* `stage=asr_first_partial` —— 会话开始到第一条中间结果
* `stage=asr_endpoint silenceMs=` —— **用户最后一帧语音到判定说完的间隔**

---

## 4. 模型横评（2026-10-22 生产实测）

复刻音色**绑定 `target_model`，不能跨模型**（官方文档明确）。所以每个模型都用自己的 klee 音色：

| 模型 | 冷首包 | 热首包 p50 | 同句音频时长 | 价格（元/万字符） |
| --- | --- | --- | --- | --- |
| `cosyvoice-v3-flash`（现用） | 1163ms | **588ms** | 4.24s | 1.0 |
| `cosyvoice-v3.5-flash` | 842ms | 862ms | 5.12s | — |
| `cosyvoice-v3.5-plus` | 953ms | 985ms | 5.20s | 1.5 |

读法：

* **v3-flash 热态最快、语速最赶**（同句 4.24s）。v3.5 两档同句 5.1-5.2s，慢约 20% ——
  这通常意味着更从容的语速和更完整的句末收尾，也就是用户觉得"平淡/连读"的那部分。
* v3.5 的**冷启动更快**（842 / 953 vs 1163），热态慢 270-400ms。复用长连接后热的才是常态，
  所以换 v3.5 会用首包换音质。
* v3.5-plus 比 v3.5-flash 只慢 ~120ms、贵 50%？—— 官方价 1.5 元/万字符，v3-flash 1.0，
  对月量几百万字来说是几十块量级，不是决策点。

结论：**音质的判断只能靠耳朵**，已生成同文本、同音色的对比样本：

```
artifacts/voice-compare/compare-v3-flash.wav
artifacts/voice-compare/compare-v3.5-flash.wav
artifacts/voice-compare/compare-v3.5-plus.wav
```

### 换模型的完整步骤（约 10 分钟）

1. `DSK=<生产key> DS_MODEL=cosyvoice-v3.5-flash DS_OUT_NAME=v35 python scripts/re_enroll_pet_voices.py`
   重新注册 13 个音色（**必须换**：旧 voice_id 在新模型上返回 `Engine return error code: 418`）
2. `python scripts/write_pet_voices_registry.py` 写回 `pet-voices.json`（同时改 `model` 字段）
3. 按 `production-rollout.md` 的正常部署流程上后端
4. 回滚：模型名 + voice_id 一起回退（音色在两套模型下并存，互不覆盖）

---

## 5. 顺带修掉的一处诊断黑洞

DashScope 的失败有两种形态：非 200，以及 **200 + `event:error` 的 SSE 帧**（音色不匹配就是这种）。
原来的实现只数音频块数，于是线上表现是"静默没有声音、日志里什么都没有"。
现在没有音频时会打 `tts stream produced NO audio model=... voice=... upstream=...`。

---

## 6. 遗留 / 下一步

* **ASR 侧还没有为"更好"做任何改动**：先跑一次真实通话，看新埋点的
  `asr_endpoint silenceMs` 落在什么量级。若 >800ms，说明瓶颈在 VAD/端点判定而不是识别
  （现在是客户端 VAD `strict` + Zipformer partial，final 直接复用 partial，没有跑 SenseVoice 整段）。
  那一步再决定是调 VAD、还是换 SenseVoice streaming / Paraformer realtime。
* 并行/预取 TTS：现在按序串行，单片合成（0.6-1.0s 热）远小于单片播放（2-5s），暂不需要。
  若合并后单片变长导致断流，再加一路预取。
* `turn.done` 一到客户端就 `phase=idle` + `resetBargeState()`，此时尾音可能还在播 → 这段时间
  打断失效。属遗留问题，未在本次修复范围。
