# 用户角色语音通话 — 设计

日期：2026-08-04  
状态：已批准（用户确认两链路一起做）

## 目标

给**用户自建角色**开通与官方类似的语音通话：须自行提供「语音模型」+「参考音频（mp3/wav）」。官方内置 `pet-voices.json` / `PetVoiceRegistry` **不动、不混存**。

## 非目标

- 不给官方内置角色覆盖音色
- 后端不请求用户填的本地 URL（防 SSRF）
- 第一期不做打赏门槛（此前「打赏标识」澄清为「按角色标识绑定」）

## 存储

表 `user_custom_voice`：

| 列 | 说明 |
|---|---|
| user_id + character_id | UNIQUE；音色挂在（用户，角色）上 |
| provider | `DASHSCOPE_VC` / `GPTSOVITS_LOCAL` |
| http_voice_id / realtime_voice_id | 仅 DashScope；两套不混用 |
| ref_audio_object_key | MinIO key（`custom-voices/{userId}/...`） |
| ref_text | 本地零样本参考文本（GPT-SoVITS 必需） |
| endpoint | 仅本地；只下发客户端，后端永不请求 |
| api_key_vault_id | 用户 DashScope key 所在 vault 行（可空，本地不需要） |
| status | `READY` / `FAILED` / `PENDING` |
| error_message | 报名失败可读信息（不泄露内部栈） |

官方 `pet-voices.json` 零改动。

## 凭证

- DashScope：用户自带 key，存入现有 `api_key_vault`，固定 provider 别名 `dashscope-tts`（USER scope）
- GPT-SoVITS：无云端 key；客户端直连 `endpoint`（默认 `http://127.0.0.1:9880`）

## 链路

### A. DASHSCOPE_VC

1. 上传 mp3/wav → 校验扩展名/MIME/大小/时长 → ffmpeg 转 48k mono → MinIO
2. 用用户 vault key 调 DashScope enrollment（HTTP target + realtime target 各一次）
3. 存两个 voice_id；通话走现有 WS 双工（`startWithVoiceId`）

### B. GPTSOVITS_LOCAL

1. 上传参考音频 + 参考文本 + endpoint（仅允许 loopback / 私网格式校验后存库）
2. 通话：后端只出文本；Electron 拉参考音频缓存，直连本地 SoVITS 逐句合成播放（非流式降级）

## 解析顺序（VoiceCall）

1. 查 `user_custom_voice`（userId, characterId）且 status=READY → 自定义分支（**绕过** `VOICE_CALL_PET_IDS`）
2. 否则走官方 `resolveVoicePetId` + 白名单

## API

- `POST /api/character/{id}/custom-voice` multipart：audio + provider + refText? + endpoint? + apiKey?
- `GET /api/character/{id}/custom-voice`
- `DELETE /api/character/{id}/custom-voice`（删库 + MinIO + 可选 DashScope delete）

## 安全

1. 音频：仅 `.mp3`/`.wav`，MIME 嗅探，大小上限（如 15MB），时长 5–120s
2. MinIO：`custom-voices/` 加入 `SAFE_OBJECT_KEY` 白名单
3. endpoint：仅 `http://127.0.0.1|localhost|*.local` 或 RFC1918；后端合成路径绝不 `HttpClient` 该 URL
4. 密钥：不入日志；错误消息脱敏
5. 所有权：`findOwned(userId, characterId)`

## 前端

- `CharacterChatDetailPage` 新「语音通话」区块（自备模型 + 音频）
- `voiceCallEnabled`：官方 pet **或** 该角色有 READY 自定义音色
- Electron：IPC `custom-tts:sovits` 供本地合成

## 体验差异

| | DashScope | GPT-SoVITS |
|---|---|---|
| 双工流式 | 是 | 否（逐句） |
| 首音延迟 | 接近官方 | 较慢 |
| 费用 | 用户自己的 DashScope | 本地算力 |
