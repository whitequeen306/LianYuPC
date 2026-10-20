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
from contextlib import asynccontextmanager
from typing import Any, AsyncIterator

from app.config import get_settings
from app.resilience import GatewayBusinessError, GatewayError
from app.http_client import new_client
from app.resilience import (
    GatewayRejectedError,
    breaker_registry,
    call_with_transient_retry,
    is_transient_failure,
    lane,
)
from app.resilience import GatewayError as _resilience_GatewayError

logger = logging.getLogger(__name__)


@asynccontextmanager
async def _null_context(client):
    """把一个已注入的 client 包成 async context manager（不负责关闭）。"""
    yield client


@dataclass(frozen=True)
class TextPart:
    text: str


@dataclass(frozen=True)
class ImagePart:
    """inline base64 PNG，对齐 Java Media.builder().data(byte[])。"""

    png_base64: str


Part = TextPart | ImagePart


# NOTE: 归一化函数不属于 dataclass
def _raise_for_status(status: int) -> None:
    """把上游 HTTP 状态映射成两类异常，决定是否计入熔断。

    - **4xx（429 除外）= 用户侧问题**（key 失效/欠费/model 名错/路径版本不对）：
      按 `GatewayBusinessError` 处理，**不计入熔断**。
      否则一个用户把 key 弄坏，就会按 `provider|baseUrl` 把同 provider 的
      其他用户一起熔断（2026-10-20 生产 `circuit open for Provider326`）。
    - **5xx / 429 / 传输层失败 = 上游问题**：计入熔断并可能快失败。
    """
    if 200 <= status < 300:
        return
    if 400 <= status < 500 and status != 429:
        raise GatewayBusinessError(f"model gateway http {status}")
    raise ModelGatewayError(f"model gateway http {status}")

def normalize_openai_base_url(base_url: str) -> str:
    """把 vault 里的 base_url 归一化成 OpenAI 兼容的 completions 端点。

    必须与旧 Java 行为一致（`ChatModelFactory.normalizeOpenAiBaseUrl`
    + Spring AI 默认 `completionsPath=/v1/chat/completions`）：

    1. 去掉末尾所有 `/`
    2. 若以 `/v1` 结尾则去掉这个 `/v1`
    3. 固定追加 `/v1/chat/completions`

    生产 vault 里同时存在 `https://api.deepseek.com` 与
    `https://clove.dpdns.org/v1` 两种写法；只按 `<base>/chat/completions`
    拼会漏掉 `/v1`，对不带 `/v1` 的代理站直接 404。
    """
    trimmed = (base_url or "").strip()
    while trimmed.endswith("/"):
        trimmed = trimmed[:-1]
    if trimmed.endswith("/v1"):
        trimmed = trimmed[: -len("/v1")]
    return f"{trimmed}/v1/chat/completions"

@dataclass(frozen=True)
class GatewayResult:
    content: str
    prompt_tokens: int | None = None
    completion_tokens: int | None = None
    tool_calls: list[dict[str, Any]] | None = None


class ModelGatewayError(GatewayError):
    """模型网关错误（对齐 Java BusinessException(AI_PROVIDER_ERROR) 语义）。"""


