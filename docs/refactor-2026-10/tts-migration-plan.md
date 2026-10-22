# TTS / 音色克隆迁移（第 1、2 步已完成）

> 2026-10-20 第二轮。第一轮只出了清点与顺序（见本文件末「原始计划」），
> 第二轮把**第 1 步（音色注册）与第 2 步（非实时 TTS）真正做完并验证**。
> 第 3 步（实时 TTS，WebSocket 双向）仍未开始，原因在末尾。

## 一、最终形态

```
Java（产品规则）                          Python（AI 能力）
─────────────────────────────            ──────────────────────────────
CustomVoiceService                        /agent/voice/enroll
  ├ 音色给哪个用户/角色                     ├ 音色注册请求体拼装
  ├ Key 加密存库（JasyptUtil）              ├ 出站 URL（enroll_url）
  ├ 音频裁剪 / MinIO 备份                   ├ 错误脱敏（sk-* → [redacted]）
  └ VoiceEnrollmentPort ──────────────────▶ └ 返回 voice_id

VoiceSynthesisService                      /agent/tts/synthesize
  ├ 用哪个音色（Pet 映射 / 用户自定义）        ├ 合成请求体（text/voice/language_type）
  ├ 用哪个 Key（平台 / 用户）                 ├ 取 audio URL → 下载音频
  ├ enabled / language_type 配置             ├ 域名白名单 + scheme 升级
  └ TtsPort ───────────────────────────────▶ └ 返回原始音频字节（204 = 没有）
```

关键取舍：**出站 URL 的拼装只在 Python**。`DashScopeCloudEndpointValidator`
留在 Java，但只剩「用户填的地址能不能存」这层输入校验，以及仍在 Java 的
Realtime WS 端点；`enrollUrl` / `synthUrl` 随客户端一起删掉 —— 两边各拼一套必然分叉。

## 二、实际删掉的东西

| 文件 | 行数 | 去处 |
|---|---|---|
| `DashScopeTtsService.java` | 302 | `app/tts.py`（147）+ `VoiceSynthesisService`（配置解析） |
| `DashScopeVoiceEnrollmentClient.java` | 147 | `app/voice_enrollment.py`（172） |
| `DashScopeTtsServiceNetworkTest.java` | 33 | 行为测试搬到 `tests/test_voice_tts.py` |

合计 **-482 行 Java**（其中 449 行是真实的 HTTP/JSON/白名单实现）。
`DashScopeCloudEndpointValidator` 同时删掉 `enrollUrl` / `synthUrl` 两个方法（-9 行），
`synthUrl` 的唯一调用方就是被删的服务。

新增 Java 只有「端口 + 适配 + 门面」：`TtsPort`(53)、`VoiceEnrollmentPort`(47)、
`AgentTtsAdapter`(58)、`AgentVoiceEnrollmentAdapter`(84)、`VoiceSynthesisService`(117)。
它们是**接线**，不是 AI 逻辑 —— 和 `ChatTurnPort` 一起由 `AgentWiringHealthCheck` 在启动时自检。

## 三、一个真 bug：DashScope 的音频地址是 http

Java 原实现要求音频 URL 必须是 `https`：

```java
if (!"https".equalsIgnoreCase(uri.getScheme())) return false;   // 原 DashScopeTtsService:182
```

实测（2026-10-20，`qwen3-tts-flash`）DashScope 返回的是：

```
http://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/prod/qwen3-tts/.../x.wav?OSSAccessKeyId=...&Signature=...
```

也就是说**凡是用 http 下发的音频 URL，原实现一律判为非法 → 静默返回 null**。
桌面问候只是不带音频、语音通话则报「语音合成失败」，两条链路都不会报错，
所以这个缺陷一直没人发现。

Python 侧的处理：**域名白名单不放宽，只把 scheme 升级成 https 再下载**
（同一签名 https 同样 200，实测 119084 字节 RIFF）。见 `app/dashscope.py::normalize_audio_url`。

