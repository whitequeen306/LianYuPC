"""DashScope 云端端点归一化与域名白名单。

这是 Java `DashScopeCloudEndpointValidator` 的 Python 等价物，但**职责分了两层**：

- Java 保留「用户填的 base_url / model 是否合法」这一层**输入校验**，
  因为那是写库前的产品规则（写进 `user_custom_voice.endpoint` 的值要可信）。
- 真正**拼出站 URL** 的地方在 Python，所以 `enroll_url` / `synth_url` /
  `realtime_ws_url` 只在这里存在。出站地址的最终形态由发起调用的那一侧决定，
  否则又会出现「Java 说一套、Python 说一套」的分叉（见 prompt_pipeline 那次教训）。

错误文案逐字对齐 Java，避免前端提示语变化。
"""

import re
from urllib.parse import urlsplit

DEFAULT_BASE = "https://dashscope.aliyuncs.com"
RECOMMENDED_HTTP_MODEL = "qwen3-tts-vc-2026-01-22"
RECOMMENDED_REALTIME_MODEL = "qwen3-tts-vc-realtime-2026-01-15"

ENROLL_PATH = "/api/v1/services/audio/tts/customization"
SYNTH_PATH = "/api/v1/services/aigc/multimodal-generation/generation"
REALTIME_WS_PATH = "/api-ws/v1/realtime"

_ALLOWED_HOST = re.compile(r"^(dashscope(-intl)?\.aliyuncs\.com)$", re.IGNORECASE)
_SAFE_MODEL = re.compile(r"^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$")


class DashScopeEndpointError(ValueError):
    """用户侧地址/模型不合法（对齐 Java BusinessException(BAD_REQUEST)）。"""


def normalize_base_url(raw: str | None) -> str:
    """只保留 scheme://host。

    对齐 Java 语义：仅 https、仅官方域名、不允许 userinfo、不允许自定义端口
    （443 显式写出视为等价）。路径一律丢弃 —— 调用方只会拿到 host。
    """
    text = (raw or "").strip() or DEFAULT_BASE
    # Java URI.create 对含空白的字符串直接抛 IllegalArgumentException
    if any(ch in text for ch in " \t\r\n"):
        raise DashScopeEndpointError("API 地址格式无效")
    try:
        parts = urlsplit(text)
        port = parts.port
        host = parts.hostname
    except ValueError as exc:  # 非法端口 / 非法 IPv6 字面量
        raise DashScopeEndpointError("API 地址格式无效") from exc

    scheme = (parts.scheme or "").lower()
    if scheme != "https":
        raise DashScopeEndpointError("API 地址仅支持 https")
    if not host or not _ALLOWED_HOST.match(host):
        raise DashScopeEndpointError(
            "API 地址仅支持 dashscope.aliyuncs.com / dashscope-intl.aliyuncs.com"
        )
    if parts.username is not None or parts.password is not None:
        raise DashScopeEndpointError("API 地址不能包含用户信息")
    if port and port != 443:
        raise DashScopeEndpointError("API 地址不支持自定义端口")
    return f"{scheme}://{host.lower()}"


def normalize_model(raw: str | None, recommended: str, label: str) -> str:
    """模型名白名单校验（对齐 Java SAFE_MODEL 正则与文案）。"""
    model = (raw or "").strip() or recommended
    if not _SAFE_MODEL.match(model):
        raise DashScopeEndpointError(f"{label} 模型名无效")
    return model


def enroll_url(base: str | None) -> str:
    return normalize_base_url(base) + ENROLL_PATH


def synth_url(base: str | None) -> str:
    return normalize_base_url(base) + SYNTH_PATH


def realtime_ws_url(base: str | None) -> str:
    normalized = normalize_base_url(base)
    host = urlsplit(normalized).hostname
    return f"wss://{host}{REALTIME_WS_PATH}"


def is_allowed_audio_host(audio_url: str | None) -> bool:
    """TTS 合成结果只允许官方域名 / OSS 回源，防任意 URL 下载（SSRF）。

    对齐 Java `DashScopeTtsService.isAllowedTtsAudioHost`，并**补上国际站**：
    Java 版只放行 `dashscope.aliyuncs.com`，用户把 base 配成
    `dashscope-intl.aliyuncs.com` 时音频 URL 会被判为非法 → 静默无音频。
    既然 `normalize_base_url` 明确支持国际站，白名单必须同步，否则支持是假的。

    scheme 不再强制 https —— DashScope 的签名 OSS 地址**实际以 http 下发**
    （实测 `qwen3-tts-flash`：`http://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/...`）。
    Java 原实现硬要求 https，直接把这类响应判为非法，结果是**音频永远拿不到**。
    这里放宽到「http 或 https 都算合法」，由 {@link normalize_audio_url} 统一升级成 https 下载，
    既不放松域名范围，也不走明文。
    """
    if not audio_url:
        return False
    try:
        parts = urlsplit(audio_url.strip())
        host = parts.hostname
    except ValueError:
        return False
    if (parts.scheme or "").lower() not in ("http", "https") or not host:
        return False
    lower = host.lower()
    return (
        "dashscope.aliyuncs.com" == lower
        or "dashscope-intl.aliyuncs.com" == lower
        or lower.endswith(".dashscope.aliyuncs.com")
        or lower.endswith(".dashscope-intl.aliyuncs.com")
        or (lower.endswith(".aliyuncs.com") and ".oss-" in lower)
    )


def normalize_audio_url(audio_url: str | None) -> str | None:
    """校验域名白名单并把 scheme 升级为 https；不合法返回 None。

    签名 OSS 地址对 scheme 不敏感（同一签名 https 同样 200），
    所以「校验 http → 下载 https」是安全且无损的。
    """
    if not is_allowed_audio_host(audio_url):
        return None
    candidate = audio_url.strip()
    if candidate.lower().startswith("http://"):
        return "https://" + candidate[len("http://"):]
    return candidate



def guess_mime_type(url: str | None, audio: bytes) -> str:
    """对齐 Java `guessMimeType`：DashScope 实际返回 wav，缺省也按 wav。"""
    lower = (url or "").lower()
    if ".mp3" in lower:
        return "audio/mpeg"
    if ".wav" in lower or (len(audio) > 12 and audio[0:1] == b"R" and audio[1:2] == b"I"):
        return "audio/wav"
    return "audio/wav"


__all__ = [
    "DEFAULT_BASE",
    "DashScopeEndpointError",
    "ENROLL_PATH",
    "RECOMMENDED_HTTP_MODEL",
    "RECOMMENDED_REALTIME_MODEL",
    "REALTIME_WS_PATH",
    "SYNTH_PATH",
    "enroll_url",
    "guess_mime_type",
    "is_allowed_audio_host",
    "normalize_audio_url",
    "normalize_base_url",
    "normalize_model",
    "realtime_ws_url",
    "synth_url",
]
