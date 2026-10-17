"""有界工具循环 —— 审计 A4（工具循环无上限）的 Python 侧解药。

Java 侧只开了 Spring AI 的 `internalToolExecutionEnabled(true)`，框架
`OpenAiChatModel.internalCall` 递归调用自身且**没有计数器**，唯一兜底是 30 分钟 SSE 硬超时。

本模块显式设 `max_tool_rounds`（默认 3）：每轮模型可返 tool_calls，执行完回灌再问；
超过上限即停止并返回最后一次文本（可能为空），绝不无限递归。
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass, field
from typing import Any

import httpx

from app.config import get_settings
from app.gateway import GatewayResult, ModelGateway, ModelGatewayError
from app.tools import resolve_tools, supported_tool_schemas

logger = logging.getLogger(__name__)


@dataclass
class ToolLoopResult:
    content: str
    rounds: int
    truncated: bool = False
    tool_names_called: list[str] = field(default_factory=list)


async def _post_with_tools(
    gateway: ModelGateway,
    *,
    base_url: str,
    api_key: str,
    model: str,
    messages: list[dict[str, Any]],
    tool_schemas: list[dict[str, Any]],
    temperature: float,
    max_tokens: int,
    provider: str | None,
    lane_name: str,
) -> tuple[str, list[dict[str, Any]]]:
    """一次带工具的调用；返回 (文本, tool_calls)。"""
    payload: dict[str, Any] = {
        "model": model,
        "messages": messages,
        "temperature": temperature,
        "max_tokens": max_tokens,
    }
    if tool_schemas:
        payload["tools"] = tool_schemas
        payload["tool_choice"] = "auto"

    result: GatewayResult = await gateway.chat_payload(
        base_url=base_url,
        api_key=api_key,
        payload=payload,
        provider=provider,
        lane_name=lane_name,
    )
    return result.content, list(result.tool_calls or [])


async def run_tool_loop(
    gateway: ModelGateway,
    *,
    base_url: str,
    api_key: str,
    model: str,
    messages: list[dict[str, Any]],
    tool_names: list[str] | None,
    temperature: float,
    max_tokens: int,
    provider: str | None = None,
    lane_name: str = "background",
    max_rounds: int | None = None,
) -> ToolLoopResult:
    """跑一轮「模型 → 工具 → 模型」循环，最多 `max_rounds` 轮工具调用。"""
    settings = get_settings()
    rounds_limit = settings.max_tool_rounds if max_rounds is None else max_rounds
    specs = resolve_tools(tool_names)
    schemas = supported_tool_schemas(tool_names)
    if not specs:
        # 无工具：单次调用，零额外开销
        result = await gateway.chat_sync(
            base_url=base_url,
            api_key=api_key,
            model=model,
            system=None,
            user_text=_flatten(messages),
            temperature=temperature,
            max_tokens=max_tokens,
            provider=provider,
            lane_name=lane_name,
        )
        return ToolLoopResult(content=result.content, rounds=0)

    history = list(messages)
    called: list[str] = []
    rounds = 0
    content = ""

    while True:
        content, tool_calls = await _post_with_tools(
            gateway,
            base_url=base_url,
            api_key=api_key,
            model=model,
            messages=history,
            tool_schemas=schemas,
            temperature=temperature,
            max_tokens=max_tokens,
            provider=provider,
            lane_name=lane_name,
        )
        if not tool_calls:
            return ToolLoopResult(content=content, rounds=rounds, tool_names_called=called)

        if rounds >= rounds_limit:
            logger.warning(
                "tool loop truncated at %d rounds (requested %d tool calls, dropped)",
                rounds_limit, len(tool_calls),
            )
            return ToolLoopResult(
                content=content, rounds=rounds, truncated=True, tool_names_called=called
            )

        # 把 assistant 的 tool_calls 帧与每个工具结果回灌
        history.append({
            "role": "assistant",
            "content": content or "",
            "tool_calls": tool_calls,
        })
        for call in tool_calls:
            name = (call.get("function") or {}).get("name") or ""
            raw_args = (call.get("function") or {}).get("arguments") or "{}"
            try:
                arguments = json.loads(raw_args) if isinstance(raw_args, str) else (raw_args or {})
            except json.JSONDecodeError:
                arguments = {}
            spec = next((s for s in specs if s.name == name), None)
            if spec is None:
                output = f"工具 {name} 不可用"
            else:
                try:
                    output = await spec.handler(arguments if isinstance(arguments, dict) else {})
                except (httpx.HTTPError, RuntimeError) as exc:
                    logger.warning("tool %s failed: %s", name, exc)
                    output = f"工具 {name} 调用失败：{exc}"
            called.append(name)
            history.append({
                "role": "tool",
                "tool_call_id": call.get("id") or "",
                "content": output,
            })
        rounds += 1


def _flatten(messages: list[dict[str, Any]]) -> str:
    """无工具路径：把消息折叠成单条 user 文本（与 proactive 图同样处理）。"""
    parts: list[str] = []
    for msg in messages:
        role = msg.get("role")
        if role == "system":
            continue
        prefix = "用户" if role == "user" else "你"
        parts.append(f"{prefix}：{msg.get('content') or ''}")
    return "\n".join(parts)


__all__ = ["ToolLoopResult", "run_tool_loop"]
