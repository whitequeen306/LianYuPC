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

from app.capability.intent import (
    DESKTOP_ACTION_NUDGE,
    last_user_text,
    looks_like_desktop_action,
)
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
    # 各轮 usage 累加：非流式回合要用它落库 token 统计，丢失会让用户额度显示为 0
    prompt_tokens: int | None = None
    completion_tokens: int | None = None
    # 是否触发过「空口承诺」重问（见 app/capability/intent.py 的真实事故说明）
    nudged: bool = False


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
) -> tuple[str, list[dict[str, Any]], GatewayResult]:
    """一次带工具的调用；返回 (文本, tool_calls, 原始结果<含 usage>)。"""
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
    return result.content, list(result.tool_calls or []), result


async def execute_tool_calls(
    specs: list[Any],
    tool_calls: list[dict[str, Any]],
    tool_context: dict[str, Any] | None,
) -> list[tuple[str, str]]:
    """执行一组 tool_calls，返回 [(name, output)]。

    流式与非流式两条链路共用（`stream_chat_events` 的工具轮次也走这里），
    避免「怎么执行工具」出现两份实现。单个工具失败只影响它自己那条结果。
    """
    results: list[tuple[str, str]] = []
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
            # 上下文（userId/characterId/language）由调用方注入，对齐 Java ChatToolContext；
            # 模型看不到这些键，也不参与 schema。
            merged = dict(arguments if isinstance(arguments, dict) else {})
            if tool_context:
                merged.update(tool_context)
            try:
                output = await spec.handler(merged)
            except (httpx.HTTPError, RuntimeError) as exc:
                logger.warning("tool %s failed: %s", name, exc)
                output = f"工具 {name} 调用失败：{exc}"
        results.append((name, output))
    return results


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
    tool_context: dict[str, Any] | None = None,
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
        return ToolLoopResult(content=result.content, rounds=0,
                              prompt_tokens=result.prompt_tokens,
                              completion_tokens=result.completion_tokens)

    history = list(messages)
    called: list[str] = []
    rounds = 0
    content = ""
    prompt_tokens = 0
    completion_tokens = 0

    # 空口承诺防护：只有「桌面上确实可用」+「用户这句话是要动本机」时才启用，
    # 否则纯闲聊被追问一句会显得角色很笨。详见 app/capability/intent.py。
    desk_offered = any(str(name).startswith("desk_") for name in (tool_names or ()))
    wants_action = desk_offered and looks_like_desktop_action(last_user_text(messages))
    nudged = False

    while True:
        content, tool_calls, result = await _post_with_tools(
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
        prompt_tokens += result.prompt_tokens or 0
        completion_tokens += result.completion_tokens or 0
        if not tool_calls:
            if wants_action and not nudged and rounds == 0:
                # 模型第一轮只说话不动手 → 追问一次（只追一次，绝不递归）
                nudged = True
                logger.info(
                    "desktop action expected but model returned no tool_call; nudging once "
                    "(chars=%d, tools=%s)",
                    len(content or ""),
                    tool_names,
                )
                history.append({"role": "user", "content": DESKTOP_ACTION_NUDGE})
                continue
            return ToolLoopResult(content=content, rounds=rounds, tool_names_called=called,
                                  prompt_tokens=prompt_tokens or None,
                                  completion_tokens=completion_tokens or None,
                                  nudged=nudged)

        if rounds >= rounds_limit:
            logger.warning(
                "tool loop truncated at %d rounds (requested %d tool calls, dropped)",
                rounds_limit, len(tool_calls),
            )
            return ToolLoopResult(
                content=content, rounds=rounds, truncated=True, tool_names_called=called,
                prompt_tokens=prompt_tokens or None,
                completion_tokens=completion_tokens or None,
                nudged=nudged,
            )

        # 把 assistant 的 tool_calls 帧与每个工具结果回灌
        history.append({
            "role": "assistant",
            "content": content or "",
            "tool_calls": tool_calls,
        })
        outputs = await execute_tool_calls(specs, tool_calls, tool_context)
        for call, (name, output) in zip(tool_calls, outputs):
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


__all__ = ["ToolLoopResult", "execute_tool_calls", "run_tool_loop"]
