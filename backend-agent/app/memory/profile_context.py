"""记忆块（预注入 system prompt 的结构化画像）—— 对齐 `MemoryRetriever.retrieveProfileContext`。

格式（四段，段内 `- 摘要`，段间空行，全空返回 None）：
    [用户画像]        FACT
    [近期情绪线索]    EMOTION
    [关系事件]        RELATION
    [专属仪式]        RITUAL

本模块只负责**格式**：传入 (summary, memory_type) 列表，产出字符串。
取数（Milvus / Redis 缓存 / 降级合并）由调用方负责 —— 这样格式正确与数据正确
可以分开验证。
"""

from __future__ import annotations

from typing import Iterable, Sequence

SECTION_LABELS = (
    ("FACT", "用户画像"),
    ("EMOTION", "近期情绪线索"),
    ("RELATION", "关系事件"),
    ("RITUAL", "专属仪式"),
)


def _type_of(item) -> str:
    """兼容 (summary, type) 元组与 dict。"""
    if isinstance(item, dict):
        return str(item.get("memory_type") or "").upper()
    return str(item[1] or "").upper()


def _summary_of(item) -> str:
    if isinstance(item, dict):
        return item.get("summary") or ""
    return item[0] or ""


def build_profile_context(items: Sequence[Iterable]) -> str | None:
    """返回四段拼接结果；没有任何有效摘要时返回 None。"""
    sections: list[str] = []
    for memory_type, label in SECTION_LABELS:
        lines: list[str] = []
        seen: set[str] = set()
        for item in items or []:
            if _type_of(item) != memory_type:
                continue
            summary = (_summary_of(item) or "").strip()
            if not summary or summary in seen:
                continue
            seen.add(summary)
            lines.append("- " + summary)
        if lines:
            sections.append("[" + label + "]\n" + "\n".join(lines))
    if not sections:
        return None
    return "\n\n".join(sections)


__all__ = ["SECTION_LABELS", "build_profile_context"]