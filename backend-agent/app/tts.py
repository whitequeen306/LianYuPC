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
import base64
import json
import logging
import time
from collections.abc import AsyncIterator
from dataclasses import dataclass

import httpx

from app.dashscope import (
    DEFAULT_BASE,
    DashScopeEndpointError,
    RECOMMENDED_HTTP_MODEL,
    SPEECH_SYNTH_PATH,
    guess_mime_type,
    normalize_audio_url,
    synth_path_for,
    synth_url,
)
from app.http_client import new_client, shared_client

logger = logging.getLogger(__name__)

LANGUAGE_TYPE_DEFAULT = "Chinese"
_MAX_ATTEMPTS = 2
# 流式合成固定 24kHz / 单声道 / s16le —— 与客户端 createPcmPlayback 的默认值一致。
# 改这里必须同步改前端，否则会变调或出现噪声。
STREAM_SAMPLE_RATE = 24000
STREAM_MIME = "audio/pcm"


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
        for token in ("try again", "timed out", "connection reset", "failed to resolve",
                      # 容器内 DNS 抖动的实际报文（2026-10-23 生产日志）：
                      # "[Errno -3] Temporary failure in name resolution"
                      # 漏掉这两个 token 就不会重试，表现为「那一句静默没声音」。
                      "name resolution", "temporary failure", "transient http"):
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
    resolved_model = (model or "").strip() or RECOMMENDED_HTTP_MODEL
    # 端点必须跟着模型家族走：qwen-audio-* 走 SpeechSynthesizer，
    # qwen3-tts-* 走旧的 multimodal-generation（发错端点会报 url error）。
    path = synth_path_for(resolved_model)

    if base_url and base_url.strip():
        try:
            url = synth_url(base_url, path)
        except DashScopeEndpointError:
            logger.warning("tts skipped: invalid api base")
            return None
    else:
        url = DEFAULT_BASE + path

    timeout = (connect_timeout, read_timeout, 30.0, 30.0)
    language = (language_type or "").strip() or LANGUAGE_TYPE_DEFAULT

    for attempt in range(1, _MAX_ATTEMPTS + 1):
        try:
            audio_url = await _request_audio_url(
                key=key, model=resolved_model, voice=voice, text=spoken,
                url=url, language=language, path=path, timeout=timeout,
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
    path: str,
    timeout: tuple[float, float, float, float],
) -> str | None:
    if path == SPEECH_SYNTH_PATH:
        # 新端点（qwen-audio-*）：要 format/sample_rate，不认 language_type
        payload = {
            "model": model,
            "input": {"text": text, "voice": voice, "format": "wav", "sample_rate": 24000},
        }
    else:
        # 旧端点（qwen3-tts-*）：语言走 language_type
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