顺带补上国际站：原白名单只放行 `dashscope.aliyuncs.com`，但
`normalizeBaseUrl` 明确支持 `dashscope-intl.aliyuncs.com` —— 国际站用户的音频
必然被判非法。既然接受这个 base，白名单必须同步，否则「支持」是假的。

## 四、契约细节（改动时最容易踩的）

| 点 | 现状 | 为什么 |
|---|---|---|
| TTS 失败返回 | `null` / HTTP 204 | 原实现就返回 null，调用方已按「没音频」降级。改成异常会把可选增强变成主链路故障 |
| 音色注册失败 | 抛 `AI_PROVIDER_ERROR(message)` | 用户要看到原因；message 由 Python 脱敏（`sk-*`→`[redacted]`，截断 160） |
| 删除音色 | 永不抛，永不失败 | 删远端失败不该阻塞本地删记录（原 `deleteVoiceQuietly`） |
| 传输格式 | **原始字节** + `Content-Type` | JSON+base64 会膨胀 33%，而 Spring 的 `StringDecoder`/`ByteArrayDecoder` 默认 256KB 上限会抛 `DataBufferLimitException`（十秒语音就 300KB+） |
| 重试 | 最多 2 次，仅「连不上」/429/5xx | 对齐原 `isTransientNetworkFailure`：**读超时不重试**，请求已发出，重试重复扣费 |
| 一次注册一个模型 | HTTP / Realtime 各注册一次 | 一个音色只属于一个 `target_model`，合并会得到 `CLIENT_ERROR` |

## 五、验证

| 项 | 结果 |
|---|---|
| `pytest` | **1197 passed**（新增 `tests/test_voice_tts.py` 58 个） |
| `mvn test` | **BUILD SUCCESS**（lianyu-service 229 / lianyu-web 104；新增 `AgentVoiceAdaptersTest` 13 个、`AgentRuntimeClientBinaryTest` 4 个） |
| `check-java-ai-free.ps1` | prompt 0 / 模型调用 0 / 已知 AI 类 0 / 复活客户端 0 |
| 检查器非空转 | 造一个含 `/api/v1/services/audio/tts/customization` 的探针文件 → gate 立刻失败；删掉 → 通过 |
| 编解码上限非空转 | 摘掉 `.codecs(maxInMemorySize(16MB))` → `DataBufferLimitException: Exceeded limit on max bytes to buffer : 262144`，400KB 音频用例失败；放回 → 通过 |
| 本地容器 | `/agent/tts/synthesize` 实测 `200 audio/wav`、88364 字节、`RIFF` 头（`qwen3-tts-flash` + `Cherry`） |
| 反面用例 | 不存在的模型 → DashScope 404 `Model not exist` → 端点 204（不是 500） |
| 启动自检 | 8 个端口全部 wired：`tts(TtsPort)=true, voiceEnrollment(VoiceEnrollmentPort)=true`，启动 12.257s |
| **音色注册全链路** | `POST /api/character/52/custom-voice`（multipart，真实 wav 307KB）→ `status=READY`、`voiceCallReady=true`；DB 落两条不同 voiceId：`...-u23_c52-voice-...` / `...-u23_c52_rt-voice-...`，说明 Python 的 `sanitize_preferred_name` 与原 Java 逐字一致 |
| **音色删除全链路** | `DELETE /api/character/{id}/custom-voice` → 远端两条音色 DashScope 均返回 200（日志 `voice delete ... status=200`），本地行 count=0 |

**没能验证的**：用自定义音色（`qwen3-tts-vc-*`）真正合成出声音。

- 音色**注册**没问题：`qwen-voice-enrollment` 对这个 key 完全可用，两个模型都返回了 voice_id。
- 但 `qwen3-tts-vc-2026-01-22` 走**合成**接口返回 404（body 为空），
  换刚注册的 voiceId 也一样 —— 是这个 key 的合成权限，不是代码问题。
  `qwen3-tts-flash` 走同一条路径 200，说明协议与实现都对。
- `pet-voices.json` 里的 voiceId 属于另一个账号，本机无法直接复用。

