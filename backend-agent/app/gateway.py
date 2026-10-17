"""ModelGateway —— 模型调用的唯一出口。

对齐审计 B1 的结构债修复：Java 侧 VisionChatService 直调 AiChatService 8 个 public
方法的「工具箱」模式不允许在 Python 侧复现。所有 OpenAI-compatible 出站调用统一走这里，
以后接 Ollama / 其它协议只扩展本模块。
"""

import base64
import json
import re
import logging
from dataclasses import dataclass
from typing import Any

from app.config import get_settings
from app.http_client import new_client

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class TextPart:
    text: str


@dataclass(frozen=True)
class ImagePart:
    """inline base64 PNG，对齐 Java Media.builder().data(byte[])。"""

    png_base64: str


Part = TextPart | ImagePart


@dataclass(frozen=True)
class GatewayResult:
    content: str
    prompt_tokens: int | None = None
    completion_tokens: int | None = None


class ModelGatewayError(Exception):
    """模型网关错误（对齐 Java BusinessException(AI_PROVIDER_ERROR) 语义）。"""


class ModelGateway:
    """OpenAI-compatible /chat/completions 客户端（阻塞模型调用走 to_thread）。"""

    def __init__(self, client: Any | None = None) -> None:
        self._client = client

    async def chat(
        self,
        *,
        base_url: str,
        api_key: str,
        model: str,
        system: str | None,
        parts: list[Part],
        temperature: float,
        max_tokens: int,
    ) -> GatewayResult:
        messages: list[dict[str, Any]] = []
        if system:
            messages.append({"role": "system", "content": system})
        content: list[dict[str, Any]] = []
        for part in parts:
            if isinstance(part, TextPart):
                content.append({"type": "text", "text": part.text})
            else:
                content.append({
                    "type": "image_url",
                    "image_url": {"url": f"data:image/png;base64,{part.png_base64}"},
                })
        messages.append({"role": "user", "content": content})

        payload = {
            "model": model,
            "messages": messages,
            "temperature": temperature,
            "max_tokens": max_tokens,
        }
        url = f"{base_url.rstrip('/')}/chat/completions"
        headers = {"Authorization": f"Bearer {api_key}"}

        try:
            if self._client is not None:
                response = await self._client.post(url, json=payload, headers=headers)
                status, body = response.status_code, response.json()
            else:
                async with new_client() as client:
                    response = await client.post(url, json=payload, headers=headers)
                    status, body = response.status_code, response.json()
        except Exception as exc:  # 网络层失败：连接拒绝/DNS/读超时
            raise ModelGatewayError(f"model gateway transport error: {exc}") from exc

        if status != 200:
            raise ModelGatewayError(f"model gateway http {status}")
        try:
            choice = body["choices"][0]["message"]["content"]
        except (KeyError, IndexError, TypeError) as exc:
            raise ModelGatewayError("model gateway malformed response") from exc
        usage = body.get("usage") or {}
        return GatewayResult(
            content=choice or "",
            prompt_tokens=usage.get("prompt_tokens"),
            completion_tokens=usage.get("completion_tokens"),
        )


def extract_json_object(raw: str) -> dict[str, Any] | None:
    """从模型输出中提取 JSON 对象，对齐 VisionAnalysisParser.unwrap 的三形态：
    <json>{...}</json> / ```json ...``` / 裸 JSON。
    """
    if not raw or not raw.strip():
        return None
    text = raw.strip()
    patterns = (
        re.compile(r"^<json>\s*(\{.*})\s*</json>$", re.DOTALL),
        re.compile(r"^```json\s*(\{.*})\s*```\s*$", re.DOTALL),
    )
    for pattern in patterns:
        match = pattern.match(text)
        if match:
            text = match.group(1).strip()
            break
    if not text.startswith("{"):
        return None
    try:
        parsed = json.loads(text)
    except json.JSONDecodeError:
        return None
    return parsed if isinstance(parsed, dict) else None


def decode_png_base64(image_base64: str) -> bytes:
    """校验并解码 base64 图片；非法输入抛 ValueError（对齐 Java IllegalArgumentException）。"""
    try:
        return base64.b64decode(image_base64, validate=True)
    except Exception as exc:
        raise ValueError("invalid base64 image") from exc


def get_gateway() -> ModelGateway:
    return ModelGateway()


__all__ = [
    "GatewayResult",
    "ImagePart",
    "ModelGateway",
    "ModelGatewayError",
    "Part",
    "TextPart",
    "decode_png_base64",
    "extract_json_object",
    "get_gateway",
    "get_settings",
]
