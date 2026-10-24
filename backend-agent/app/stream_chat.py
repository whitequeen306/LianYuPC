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
    tool_names: list[str] | None = None,
    tool_context: dict[str, Any] | None = None,
    low_latency: bool = False,
) -> AsyncIterator[str]:
    """产出符合 Java 契约的 SSE 帧序列。

    工具轮次是**流式**的：模型可以在任意一轮返回 `tool_calls`，执行完把
    assistant / tool 消息回灌，再发起下一轮流式请求 —— 后续回答依旧逐 token 下发。
    轮次上限由 `settings.max_tool_rounds` 控制（审计 A4：有界，绝不无限递归）。

    兜底：部分上游会把 `tool_calls` 分片吞掉（流里只剩空文本）。此时用
    非流式的 `run_tool_loop` 补一次，避免这一轮变成「对方没有回复内容」。
    """
    settings = get_settings()
    from app.tool_loop import execute_tool_calls, resolve_tools  # 局部导入：避免启动期耦合

    specs = resolve_tools(tool_names) if tool_names else []
    # 没有可执行工具时不下发 schema：只给提示词会让模型以为能调，
    # 反而吐出 `<｜｜DSML｜｜ tool_calls>` 这类原始标记。
    schemas = tools if (specs or not tool_names) else None

    history = list(messages)
    collected: list[str] = []
    rounds = 0
    try:
        while True:
            round_text: list[str] = []
            tool_sink: list[dict[str, Any]] = []
            async for delta in gateway.stream_text(
                base_url=base_url,
                api_key=api_key,
                model=model,
                messages=history,
                temperature=temperature,
                max_tokens=max_tokens,
                tools=schemas,
                provider=provider,
                lane_name="interactive",
                tool_sink=tool_sink,
                low_latency=low_latency,
            ):
                round_text.append(delta)
                collected.append(delta)
                yield sse_content(delta)

            if not tool_sink or not specs:
                break
            if rounds >= settings.max_tool_rounds:
                logger.warning(
                    "stream tool loop truncated at %d rounds (%d calls dropped)",
                    settings.max_tool_rounds, len(tool_sink),
                )
                break

            history.append({
                "role": "assistant",
                "content": "".join(round_text),
                "tool_calls": tool_sink,
            })
            outputs = await execute_tool_calls(specs, tool_sink, tool_context)
            for call, (name, output) in zip(tool_sink, outputs):
                logger.info("stream tool call: round=%d name=%s chars=%d",
                            rounds + 1, name, len(output or ""))
                history.append({
                    "role": "tool",
                    "tool_call_id": call.get("id") or "",
                    "content": output,
                })
            rounds += 1
    except GatewayRejectedError:
        # 快速失败：熔断打开 / 隔舱满 —— 对齐 Java 的「对方模型暂时繁忙」
        yield sse_error("对方模型暂时繁忙，请稍后再试")
        yield DONE_FRAME
        return
    except GatewayError as exc:
        logger.warning("stream failed: %s", exc)
        yield sse_error(_humanize(exc))
        yield DONE_FRAME
        return

    content = "".join(collected)
    if not content.strip() and specs:
        # 上游把 tool_calls 吞了：退化成非流式循环补一次（结果整段下发）
        content = await _tool_fallback(
            gateway,
            base_url=base_url,
            api_key=api_key,
            model=model,
            messages=messages,
            tool_names=tool_names,
            tool_context=tool_context,
            temperature=temperature,
            max_tokens=max_tokens,
            provider=provider,
        )
        if content.strip():
            yield sse_content(content)

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


async def _tool_fallback(
    gateway: ModelGateway,
    *,
    base_url: str,
    api_key: str,
    model: str,
    messages: list[dict[str, Any]],
    tool_names: list[str],
    tool_context: dict[str, Any] | None,
    temperature: float,
    max_tokens: int,
    provider: str | None,
) -> str:
    """流式无文本时的工具回退：复用有界工具循环，返回最终文本（失败返回空串）。

    轮次上限仍由 `settings.max_tool_rounds` 控制（`run_tool_loop` 内部），
    绝不让「模型↔工具」无限递归 —— 这是审计 A4 的硬要求。
    """
    from app.tool_loop import run_tool_loop

    try:
        loop = await run_tool_loop(
            gateway,
            base_url=base_url,
            api_key=api_key,
            model=model,
            messages=messages,
            tool_names=tool_names,
            temperature=temperature,
            max_tokens=max_tokens,
            provider=provider,
            lane_name="interactive",
            tool_context=tool_context,
        )
    except GatewayError as exc:
        logger.warning("stream tool fallback failed: %s", exc)
        return ""
    logger.info(
        "stream tool fallback: rounds=%d tools=%s chars=%d",
        loop.rounds, loop.tool_names_called, len(loop.content or ""),
    )
    return loop.content or ""


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
