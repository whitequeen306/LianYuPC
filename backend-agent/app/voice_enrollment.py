"""DashScope 自定义音色注册（音色克隆 enroll / delete）。

搬过来的理由：这是**纯 HTTP 能力**，没有任何产品规则 ——
「哪个用户能给哪个角色配什么音色」「Key 怎么加密存库」全在 Java；
Python 只负责「拿这段音频去换一个 voice_id」。

2026-10-22 重写：**接口换版了**。
旧实现用 `qwen-voice-enrollment` + `action:create` + `preferred_name` + 读 `output.voice`，
现在拿新模型调它会返回 `400 PipelineNotFound: No pipeline for: <model>`。平台现役接口是：

    POST /api/v1/services/audio/tts/customization
    {"model": "voice-enrollment",
     "input": {"action": "create_voice", "target_model": <合成模型>,
               "prefix": <≤10 位字母数字>, "audio": <data URI 或公网 URL>}}
    -> {"output": {"voice_id": "..."}}

    {"model": "voice-enrollment", "input": {"action": "query_voice", "voice_id": "..."}}
    -> {"output": {"status": "DEPLOYING" | "OK" | ...}}

**注册后必须轮询 query_voice 到 OK**：刚创建时是 DEPLOYING，此时拿去合成会失败。

失败语义严格对齐原实现：
- 上游非 2xx / 无 voice_id / 部署失败 → 抛 {@link VoiceEnrollmentError}，message 已脱敏，
  由 Java 映射成 `BusinessException(AI_PROVIDER_ERROR, message)`。
- 删除音色**永不抛**（`deleteVoiceQuietly`）—— 删远端失败不该阻塞本地删记录。
"""

import asyncio
import base64
import json
import logging
import re
from typing import Any

from app.dashscope import enroll_url
from app.http_client import new_client

logger = logging.getLogger(__name__)

ENROLLMENT_MODEL = "voice-enrollment"

# 平台对 prefix 的硬约束（实测报错原文）：
#   "prefix should be english letter and number"（连下划线都不行）
#   "prefix should not be longer than 10 characters"
PREFIX_MAX_LEN = 10
PREFIX_FALLBACK = "custom"

# 音频体积上限 30MB（CustomVoiceAudioValidator）→ base64 膨胀后更大，
# 上传写超时必须放宽；读超时给到 3 分钟对齐 Java。
_ENROLL_TIMEOUT = (15.0, 180.0, 120.0, 30.0)  # connect, read, write, pool
_POLL_TIMEOUT = (15.0, 60.0, 30.0, 30.0)
_DELETE_TIMEOUT = (15.0, 60.0, 30.0, 30.0)

# 音色部署要等一会儿（实测几秒），最多等约 60 秒
_POLL_ATTEMPTS = 40
_POLL_INTERVAL_S = 1.5
_PENDING_STATUSES = {"DEPLOYING", "PROCESSING", "PENDING"}

_SK_PATTERN = re.compile(r"sk-[a-zA-Z0-9]{8,}", re.IGNORECASE)


class VoiceEnrollmentError(RuntimeError):
    """上游/传输失败；message 可安全展示给用户（已脱敏、已截断）。"""


def sanitize_preferred_name(preferred_name: str | None) -> str:
    """把任意名字压成平台接受的 prefix：**只保留字母数字，截断到 10 位**。

    旧实现按 Java 的 `[a-z0-9_]` 且最长 32 位来做，调用新接口会被 400 拒掉；
    中文名会被过滤成空串，此时退回 {@link PREFIX_FALLBACK}。
    """
    base = re.sub(r"[^a-z0-9]", "", (preferred_name or "").strip().lower())
    return base[:PREFIX_MAX_LEN] or PREFIX_FALLBACK


def sanitize_user_message(message: str | None) -> str:
    """对齐 Java：脱敏 sk-*、截断 160、空则兜底文案。"""
    text = (message or "").strip()
    if not text:
        return "语音报名失败"
    text = _SK_PATTERN.sub("[redacted]", text)
    return text[:160]


def _parse_json(raw: str | None) -> dict[str, Any]:
    if not raw:
        return {}
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError:
        return {}
    return parsed if isinstance(parsed, dict) else {}


def _output(root: dict[str, Any]) -> dict[str, Any]:
    output = root.get("output")
    return output if isinstance(output, dict) else {}


