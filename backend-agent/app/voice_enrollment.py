"""DashScope 自定义音色注册（音色克隆 enroll / delete）。

搬过来的理由：这是**纯 HTTP 能力**，没有任何产品规则 ——
「哪个用户能给哪个角色配什么音色」「Key 怎么加密存库」全在 Java；
Python 只负责「拿这段音频去换一个 voice_id」。

Java 侧的 `CustomVoiceService` 保持原样，只把 `enrollmentClient.createVoice(...)`
换成端口调用。这样这一刀切得干净：删掉的是 135 行 HTTP + JSON 拼装，
留下的是业务编排。

失败语义严格对齐原实现：
- 上游非 2xx / 无 voice_id → 抛 {@link VoiceEnrollmentError}，message 已脱敏，
  由 Java 映射成 `BusinessException(AI_PROVIDER_ERROR, message)`。
- 删除音色**永不抛**（`deleteVoiceQuietly`）—— 删远端失败不该阻塞本地删记录。
"""

import base64
import json
import logging
import re
from typing import Any

from app.dashscope import enroll_url
from app.http_client import new_client

logger = logging.getLogger(__name__)

ENROLLMENT_MODEL = "qwen-voice-enrollment"

# 音频体积上限 15MB（CustomVoiceAudioValidator）→ base64 约 20MB，
# 上传写超时必须放宽；读超时给到 3 分钟对齐 Java。
_ENROLL_TIMEOUT = (15.0, 180.0, 120.0, 30.0)  # connect, read, write, pool
_DELETE_TIMEOUT = (15.0, 60.0, 30.0, 30.0)

_SK_PATTERN = re.compile(r"sk-[a-zA-Z0-9]{8,}", re.IGNORECASE)


class VoiceEnrollmentError(RuntimeError):
    """上游/传输失败；message 可安全展示给用户（已脱敏、已截断）。"""


def sanitize_preferred_name(preferred_name: str | None) -> str:
    """对齐 Java：小写、非 [a-z0-9_] 变下划线、空则 custom、最长 32。"""
    base = (preferred_name or "custom").strip().lower()
    base = re.sub(r"[^a-z0-9_]", "_", base)
    if not base:
        base = "custom"
    return base[:32]


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
    """注册一个自定义音色，返回 voice_id。

    注意：一个音色只能对应一个 target_model，所以 HTTP 与 Realtime 需要**各注册一次**
    （Java `CustomVoiceService.enrollDashScope` 调两次），这里不做任何合并。
    """
    key = (api_key or "").strip()
    if not key:
        raise VoiceEnrollmentError("请提供 API Key")

    url = enroll_url(base_url)
    mime = (mime_type or "").strip() or "audio/wav"
    payload = {
        "model": ENROLLMENT_MODEL,
        "input": {
            "action": "create",
            "target_model": target_model,
            "preferred_name": sanitize_preferred_name(preferred_name),
            "language": (language or "").strip() or "zh",
            "audio": {
                "data": f"data:{mime};base64,{base64.b64encode(audio_bytes).decode('ascii')}",
            },
        },
    }

    try:
        async with new_client() as client:
            response = await client.post(
                url,
                json=payload,
                headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
                timeout=_ENROLL_TIMEOUT,
            )
    except Exception as exc:  # noqa: BLE001 - 网络层一律归为可展示失败
        logger.warning("voice enroll transport error: %s", exc)
        raise VoiceEnrollmentError("语音报名失败，请检查 API Key、地址与音频后重试") from exc

    root = _parse_json(response.text)
    if not 200 <= response.status_code < 300:
        raw_message = root.get("message") or root.get("code") or "报名失败"
        logger.warning(
            "voice enroll failed status=%s code=%s", response.status_code, root.get("code", "")
        )
        raise VoiceEnrollmentError(sanitize_user_message(str(raw_message)))

    output = root.get("output")
    voice = output.get("voice") if isinstance(output, dict) else None
    if not isinstance(voice, str) or not voice.strip():
        raise VoiceEnrollmentError("语音报名未返回 voice_id")
    return voice


async def delete_voice_quietly(
    *, api_key: str | None, base_url: str | None, voice_id: str | None
) -> None:
    """删除远端音色，**任何失败都吞掉**（对齐 Java deleteVoiceQuietly）。"""
    key = (api_key or "").strip()
    voice = (voice_id or "").strip()
    if not key or not voice:
        return
    try:
        url = enroll_url(base_url)
        payload = {
            "model": ENROLLMENT_MODEL,
            "input": {"action": "delete", "voice": voice},
        }
        async with new_client() as client:
            response = await client.post(
                url,
                json=payload,
                headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
                timeout=_DELETE_TIMEOUT,
            )
        logger.info("voice delete voiceId=%s status=%s", voice, response.status_code)
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