所以本轮的「TTS 能出声」是在 `qwen3-tts-flash` + 预置音色上证明的；
「自定义音色能出声」要等一个有 VC 合成权限的 key 才能验证 —— 这是**环境缺口，不是代码缺口**。

## 六、第 3 步（实时 TTS）：**不是迁移，而是退役**（2026-10-22 结论）

原计划是把这条 WS 链路迁到 Python。查证后的结论是**这条路本身不存在了**：

- `qwen3-tts-vc-realtime-2026-01-15`（代码里硬编码的那个）已下架；
- 现役实时 TTS 模型 `qwen3-tts-flash-realtime` 能流式出音频，但**不支持声音复刻**，
  只能系统音色 —— 做不了角色音色；
- 支持复刻的 `qwen-audio-*-realtime-*` 是**语音对话模型**（输入音频、另一套事件），
  不是 TTS：喂 `input_text_buffer` 只会停在 `session.updated`，不出音频。

所以「实时 WS + 复刻音色」这个组合在平台上已经没有实现方式。替代方案是
**按块 SSE 流式合成**：同一句文本走 HTTP，边合成边下发 PCM，客户端本来就有
流式播放队列（`createPcmPlayback().enqueueBase64Audio`），前端一行都不用改。

实测对比（生产 key，CosyVoice 复刻音色）：

| 方案 | 首帧 | 备注 |
|---|---|---|
| 整句合成（原 HTTP 回退） | 1440ms | 等整段音频落地才发第一帧 |
| **按块 SSE 流式** | **895ms**（本地 841ms，生产容器 895ms） | 首块即可播放 |
| 原实时 WS（已不可用） | —— | 无可用模型 |

因此 2026-10-22 把 Java 里的实时链路**整体删除**，而不是迁移：

- 删除 `DashScopeTtsRealtimeService`（444 行）
- `VoiceCallDuplexSession` 698 → 403 行（删掉 WS 会话管理、预热、握手等待、句子推送）
- `VoiceDuplexHandler` 去掉该依赖；`DashScopeCloudEndpointValidator.realtimeWsUrl` 删除
- Python 侧 `realtime_ws_url` / `REALTIME_WS_PATH` 一并删除（同为死代码）

**保留** `nextCommitEnd` / `indexOfAnyFrom`（句子切分，纯文本逻辑 + 完整单测）：
下一步「按句流水线预取」（上一句在播、下一句已在合成）要用同一套规则。

### 检查器同时补上了盲区

`check-java-ai-free.ps1` 此前**完全没有 wss 模式**，所以那 444 行一直扫不到 ——
它输出的「Java 已无任何 AI 逻辑残留」是**假阴性**。现在补了
`api-ws/v1/realtime` 与 `DashScopeTtsRealtimeService` 两个模式，并把后者加进
「已迁往 Python 的客户端复活」清单。刻意**不用**泛化的 `wss://`：`AsrStreamClient`
也走 WebSocket，但连的是我们自己的本地 ASR 容器，不算 AI 出站调用。

补完之后 ratchet 先红了（22 处 / 444 行），删干净后回到 0/0/0/0 —— 这次是真绿。

## 七、原始计划（第一轮，保留作对照）

### 迁移顺序（按风险从低到高）
1. 音色注册（纯 HTTP，最低风险）—— **已完成**
2. 非实时 TTS（HTTP 单次合成）—— **已完成**
3. 实时 TTS（WebSocket，最高风险）—— **已退役**（平台无可用模型，见第六节）
4. 补检查器 —— **已完成**（`MODEL_CALLS` 增加出站路径模式 + 客户端复活检测 + wss 盲区）

### 验收标准
- `mvn test` / `pytest` 全绿 —— ✅
- 检查器新增模式后仍 0/0/0，且**证明它不是空转** —— ✅
- 本地容器：发一条语音消息 → 能播放 —— ⚠️ 协议层通过，音色一致性未验证（见 §5）
- 线上灰度：先单实例（`agent` only），观察 30 分钟再放到 `agent2` —— 未做
