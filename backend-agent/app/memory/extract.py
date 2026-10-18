"""记忆抽取（Python 侧）—— 对齐 Java `MemoryExtractionService` + `MemoryLlmExtractor`。

两条通道：
1. **规则**（`extract_heuristics`）：高精度、零成本。姓名/爱好/忌口/偏好/禁忌/仪式。
   移植 Java `MemoryExtractionHeuristics` 的槽位与归一规则（保留主力模式）。
2. **LLM**（`extract_with_llm`）：产出结构化 JSON，prompt 与 Java 逐字一致，
   temperature 0.1（抽取要稳定，不要创造力）。

合并规则对齐 Java：
- 规则结果在前，LLM 结果去重后追加（按 summary 去重）
- `importance < 阈值(0.5)` 丢弃
- 每轮最多 3 条（按 importance 降序取）

为什么迁移到 Python：抽取是 LLM 任务，属 agent 层；留在 Java 会让 agent 层永远迁不干净，
而写入已经在 Python —— 拆开意味着 Java 要读 Python 写的表再写一遍。
"""

from __future__ import annotations

import json
import logging
import re
from dataclasses import dataclass
from typing import Any, Iterable

from app.config import get_settings
from app.gateway import ModelGateway

logger = logging.getLogger(__name__)

# 与 Java MemoryLlmExtractor 的 system prompt 逐字一致
EXTRACT_SYSTEM_PROMPT = """你是长期记忆提取器。从用户消息中识别值得长期记住的信息。
规则：
1) 只提取用户明确表达的个人信息、偏好、情绪、事件、习惯、关系；
2) 不要记录寒暄、玩笑、提问句、纯表情；
3) 结构化事实用 summary 格式：【长期记忆/槽位】内容，槽位可为姓名/爱好/忌口/偏好/禁忌/事件/习惯；
4) 关系/情绪/仪式类可用简短摘要句；
5) importance 范围 0~1，越重要越高；
6) 仅输出 JSON，不要 markdown：
{"memories":[{"summary":"...","memoryType":"FACT|EMOTION|RELATION|RITUAL","importance":0.75,"sourceMsgId":123}]}
若无值得记住的内容，返回 {"memories":[]}。"""

MAX_CONTEXT_MESSAGES = 30
MAX_CONTEXT_CHARS = 160
MAX_SUMMARY_CHARS = 120

# 槽位 → 前缀（对齐 Java profilePrefix）
_SLOT_PREFIX = {
    "姓名": "【长期记忆/姓名】",
    "爱好": "【长期记忆/爱好】",
    "忌口": "【长期记忆/忌口】",
    "偏好": "【长期记忆/偏好】",
    "禁忌": "【长期记忆/禁忌】",
}

_NAME_VALUE = r"([\w\u4e00-\u9fff·]{1,20})"
_PROFILE_PATTERNS: list[tuple[str, re.Pattern[str]]] = [
    ("姓名", re.compile(r"我叫\s*" + _NAME_VALUE)),
    ("姓名", re.compile(r"请叫我\s*" + _NAME_VALUE)),
    ("姓名", re.compile(r"以后(?:你|大家)?(?:可以)?叫我\s*" + _NAME_VALUE)),
    ("姓名", re.compile(r"名字(?:是|叫)\s*" + _NAME_VALUE)),
    ("姓名", re.compile(r"(?i)(?:my name is|call me)\s+" + _NAME_VALUE)),
    ("爱好", re.compile(r"我喜欢[^，。！？；\n]{1,30}")),
    ("偏好", re.compile(r"我更喜欢[^，。！？；\n]{1,30}")),
    ("忌口", re.compile(r"我不喜欢[^，。！？；\n]{1,30}")),
    ("忌口", re.compile(r"我对([^，。！？；\n]{1,30})过敏")),
    ("禁忌", re.compile(r"不要让我[^，。！？；\n]{1,30}")),
]

_RITUAL_PATTERN = re.compile(r"以后(?:你|大家)?(?:可以)?叫我\s*" + _NAME_VALUE)


@dataclass(frozen=True)
class ExtractedMemory:
    summary: str
    memory_type: str = "FACT"
    importance: float = 0.5
    source_msg_id: int | None = None


def _is_user(message: dict[str, Any]) -> bool:
    return str(message.get("role") or "").upper() == "USER"


def build_context(messages: Iterable[dict[str, Any]], max_messages: int = MAX_CONTEXT_MESSAGES) -> str:
    """对齐 Java `buildContext`：只取 USER 消息，截 160 字，带 msgId。"""
    items = list(messages)[-max(1, max_messages):]
    lines: list[str] = []
    for message in items:
        if not _is_user(message):
            continue
        content = (message.get("content") or "").strip()
        if not content:
            continue
        if len(content) > MAX_CONTEXT_CHARS:
            content = content[:MAX_CONTEXT_CHARS] + "..."
        lines.append(f"msgId={message.get('id')} 用户: {content}")
    return "\n".join(lines) if lines else "(空)"


