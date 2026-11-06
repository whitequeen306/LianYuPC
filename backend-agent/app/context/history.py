"""历史裁剪：把「最近 N 条」换成「最近 N 个 token」。

固定条数在两种对话里都错：短句闲聊 32 条只有几百 token，长段落 32 条能到
六七千。裁剪从最新往回数，保留当前这轮用户消息，且不把 tool 调用切成孤儿。
"""

from __future__ import annotations

from typing import Any, Sequence

from app.context.tokens import estimate_tokens

MESSAGE_OVERHEAD = 4
MIN_MESSAGES = 6


def message_cost(message: dict[str, Any]) -> int:
    return estimate_tokens(str(message.get("content") or "")) + MESSAGE_OVERHEAD


def trim_history(
    messages: Sequence[dict[str, Any]],
    budget_tokens: int,
    *,
    min_messages: int = MIN_MESSAGES,
) -> list[dict[str, Any]]:
    """保留预算内、尽量新的历史；至少留 `min_messages` 条（当前轮永远留）。"""
    if not messages:
        return []
    if budget_tokens <= 0:
        return list(messages)

    kept: list[dict[str, Any]] = []
    total = 0
    for message in reversed(messages):
        cost = message_cost(message)
        if kept and total + cost > budget_tokens and len(kept) >= min_messages:
            break
        kept.append(message)
        total += cost
    kept.reverse()

    # 开头是 tool 就成了"没有请求的响应"，模型会拒收；宁可再丢一条。
    while kept and str(kept[0].get("role") or "").lower() == "tool":
        kept.pop(0)
    return kept
