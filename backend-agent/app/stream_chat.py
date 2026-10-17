"""单聊流式回合 —— SSE 契约逐字节对齐 Java `SseChatStreamHelper`。

Java 契约（资产清单第 4 条，客户端依赖，不许改）：

| 帧 | 语义 |
|---|---|
| `{"content":"<delta>"}` | token 增量 |
| `{"replace":"<全文>"}` | 语言门重写后的整体替换 |
| `{"pieces":[...],"replace":"..."}` | beforeStreamComplete 的多气泡切分（**Java 侧产出**，本层不发） |
| `{"error":"<msg>"}` | 错误收尾（随后仍有 `[DONE]`） |
| `[DONE]` | 终止标记 |
| `: keep-alive` | 心跳注释行 |

收尾顺序：内容 →（可选）replace → `[DONE]`。
`pieces` 由 Java 的 `AssistantReplySplitter` 在 beforeStreamComplete 阶段生成，
因此**本层不产出 pieces**；若未来迁走切分逻辑，才需要在此补。
"""

from __future__ import annotations

import json
import logging
from typing import Any, AsyncIterator

from app.config import get_settings
from app.gateway import ModelGateway, ModelGatewayError
from app.language_gate import (
    correction_instruction,
    matches_expected,
    should_enforce_language_gate,
)
from app.resilience import GatewayError, GatewayRejectedError

logger = logging.getLogger(__name__)

DONE_FRAME = "data: [DONE]\n\n"
HEARTBEAT = ": keep-alive\n\n"


def sse_content(delta: str) -> str:
    return "data: " + json.dumps({"content": delta}, ensure_ascii=False) + "\n\n"


def sse_replace(full_text: str) -> str:
    return "data: " + json.dumps({"replace": full_text}, ensure_ascii=False) + "\n\n"


def sse_error(message: str) -> str:
    return "data: " + json.dumps({"error": message}, ensure_ascii=False) + "\n\n"


async def stream_chat_events(
    gateway: ModelGateway,
    *,
    base_url: str,
    api_key: str,
    model: str,
    messages: list[dict[str, Any]],
    temperature: float,
    max_tokens: int,
    provider: str | None = None,
    expected_language: str | None = None,
    tools: list[dict[str, Any]] | None = None,
) -> AsyncIterator[str]:
    """产出符合 Java 契约的 SSE 帧序列。"""
    settings = get_settings()
    collected: list[str] = []
    try:
        async for delta in gateway.stream_text(
            base_url=base_url,
            api_key=api_key,
            model=model,
            messages=messages,
            temperature=temperature,
            max_tokens=max_tokens,
            tools=tools,
            provider=provider,
            lane_name="interactive",
        ):
            collected.append(delta)
            yield sse_content(delta)
    except GatewayRejectedError:
        # 快速失败：熔断打开 / 隔舱满 —— 对齐 Java 的「对方模型暂时繁忙」
        yield sse_error("对方模型暂时繁忙，请稍后再试")
        yield DONE_FRAME
        return
    except ModelGatewayError as exc:
        logger.warning("stream failed: %s", exc)
        yield sse_error(_humanize(exc))
        yield DONE_FRAME
        return
    except GatewayError as exc:
        logger.warning("stream gateway error: %s", exc)
        yield sse_error(_humanize(exc))
        yield DONE_FRAME
        return

    content = "".join(collected)
    if not content.strip():
        yield sse_error("对方没有回复内容，请重试")
        yield DONE_FRAME
        return

    # 语言门：仅在期望语言非 EN 且不匹配时，阻塞重生成并整体替换
    if should_enforce_language_gate(expected_language) and not matches_expected(content, expected_language):
        replaced = await _regenerate_in_expected_language(
            gateway,
            base_url=base_url,
            api_key=api_key,
            model=model,
            messages=messages,
            content=content,
            expected_language=expected_language or "",
            temperature=temperature,
            max_tokens=max_tokens,
            provider=provider,
            max_retries=settings.language_gate_max_retries,
        )
        if replaced and replaced != content:
            yield sse_replace(replaced)

    yield DONE_FRAME


async def _regenerate_in_expected_language(
    gateway: ModelGateway,
    *,
    base_url: str,
    api_key: str,
    model: str,
    messages: list[dict[str, Any]],
    content: str,
    expected_language: str,
    temperature: float,
    max_tokens: int,
    provider: str | None,
    max_retries: int,
) -> str | None:
    """对齐 Java `enforceExpectedLanguage`：最多 max_retries 次完整重生成。"""
    current = content
    retry_messages = list(messages)
    for attempt in range(1, max_retries + 1):
        retry_messages = retry_messages + [
            {"role": "assistant", "content": current},
            {"role": "user", "content": correction_instruction(expected_language)},
        ]
        logger.info("language gate retry %d (stream), expected=%s", attempt, expected_language)
        try:
            result = await gateway.chat_messages(
                base_url=base_url,
                api_key=api_key,
                model=model,
                messages=retry_messages,
                temperature=temperature,
                max_tokens=max_tokens,
                provider=provider,
                lane_name="interactive",
            )
        except Exception as exc:  # noqa: BLE001 - 纠错失败不应炸整个连接
            logger.warning("language gate regeneration failed: %s", exc)
            return None
        regenerated = (result.content or "").strip()
        if not regenerated:
            return None
        current = regenerated
        if matches_expected(current, expected_language):
            return current
    logger.warning("language gate exhausted (stream), expected=%s", expected_language)
    return current


def _humanize(exc: Exception) -> str:
    message = str(exc)
    if "http 429" in message or "rate" in message.lower():
        return "请求过于频繁，请稍后再试"
    if "transport" in message:
        return "连接对方模型失败，请稍后再试"
    return "消息发送失败，请稍后再试"


__all__ = [
    "DONE_FRAME",
    "HEARTBEAT",
    "sse_content",
    "sse_error",
    "sse_replace",
    "stream_chat_events",
]