def extract_heuristics(messages: Iterable[dict[str, Any]]) -> list[ExtractedMemory]:
    """规则抽取：每个槽位只保留最后一次出现（对齐 Java 的 latestBySlot）。"""
    latest: dict[str, tuple[str, int | None]] = {}
    for message in messages:
        if not _is_user(message):
            continue
        text = (message.get("content") or "").strip()
        if not text:
            continue
        for slot, pattern in _PROFILE_PATTERNS:
            match = pattern.search(text)
            if match:
                value = match.group(0)
                if slot in ("姓名",) and match.groups():
                    value = match.group(1)
                latest[slot] = (value.strip(), message.get("id"))

    memories = [
        ExtractedMemory(
            summary=(_SLOT_PREFIX.get(slot, "【长期记忆/事件】") + value)[:MAX_SUMMARY_CHARS],
            memory_type="FACT",
            importance=0.8,
            source_msg_id=msg_id,
        )
        for slot, (value, msg_id) in latest.items()
    ]

    # 仪式：以后叫我X / 只给你叫X
    for message in messages:
        if not _is_user(message):
            continue
        text = (message.get("content") or "").strip()
        if not text:
            continue
        if "只给你叫" in text or "专属" in text or _RITUAL_PATTERN.search(text):
            memories.append(ExtractedMemory(
                summary=f"【长期记忆/仪式】{text[:MAX_SUMMARY_CHARS]}",
                memory_type="RITUAL",
                importance=0.7,
                source_msg_id=message.get("id"),
            ))
            break
    return memories


def _extract_json_object(text: str) -> str | None:
    start = text.find("{")
    end = text.rfind("}")
    if start >= 0 and end > start:
        return text[start:end + 1]
    return None


def parse_memories(raw: str) -> list[ExtractedMemory]:
    """解析 LLM 输出；任何异常都返回空列表（抽取失败不该炸整轮）。"""
    if not raw or not raw.strip():
        return []
    json_text = _extract_json_object(raw.strip())
    if not json_text:
        return []
    try:
        root = json.loads(json_text)
    except json.JSONDecodeError:
        logger.debug("memory llm json parse failed")
        return []

    result: list[ExtractedMemory] = []
    for node in (root.get("memories") or []):
        if not isinstance(node, dict):
            continue
        summary = str(node.get("summary") or "").strip()
        if not summary:
            continue
        memory_type = str(node.get("memoryType") or "FACT").upper()
        if memory_type not in {"FACT", "EMOTION", "RELATION", "RITUAL"}:
            memory_type = "FACT"
        try:
            importance = float(node.get("importance", 0.5))
        except (TypeError, ValueError):
            importance = 0.5
        source = node.get("sourceMsgId")
        try:
            source_id = int(source) if source is not None else None
        except (TypeError, ValueError):
            source_id = None
        result.append(ExtractedMemory(
            summary=summary[:MAX_SUMMARY_CHARS],
            memory_type=memory_type,
            importance=max(0.0, min(1.0, importance)),
            source_msg_id=source_id,
        ))
    return result


async def extract_with_llm(
    gateway: ModelGateway,
    *,
    base_url: str,
    api_key: str,
    model: str,
    messages: list[dict[str, Any]],
    provider: str | None = None,
) -> list[ExtractedMemory]:
    """LLM 抽取；失败返回空列表。"""
    context = build_context(messages)
    try:
        result = await gateway.chat_messages(
            base_url=base_url,
            api_key=api_key,
            model=model,
            messages=[
                {"role": "system", "content": EXTRACT_SYSTEM_PROMPT},
                {"role": "user", "content": "最近对话：\n" + context},
            ],
            temperature=0.1,
            max_tokens=800,
            provider=provider,
            lane_name="background",
        )
    except Exception as exc:  # noqa: BLE001 - 抽取失败降级为只用规则
        logger.warning("memory llm extraction failed: %s", exc)
        return []
    return parse_memories(result.content or "")


def merge_and_gate(
    heuristic: list[ExtractedMemory],
    llm: list[ExtractedMemory],
    *,
    importance_threshold: float,
    max_per_turn: int,
) -> list[ExtractedMemory]:
    """对齐 Java `applyGates`：规则在前、按 summary 去重、阈值过滤、上限截断。"""
    merged: list[ExtractedMemory] = []
    seen: set[str] = set()
    for memory in list(heuristic) + list(llm):
        key = memory.summary.strip()
        if not key or key in seen:
            continue
        seen.add(key)
        merged.append(memory)

    kept = [m for m in merged if m.importance >= importance_threshold]
    kept.sort(key=lambda m: m.importance, reverse=True)
    return kept[: max(1, max_per_turn)]


async def extract_all(
    gateway: ModelGateway,
    *,
    messages: list[dict[str, Any]],
    base_url: str | None = None,
    api_key: str | None = None,
    model: str | None = None,
    provider: str | None = None,
) -> list[ExtractedMemory]:
    """完整抽取：规则 + （有凭据时）LLM，然后过闸。"""
    settings = get_settings()
    heuristic = extract_heuristics(messages)
    llm: list[ExtractedMemory] = []
    if base_url and api_key and model:
        llm = await extract_with_llm(
            gateway, base_url=base_url, api_key=api_key, model=model,
            messages=messages, provider=provider,
        )
    return merge_and_gate(
        heuristic, llm,
        importance_threshold=settings.memory_importance_threshold,
        max_per_turn=3,
    )


__all__ = [
    "EXTRACT_SYSTEM_PROMPT",
    "ExtractedMemory",
    "build_context",
    "extract_all",
    "extract_heuristics",
    "extract_with_llm",
    "merge_and_gate",
    "parse_memories",
]
