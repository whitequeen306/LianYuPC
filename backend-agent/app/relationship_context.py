"""关系块 —— 阶段行为契约 + 陪伴事实 + 事件摘录。

旧实现只输出四行英文形容词（warmly trusting / growing closer …）：
模型无法把形容词转成行为差异，且完全不支持多语言，等于关系没有驱动说话方式。

现在与 `emotion_block` 同构：按「阶段 × 语言」查行为指南表（`relationship_behavior.json`），
分数不直接进 prompt（避免模型复述「亲密度 48」），只用来触发「想念」这类具体行为。
阶段文案的维护归 Python（AGENTS.md §2），Java 只回 phase 枚举与分数。
"""

from __future__ import annotations

import json
from functools import lru_cache
from pathlib import Path

BEHAVIOR_PATH = Path(__file__).resolve().parent / "prompts" / "relationship_behavior.json"

KNOWN_PHASES = ("TESTING", "FAMILIAR", "DEPENDENT", "INJURED", "REPAIRING", "STABLE_INTIMATE")
DEFAULT_PHASE = "TESTING"

ASSISTANT_EVENT_TYPES = frozenset({"ASSISTANT_VULNERABLE_REPLY"})
MAX_EVENTS = 5
LONGING_ANTICIPATION = 55
LONGING_AWAY_HOURS = 24


@lru_cache(maxsize=1)
def _table() -> dict:
    return json.loads(BEHAVIOR_PATH.read_text(encoding="utf-8"))


def _lang_key(lang: str | None) -> str:
    code = (lang or "zh").strip().upper().replace("-", "_")
    if code in ("ZH_TW", "ZHTW", "ZH_HANT", "ZH_HK"):
        return "ZH_TW"
    if code.startswith("JA"):
        return "JA"
    if code.startswith("EN"):
        return "EN"
    return "ZH"


def _entry(key: str, lang: str | None) -> str:
    return _table()[key][_lang_key(lang)]


def normalize_phase(phase: str | None) -> str:
    """非法值回退默认阶段：脏数据不应产出一份无法解释的 prompt。"""
    normalized = (phase or "").strip().upper()
    return normalized if normalized in KNOWN_PHASES else DEFAULT_PHASE


def format_phase(phase: str | None) -> str:
    return normalize_phase(phase).lower().replace("_", "-")


def _bullet(text: str) -> str:
    stripped = (text or "").strip()
    return stripped if stripped.startswith("-") else "- " + stripped


def _quote(text: str, lang_key: str) -> str:
    return f'"{text}"' if lang_key == "EN" else f"「{text}」"


def build_relationship_context(
    *,
    phase: str | None,
    trust: int = 0,
    intimacy: int = 0,
    security: int = 0,
    anticipation: int = 0,
    recent_events: list | None = None,
    days_known: int = 1,
    streak_days: int = 0,
    away_hours: int = 0,
    lang: str | None = "zh",
) -> str:
    lang_key = _lang_key(lang)
    table = _table()
    normalized = normalize_phase(phase)
    localized = table["phases"][normalized][lang_key]

    stage = _entry("stageLine", lang).format(
        phase=localized["label"], days=max(1, int(days_known or 1)))
    if int(streak_days or 0) >= 2:
        stage += _entry("streakPart", lang).format(days=int(streak_days))
    away_days = int(away_hours or 0) // 24
    if away_days >= 1:
        stage += _entry("awayPart", lang).format(days=away_days)

    guides = [_bullet(line) for line in localized["guide"]]
    if int(anticipation or 0) >= LONGING_ANTICIPATION and int(away_hours or 0) >= LONGING_AWAY_HOURS:
        guides.append(_bullet(_entry("longingGuide", lang)))

    lines = [
        _entry("headers", lang),
        stage,
        "",
        _entry("guideHeader", lang),
        *guides,
        "",
        _entry("eventsHeader", lang),
    ]

    event_lines: list[str] = []
    for event in (recent_events or [])[:MAX_EVENTS]:
        if isinstance(event, dict):
            summary = str(event.get("summary") or "").strip()
            own_line = str(event.get("type") or "") in ASSISTANT_EVENT_TYPES
        else:
            summary = str(event or "").strip()
            own_line = False
        if not summary:
            continue
        prefix = _entry("assistantEventPrefix" if own_line else "userEventPrefix", lang)
        event_lines.append(f"- {prefix}{_quote(summary, lang_key)}")

    lines.extend(event_lines or [_bullet(_entry("emptyEventsLine", lang))])
    return "\n".join(lines)


__all__ = [
    "DEFAULT_PHASE",
    "KNOWN_PHASES",
    "BEHAVIOR_PATH",
    "LONGING_ANTICIPATION",
    "LONGING_AWAY_HOURS",
    "build_relationship_context",
    "format_phase",
    "normalize_phase",
]
