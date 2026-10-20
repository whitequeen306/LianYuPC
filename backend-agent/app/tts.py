"""DashScope 非实时语音合成（HTTP multimodal-generation）。

搬过来的理由：和音色注册一样是**纯能力**。`VoiceCallService` / `VoiceCallDuplexSession`
/ `ObserveController` 里的产品规则（什么场景该出声、声音选哪个、失败怎么降级）
全部留在 Java，Python 只回答一个问题：「这段文本用这个音色合成出来的音频字节是什么」。

**失败一律返回 None，不抛异常** —— 这是原实现的契约，调用方早已按「没音频」分支优雅降级
（桌面问候只是不带音频，语音通话则回退成纯文本错误提示）。把 None 改成异常会
把「可选增强」变成「主链路故障」，那是行为退化。

对齐要点（逐条来自 `DashScopeTtsService`）：
- 请求体只有 `{model, input:{text, voice, language_type}}`，无 temperature；
- 先拿 audio URL，再下载音频，两步都可能 429/5xx → 视为瞬断重试一次；
- 下载前必须校验域名白名单（防任意 URL 下载）；
- 重试间隔 200ms × 尝试次数。
"""

import asyncio
import logging
from dataclasses import dataclass

import httpx

from app.dashscope import (
    DEFAULT_BASE,
    DashScopeEndpointError,
    SYNTH_PATH,
    guess_mime_type,
    normalize_audio_url,
    synth_url,
)
from app.http_client import new_client

logger = logging.getLogger(__name__)

LANGUAGE_TYPE_DEFAULT = "Chinese"
_MAX_ATTEMPTS = 2


@dataclass(frozen=True)
class SynthesizedAudio:
    mime_type: str
    audio: bytes

    @property
    def size(self) -> int:
        return len(self.audio)


class _TransientTtsError(RuntimeError):
    """429 / 5xx / 连接层失败 —— 值得重试一次的那一类。"""


def is_transient_network_failure(exc: BaseException | None) -> bool:
    """对齐 Java `isTransientNetworkFailure`。

    Java 只把「连不上」当瞬断（UnknownHost / ConnectException / ConnectTimeout），
    **不含读超时** —— 读超时说明请求已发出，重试会重复扣费。这里保持同样口径。
    """
    seen: set[int] = set()
    current: BaseException | None = exc
    while current is not None and id(current) not in seen:
        seen.add(id(current))
        if isinstance(current, (httpx.ConnectError, httpx.ConnectTimeout)):
            return True
        message = str(current).lower()
        for token in ("try again", "timed out", "connection reset", "failed to resolve", "transient http"):
            if token in message:
                return True
        current = current.__cause__ or current.__context__
    return False


async def synthesize(
    *,
    api_key: str | None,
    model: str | None,
    voice_id: str | None,
    text: str | None,
    base_url: str | None = None,
    language_type: str | None = LANGUAGE_TYPE_DEFAULT,
    connect_timeout: float = 8.0,
    read_timeout: float = 30.0,
) -> SynthesizedAudio | None:
    """合成一段语音；不可用时返回 None（调用方已有降级路径）。"""
    spoken = (text or "").strip()
    if not spoken:
        return None
    voice = (voice_id or "").strip()
    if not voice:
        logger.info("tts skipped: empty voiceId")
        return None
    key = (api_key or "").strip()
    if not key:
        logger.warning("tts skipped: missing DashScope api key")
        return None
    resolved_model = (model or "").strip() or "qwen3-tts-vc-2026-01-22"

    if base_url and base_url.strip():
        try:
            url = synth_url(base_url)
        except DashScopeEndpointError:
            logger.warning("tts skipped: invalid api base")
            return None
    else:
        url = DEFAULT_BASE + SYNTH_PATH

    timeout = (connect_timeout, read_timeout, 30.0, 30.0)
    language = (language_type or "").strip() or LANGUAGE_TYPE_DEFAULT

    for attempt in range(1, _MAX_ATTEMPTS + 1):
        try:
            audio_url = await _request_audio_url(
                key=key, model=resolved_model, voice=voice, text=spoken,
                url=url, language=language, timeout=timeout,
            )
            if not audio_url:
                return None
            download_url = normalize_audio_url(audio_url)
            if not download_url:
                logger.warning("tts rejected audio url host")
                return None
            audio = await _download_audio(download_url, timeout=timeout)
            if not audio:
                logger.warning("tts audio download empty attempt=%d", attempt)
                if attempt < _MAX_ATTEMPTS:
                    await asyncio.sleep(0.2 * attempt)
                    continue
                return None
            logger.info("tts ok: bytes=%d attempt=%d", len(audio), attempt)
            return SynthesizedAudio(mime_type=guess_mime_type(None, audio), audio=audio)
        except Exception as exc:  # noqa: BLE001 - 合成失败一律降级
            if not is_transient_network_failure(exc) or attempt >= _MAX_ATTEMPTS:
                logger.warning("tts synthesis failed: %s", exc)
                return None
            logger.warning("tts transient failure attempt=%d/%d: %s", attempt, _MAX_ATTEMPTS, exc)
            await asyncio.sleep(0.2 * attempt)
    return None


async def _request_audio_url(
    *,
    key: str,
    model: str,
    voice: str,
    text: str,
    url: str,
    language: str,
    timeout: tuple[float, float, float, float],
) -> str | None:
    payload = {
        "model": model,
        "input": {"text": text, "voice": voice, "language_type": language},
    }
    async with new_client() as client:
        response = await client.post(
            url,
            json=payload,
            headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
            timeout=timeout,
        )
    if not 200 <= response.status_code < 300:
        logger.warning("tts synth HTTP %s: %s", response.status_code, response.text[:500])
        if response.status_code == 429 or response.status_code >= 500:
            raise _TransientTtsError(f"dashscope tts transient http {response.status_code}")
        return None
    try:
        body = response.json()
    except ValueError:
        logger.warning("tts response not json")
        return None
    audio = ((body or {}).get("output") or {}).get("audio") or {}
    audio_url = audio.get("url") if isinstance(audio, dict) else None
    if not isinstance(audio_url, str) or not audio_url.strip():
        logger.warning("tts response missing audio url")
        return None
    return audio_url


async def _download_audio(
    audio_url: str, *, timeout: tuple[float, float, float, float]
) -> bytes | None:
    async with new_client() as client:
        response = await client.get(audio_url, timeout=timeout)
    if not 200 <= response.status_code < 300:
        logger.warning("tts audio download HTTP %s", response.status_code)
        if response.status_code == 429 or response.status_code >= 500:
            raise _TransientTtsError(f"dashscope tts audio transient http {response.status_code}")
        return None
    return response.content


__all__ = [
    "LANGUAGE_TYPE_DEFAULT",
    "SynthesizedAudio",
    "is_transient_network_failure",
    "synthesize",
]