async def synthesize_stream(
    *,
    api_key: str | None,
    model: str | None,
    voice_id: str | None,
    text: str | None,
    base_url: str | None = None,
    language_type: str | None = None,
    connect_timeout: float = 8.0,
    read_timeout: float = 30.0,
) -> AsyncIterator[bytes]:
    """流式合成：逐块产出 **PCM（s16le / 24kHz / 单声道）**，用于语音通话边收边播。

    为什么需要它：语音通话的体感由**首包延迟**决定。整句合成要等音频落地才能发第一帧，
    而按句流式可以把首包压到 ~0.9s，并且下一句可以在这一句播放时并行预取。

    降级语义与 {@link synthesize} 一致（**永不抛异常**）：
      · key / text / voice 缺失 → 直接不产出
      · 端点不是支持 SSE 的那一族 → 退回整句合成，产出单块
      · 上游 4xx/5xx 或中途断流 → 记日志后结束，已产出的块仍然有效
    """
    key = (api_key or "").strip()
    spoken = (text or "").strip()
    voice = (voice_id or "").strip()
    if not key or not spoken or not voice:
        logger.info("tts stream skipped: missing key/text/voice")
        return

    resolved_model = (model or "").strip() or RECOMMENDED_HTTP_MODEL
    path = synth_path_for(resolved_model)

    if path != SPEECH_SYNTH_PATH:
        # 旧 qwen3-tts 家族不支持 SSE 流式；退回整句，语义上仍然"有声音"
        audio = await synthesize(
            api_key=key,
            model=resolved_model,
            voice_id=voice,
            text=spoken,
            base_url=base_url,
            language_type=language_type,
            connect_timeout=connect_timeout,
            read_timeout=read_timeout,
        )
        if audio is not None:
            yield audio.audio
        return

    try:
        url = synth_url(base_url, path) if base_url and base_url.strip() else DEFAULT_BASE + path
    except DashScopeEndpointError:
        logger.warning("tts stream skipped: invalid api base")
        return

    payload = {
        "model": resolved_model,
        "input": {"text": spoken, "voice": voice, "format": "pcm", "sample_rate": STREAM_SAMPLE_RATE},
    }
    headers = {
        "Authorization": f"Bearer {key}",
        "Content-Type": "application/json",
        "X-DashScope-SSE": "enable",
    }

    emitted = 0
    first_chunk_ms: float | None = None
    started = time.perf_counter()
    # DashScope 的失败有两种形态：非 200，以及 **200 + `event:error` 的 SSE 帧**
    # （例如音色与模型不匹配时会返回 200 + Engine error code 418）。后者如果只数块数，
    # 线上表现就是「静默没有声音、日志里什么都没有」，根本没法查。所以留一条上游原文。
    upstream_error: str | None = None
    for attempt in (1, _MAX_ATTEMPTS):
        try:
            # 复用长连接：每句一次 POST，重新握手（境内到阿里云 150-300ms）纯属白送延迟
            client = shared_client("dashscope-tts")
            async with client.stream(
                "POST", url, json=payload, headers=headers,
                timeout=(connect_timeout, read_timeout, 30.0, 30.0),
            ) as response:
                if response.status_code != 200:
                    detail = (await response.aread())[:200]
                    logger.warning("tts stream HTTP %s: %s", response.status_code, detail)
                    return
                async for line in response.aiter_lines():
                    chunk = _decode_sse_audio(line)
                    if chunk:
                        if emitted == 0:
                            first_chunk_ms = (time.perf_counter() - started) * 1000
                        emitted += 1
                        yield chunk
                    elif emitted == 0 and upstream_error is None and line.startswith("data:"):
                        upstream_error = line[5:].strip()[:300]
            break
        except Exception as exc:  # noqa: BLE001 - 流式失败不向调用方抛
            # 仅在**一块音频都没发出去**时才重试：那种情况下重试不会重复播放，
            # 也不会有拼接问题。已经吐过音频再断流，只能就此收尾（客户端播已有的部分）。
            if emitted == 0 and attempt < _MAX_ATTEMPTS and is_transient_network_failure(exc):
                logger.warning(
                    "tts stream retry attempt=%d/%d after 0 chunks: %s",
                    attempt, _MAX_ATTEMPTS, exc,
                )
                await asyncio.sleep(0.2 * attempt)
                continue
            logger.warning("tts stream aborted after %d chunks: %s", emitted, exc)
            return
    if emitted == 0:
        logger.warning(
            "tts stream produced NO audio model=%s voice=%s upstream=%s",
            resolved_model, voice[:40], upstream_error,
        )
    logger.info(
        "tts stream done: chunks=%d model=%s firstChunkMs=%s totalMs=%d",
        emitted, resolved_model,
        None if first_chunk_ms is None else round(first_chunk_ms),
        (time.perf_counter() - started) * 1000,
    )


def _decode_sse_audio(line: str) -> bytes | None:
    """从一行 SSE 里取出 base64 音频块；不是音频行就返回 None。"""
    if not line or not line.startswith("data:"):
        return None
    raw = line[5:].strip()
    if not raw or raw == "[DONE]":
        return None
    try:
        obj = json.loads(raw)
    except json.JSONDecodeError:
        return None
    if not isinstance(obj, dict):
        return None
    data = ((obj.get("output") or {}).get("audio") or {}).get("data")
    if not isinstance(data, str) or not data:
        return None
    try:
        return base64.b64decode(data)
    except (ValueError, TypeError):
        return None


__all__ = [
    "LANGUAGE_TYPE_DEFAULT",
    "STREAM_MIME",
    "STREAM_SAMPLE_RATE",
    "SynthesizedAudio",
    "is_transient_network_failure",
    "synthesize",
    "synthesize_stream",
]
