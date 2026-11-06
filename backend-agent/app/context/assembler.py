"""按槽位预算裁剪 prompt 段落——先把"该丢谁"写成纯函数，再谈接线。

预算不是平均分配：每个槽有自己的额度（`budget.SLOTS`），超了先截断本槽，
所有槽都截完还超总量，才按 `DROP_ORDER` 整段丢。顺序即优先级，`product`
（产品设定）永远最后丢。
"""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any

from app.context.budget import DROP_ORDER, SLOTS, plan_budget, total_of
from app.context.tokens import estimate_tokens

ELLIPSIS = "…"


def truncate_to_tokens(text: str, budget: int, marker: str = ELLIPSIS) -> str:
    """按估算 token 截断到预算内；ASCII 与 CJK 混排时逐字符收敛，保证不超。"""
    if not text or budget <= 0:
        return ""
    if estimate_tokens(text) <= budget:
        return text

    # 二分找最长可容纳前缀，避免 O(n) 次估算
    low, high = 0, len(text)
    while low < high:
        mid = (low + high + 1) // 2
        if estimate_tokens(text[:mid] + marker) <= budget:
            low = mid
        else:
            high = mid - 1
    return text[:low].rstrip() + marker if low else ""


def fit_sections(sections: Mapping[str, str], total_tokens: int | None = None,
                 recent_avg_chars: int | None = None) -> tuple[dict[str, str], dict[str, Any]]:
    """返回 (裁剪后的段落, 报告)。报告给日志用：谁被截、谁被丢、前后 token。"""
    budget = total_tokens or 3460
    plan = plan_budget(total_tokens=budget, recent_avg_chars=recent_avg_chars)
    fitted: dict[str, str] = {}
    truncated: list[str] = []
    before = 0

    for name, text in sections.items():
        if not text:
            continue
        before += estimate_tokens(text)
        cap = plan.get(name) or plan["recall"]
        clipped = truncate_to_tokens(text, cap)
        if clipped != text:
            truncated.append(name)
        fitted[name] = clipped

    dropped: list[str] = []
    for name in DROP_ORDER:
        if total_of({k: estimate_tokens(v) for k, v in fitted.items()}) <= budget:
            break
        if name in fitted:
            fitted.pop(name)
            dropped.append(name)

    after = total_of({k: estimate_tokens(v) for k, v in fitted.items()})
    report = {
        "budget": budget,
        "tokens_before": before,
        "tokens_after": after,
        "truncated": truncated,
        "dropped": dropped,
        "slots": sorted(SLOTS),
    }
    return fitted, report