# 统一捕获元组：调用方 `except MODEL_GATEWAY_ERRORS` 可同时接住
# 上游 HTTP 错误（ModelGatewayError）与熔断/隔舱快速失败（GatewayRejectedError）。
MODEL_GATEWAY_ERRORS: tuple[type[Exception], ...] = (
    ModelGatewayError,
    _resilience_GatewayError,  # GatewayRejectedError 是其子类，一并覆盖
)


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
        provider: str | None = None,
        lane_name: str = "interactive",
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
        return await self._post_chat(base_url, api_key, payload, provider=provider, lane_name=lane_name)

    async def chat_sync(
        self,
        *,
        base_url: str,
        api_key: str,
        model: str,
        system: str | None,
        user_text: str,
        temperature: float,
        max_tokens: int,
        provider: str | None = None,
        lane_name: str = "interactive",
    ) -> GatewayResult:
        """纯文本对话（LangGraph 节点用）。"""
        messages: list[dict[str, Any]] = []
        if system:
            messages.append({"role": "system", "content": system})
        messages.append({"role": "user", "content": user_text})
        payload = {
            "model": model,
            "messages": messages,
            "temperature": temperature,
            "max_tokens": max_tokens,
        }
        return await self._post_chat(base_url, api_key, payload, provider=provider, lane_name=lane_name)

    async def stream_text(
        self,
        *,
        base_url: str,
        api_key: str,
        model: str,
        messages: list[dict[str, Any]],
        temperature: float,
        max_tokens: int,
        tools: list[dict[str, Any]] | None = None,
        provider: str | None = None,
        lane_name: str = "interactive",
    ) -> AsyncIterator[str]:
        """流式取 token 增量（OpenAI SSE）。

        韧性语义与阻塞路径一致：隔舱占槽 + 熔断许可 + 首字前瞬断重试。
        一旦已经开始吐字就不再重试（重试会重复生成、重复扣 token）。
        """
        payload: dict[str, Any] = {
            "model": model,
            "messages": messages,
            "temperature": temperature,
            "max_tokens": max_tokens,
            "stream": True,
        }
        if tools:
            payload["tools"] = tools
            payload["tool_choice"] = "auto"

        breaker = breaker_registry().resolve(provider, base_url)
        async with lane(lane_name):
            if not await breaker.try_acquire():
                logger.warning("breaker %s OPEN, fast-fail stream %s", breaker.name, provider or base_url)
                raise GatewayRejectedError(f"upstream circuit open for {provider or base_url}")

            emitted_any = False
            try:
                attempt = 0
                while True:
                    attempt += 1
                    try:
                        async for delta in self._stream_once(base_url, api_key, payload):
                            emitted_any = True
                            yield delta
                        break
                    except Exception as exc:  # noqa: BLE001
                        # 首字前且瞬断 → 重试一次；其余直接抛
                        if (not emitted_any and attempt < 2
                                and is_transient_failure(exc)):
                            logger.warning(
                                "gateway stream transient failure, retrying (%d/2): %s", attempt, exc
                            )
                            continue
                        raise
            except BaseException as exc:
                await breaker.on_error(exc)
                raise
            await breaker.on_success()

    async def _stream_once(
        self, base_url: str, api_key: str, payload: dict[str, Any]
    ) -> AsyncIterator[str]:
        url = normalize_openai_base_url(base_url)
        headers = {"Authorization": f"Bearer {api_key}"}

        if self._client is not None:
            client_cm = _null_context(self._client)
        else:
            client_cm = new_client()

        async with client_cm as client:
            try:
                async with client.stream("POST", url, json=payload, headers=headers) as response:
                    if response.status_code != 200:
                        _raise_for_status(response.status_code)
                    async for line in response.aiter_lines():
                        if not line:
                            continue
                        trimmed = line.strip()
                        if not trimmed.startswith("data:"):
                            continue
                        data = trimmed[5:].strip()
                        if data == "[DONE]":
                            return
                        try:
                            frame = json.loads(data)
                        except json.JSONDecodeError:
                            continue
                        choices = frame.get("choices") or []
                        if not choices:
                            continue
                        delta = (choices[0].get("delta") or {}).get("content")
                        if delta:
                            yield delta
            except ModelGatewayError:
                raise
            except Exception as exc:  # 网络层
                raise ModelGatewayError(f"model gateway transport error: {exc}") from exc

    async def chat_messages(
        self,
        *,
        base_url: str,
        api_key: str,
        model: str,
        messages: list[dict[str, Any]],
        temperature: float,
        max_tokens: int,
        provider: str | None = None,
        lane_name: str = "interactive",
    ) -> GatewayResult:
        """按给定 message 列表做一次阻塞调用（语言门纠错、工具循环用）。"""
        payload: dict[str, Any] = {
            "model": model,
            "messages": messages,
            "temperature": temperature,
            "max_tokens": max_tokens,
        }
        return await self._post_chat(base_url, api_key, payload, provider=provider, lane_name=lane_name)

    async def chat_payload(
        self,
        *,
        base_url: str,
        api_key: str,
        payload: dict[str, Any],
        provider: str | None = None,
        lane_name: str = "interactive",
    ) -> GatewayResult:
        """原样透传 payload（工具调用需要自定义 	ools / 	ool_choice）。"""
        return await self._post_chat(base_url, api_key, payload, provider=provider, lane_name=lane_name)

    async def _post_chat(self, base_url: str, api_key: str, payload: dict[str, Any],
                         *, provider: str | None = None, lane_name: str = "interactive") -> GatewayResult:
        """唯一出站路径：隔舱 → 熔断 → 瞬断重试 → HTTP。

        顺序对齐 Java `AiChatService.chatStream/chatBlocking`：
        先拿 bulkhead（快速失败），再拿上游 breaker 许可，再发请求；
        breaker 只观察**最终**结果（重试发生在许可内，与 Java 的 executeCallable 一致）。
        """
        breaker = breaker_registry().resolve(provider, base_url)
        async with lane(lane_name):
            if not await breaker.try_acquire():
                logger.warning("breaker %s OPEN, fast-fail %s", breaker.name, provider or base_url)
                raise GatewayRejectedError(
                    f"upstream circuit open for {provider or base_url}"
                )
            try:
                result = await call_with_transient_retry(
                    lambda: self._do_post(base_url, api_key, payload),
                    label=f"gateway({provider or base_url})",
                )
            except BaseException as exc:
                await breaker.on_error(exc)
                raise
            await breaker.on_success()
            return result

    async def _do_post(self, base_url: str, api_key: str, payload: dict[str, Any]) -> GatewayResult:
        url = normalize_openai_base_url(base_url)
        headers = {"Authorization": f"Bearer {api_key}"}

        try:
            if self._client is not None:
                response = await self._client.post(url, json=payload, headers=headers)
                status = response.status_code
                try:
                    body = response.json()
                except Exception:
                    body = None
            else:
                async with new_client() as client:
                    response = await client.post(url, json=payload, headers=headers)
                    status = response.status_code
                    try:
                        body = response.json()
                    except Exception:
                        body = None
        except Exception as exc:  # 网络层失败：连接拒绝/DNS/读超时
            raise ModelGatewayError(f"model gateway transport error: {exc}") from exc

        if status != 200:
            raise ModelGatewayError(f"model gateway http {status}")
        if not isinstance(body, dict):
            raise ModelGatewayError("model gateway malformed response")
        try:
            choice = body["choices"][0]["message"]["content"]
        except (KeyError, IndexError, TypeError) as exc:
            raise ModelGatewayError("model gateway malformed response") from exc
        usage = body.get("usage") or {}
        message = body["choices"][0].get("message") or {}
        tool_calls = message.get("tool_calls")
        return GatewayResult(
            content=choice or "",
            prompt_tokens=usage.get("prompt_tokens"),
            completion_tokens=usage.get("completion_tokens"),
            tool_calls=tool_calls if isinstance(tool_calls, list) else None,
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
