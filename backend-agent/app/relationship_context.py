"""关系块 —— 对齐 `RelationshipContextAssembler` + `RelationshipPromptLabels`。

格式：
    关系阶段: <phase-lowercase>
    trust: <label>
    intimacy: <label>
    security: <label>
    anticipation: <label>
    近期关系事件:
    - <事件1>
    - <事件2>        （无事件时为 "- 无"）

分数标签是分档映射（>=75 / >=50 / >=25 / else），边界最容易错一位，
因此测试直接比对 Java 导出的 0..100 全部取值，而不是抽样几个点。
"""

from __future__ import annotations

BANDS = ((75, 0), (50, 1), (25, 2), (0, 3))

LABELS: dict[str, tuple[str, str, str, str]] = {
    "trust": ("highly trusting", "warmly trusting", "still cautious", "guarded"),
    "intimacy": ("very close", "growing closer", "holding some distance", "emotionally distant"),
    "security": ("emotionally secure", "mostly steady", "a little unsettled", "easily hurt"),
    "anticipation": ("eager to reconnect", "looking forward to contact",
                     "quietly waiting", "hesitant to reach out"),
}

EMPTY_EVENTS_LINE = "- 无"


def describe_dimension(dimension: str, score: int) -> str:
    """对齐 `RelationshipPromptLabels.describe`。"""
    band_index = 3
    for threshold, index in BANDS:
        if score >= threshold:
            band_index = index
            break
    labels = LABELS.get(dimension)
    if labels is None:
        return ("high", "medium", "low", "low")[band_index]
    return labels[band_index]


# 合法阶段枚举（对齐 Java RelationshipPhase）
KNOWN_PHASES = ("TESTING", "FAMILIAR", "DEPENDENT", "INJURED", "REPAIRING", "STABLE_INTIMATE")
DEFAULT_PHASE = "TESTING"


def format_phase(phase: str | None) -> str:
    """对齐 `snapshot.phase().name().toLowerCase().replace('_','-')`。

    非法值回退到 `DEFAULT_PHASE`（Java 侧枚举映射失败时会走默认快照）。
    不校验的话，脏数据会让 Python 产出 Java 永远不会生成的 prompt ——
    这类差异在 parity 比对里就是一次「无法解释的漂移」。
    """
    normalized = (phase or "").strip().upper()
    if normalized not in KNOWN_PHASES:
        normalized = DEFAULT_PHASE
    return normalized.lower().replace("_", "-")


def build_relationship_context(
    *,
    phase: str | None,
    trust: int,
    intimacy: int,
    security: int,
    anticipation: int,
    recent_events: list[str] | None = None,
) -> str:
    """对齐 `RelationshipContextAssembler.assemble`。"""
    events = [e for e in (recent_events or []) if e is not None]
    event_block = "\n".join("- " + e for e in events) if events else EMPTY_EVENTS_LINE
    return (
        "关系阶段: " + format_phase(phase) + "\n"
        + "trust: " + describe_dimension("trust", trust) + "\n"
        + "intimacy: " + describe_dimension("intimacy", intimacy) + "\n"
        + "security: " + describe_dimension("security", security) + "\n"
        + "anticipation: " + describe_dimension("anticipation", anticipation) + "\n"
        + "近期关系事件:\n" + event_block
    )


__all__ = [
    "DEFAULT_PHASE",
    "KNOWN_PHASES",
    "EMPTY_EVENTS_LINE",
    "LABELS",
    "build_relationship_context",
    "describe_dimension",
    "format_phase",
]