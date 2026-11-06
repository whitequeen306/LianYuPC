"""九槽位预算（`docs/context-memory-architecture-zh.md` §5）。

这张表是「每轮上下文该长什么样」的唯一事实来源：装配器只按 key 取预算，
不再各自拍脑袋。默认总量 3460 token 是由现状（3–4k）与缓存最小前缀
推出的起点，`recent` 按最近 20 条平均长度自适应，避免短句时浪费、
长句时挤掉记忆。

只做分配，不做裁剪：怎么砍由装配器按 `DROP_ORDER` 决定，
token 估算见 `app.context.tokens`。
"""

from __future__ import annotations

DEFAULT_TOTAL_TOKENS = 3460

# key, 中文名, 预算, 说明（说明只用于日志与文档，不参与计算）
SLOTS: tuple[tuple[str, str, int, str], ...] = (
    ("product", "产品与安全边界", 180, "固定不变，永远第一段"),
    ("persona", "人设与说话方式", 500, "角色卡 + 模板，逐轮稳定"),
    ("core", "core 记忆", 260, "设定与 pin，不衰减"),
    ("relation", "关系与情绪", 120, "阶段、信任、当前情绪"),
    ("scene", "场景卡", 220, "topic / beats / openLoops"),
    ("recall", "召回原话", 400, "4–6 条，带日期与置信标记"),
    ("archive", "档案导航", 300, "摘要式索引，只在需要时出现"),
    ("recent", "最近原文", 1400, "最近若干条，预算自适应 1000–1800"),
    ("director", "导演提示", 80, "群聊/主动发言等一次性指令"),
)

SLOT_KEYS: tuple[str, ...] = tuple(slot[0] for slot in SLOTS)

# 预算不够时的丢弃顺序：先丢导航，再丢低置信召回，最后才动人设与最近原文。
DROP_ORDER: tuple[str, ...] = (
    "director",
    "archive",
    "recall",
    "scene",
    "core",
    "relation",
    "recent",
    "persona",
    "product",
)

RECENT_MIN = 1000
RECENT_MAX = 1800
RECENT_DEFAULT = 1400


def recent_budget_for_chars(avg_chars: float | None) -> int:
    """按最近 20 条的平均长度决定 recent 预算。

    >120 字/条：同样的信息量需要更多 token，但最近原文最贵，压到 1200；
    <40 字/条：短句省 token，放宽到 1600 让原话更完整；
    其余取 1400。始终夹在 1000–1800。
    """
    if avg_chars is None:
        return RECENT_DEFAULT
    if avg_chars > 120:
        value = 1200
    elif avg_chars < 40:
        value = 1600
    else:
        value = RECENT_DEFAULT
    return max(RECENT_MIN, min(RECENT_MAX, value))


def plan_budget(total_tokens: int = DEFAULT_TOTAL_TOKENS,
                recent_avg_chars: float | None = None) -> dict[str, int]:
    """返回每个槽位的 token 预算（含 recent 自适应）。

    固定槽位求和后，recent 取「自适应该给的值」与「总量剩下的额度」中较小者；
    余量低于 RECENT_MIN 时保底 1000（宁可总量轻微超出，也不把最近原文压到读不懂）。
    """
    fixed = sum(budget for key, _, budget, _ in SLOTS if key != "recent")
    remaining = total_tokens - fixed
    adaptive = recent_budget_for_chars(recent_avg_chars)
    recent = max(RECENT_MIN, min(adaptive, max(remaining, RECENT_MIN)))

    planned = {key: budget for key, _, budget, _ in SLOTS if key != "recent"}
    planned["recent"] = recent
    return planned


def total_of(planned: dict[str, int]) -> int:
    return sum(planned.values())