async def create_voice(
    *,
    api_key: str | None,
    base_url: str | None,
    target_model: str,
    preferred_name: str | None,
    audio_bytes: bytes,
    mime_type: str | None = "audio/wav",
    language: str | None = "zh",
) -> str:
    """注册一个自定义音色，返回 voice_id（已确认部署完成）。

    一个音色只能对应一个 target_model，跨模型不能复用（平台文档明确要求
    「合成模型须与创建音色时指定的目标模型一致」）。调用方要为每个目标模型各注册一次。

    `language` 仅为调用方签名兼容保留，**不发给上游**：新版 create_voice 不收这个字段。
    """
    key = (api_key or "").strip()
    if not key:
        raise VoiceEnrollmentError("请提供 API Key")
    target = (target_model or "").strip()
    if not target:
        raise VoiceEnrollmentError("缺少目标模型")

    url = enroll_url(base_url)
    mime = (mime_type or "").strip() or "audio/wav"
    headers = {"Authorization": f"Bearer {key}", "Content-Type": "application/json"}
    payload = {
        "model": ENROLLMENT_MODEL,
        "input": {
            "action": "create_voice",
            "target_model": target,
            "prefix": sanitize_preferred_name(preferred_name),
            "audio": f"data:{mime};base64,{base64.b64encode(audio_bytes).decode('ascii')}",
        },
    }

    try:
        async with new_client() as client:
            response = await client.post(
                url, json=payload, headers=headers, timeout=_ENROLL_TIMEOUT
            )
            root = _parse_json(response.text)
            if not 200 <= response.status_code < 300:
                raw_message = root.get("message") or root.get("code") or "报名失败"
                logger.warning(
                    "voice enroll failed status=%s code=%s",
                    response.status_code, root.get("code", ""),
                )
                raise VoiceEnrollmentError(sanitize_user_message(str(raw_message)))

            voice = _output(root).get("voice_id")
            if not isinstance(voice, str) or not voice.strip():
                raise VoiceEnrollmentError("语音报名未返回 voice_id")

            await _wait_until_ready(client, url, headers, voice)
            return voice
    except VoiceEnrollmentError:
        raise
    except Exception as exc:  # noqa: BLE001 - 网络层一律归为可展示失败
        logger.warning("voice enroll transport error: %s", exc)
        raise VoiceEnrollmentError("语音报名失败，请检查 API Key、地址与音频后重试") from exc


async def _wait_until_ready(client, url: str, headers: dict[str, str], voice_id: str) -> None:
    """轮询 query_voice 直到 status=OK。

    刚创建时是 DEPLOYING，这时拿去合成会失败 —— 所以这一步不是"锦上添花"，
    而是注册流程的必要环节。
    """
    payload = {
        "model": ENROLLMENT_MODEL,
        "input": {"action": "query_voice", "voice_id": voice_id},
    }
    last_status = "UNKNOWN"
    for attempt in range(_POLL_ATTEMPTS):
        response = await client.post(
            url, json=payload, headers=headers, timeout=_POLL_TIMEOUT
        )
        root = _parse_json(response.text)
        if not 200 <= response.status_code < 300:
            message = root.get("message") or root.get("code") or "查询音色失败"
            raise VoiceEnrollmentError(sanitize_user_message(str(message)))
        last_status = str(_output(root).get("status") or "UNKNOWN")
        if last_status == "OK":
            logger.info("voice ready voiceId=%s after %d polls", voice_id, attempt + 1)
            return
        if last_status not in _PENDING_STATUSES:
            raise VoiceEnrollmentError(sanitize_user_message(f"音色状态异常：{last_status}"))
        await asyncio.sleep(_POLL_INTERVAL_S)
    logger.warning("voice deploy timeout voiceId=%s lastStatus=%s", voice_id, last_status)
    raise VoiceEnrollmentError("音色部署超时，请稍后重试")


async def delete_voice_quietly(
    *, api_key: str | None, base_url: str | None, voice_id: str | None
) -> None:
    """删除远端音色，**任何失败都吞掉**（对齐 Java deleteVoiceQuietly）。

    动作名是 `delete_voice` + `voice_id`（实测：`action:delete` 会返回
    `400 invalid action`，而 `delete_voice` 配 `voice` 字段会报缺参数）。
    """
    key = (api_key or "").strip()
    voice = (voice_id or "").strip()
    if not key or not voice:
        return
    url = enroll_url(base_url)
    headers = {"Authorization": f"Bearer {key}", "Content-Type": "application/json"}
    payload = {
        "model": ENROLLMENT_MODEL,
        "input": {"action": "delete_voice", "voice_id": voice},
    }
    try:
        async with new_client() as client:
            response = await client.post(
                url, json=payload, headers=headers, timeout=_DELETE_TIMEOUT
            )
        logger.info(
            "voice delete voiceId=%s status=%s", voice, response.status_code
        )
    except Exception as exc:  # noqa: BLE001 - 删除是尽力而为
        logger.warning("voice delete ignored voiceId=%s: %s", voice, exc)


__all__ = [
    "ENROLLMENT_MODEL",
    "VoiceEnrollmentError",
    "create_voice",
    "delete_voice_quietly",
    "sanitize_preferred_name",
    "sanitize_user_message",
]
