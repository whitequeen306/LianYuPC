"""记忆写入（Python 侧）—— 对齐 Java `MemoryWriter` 的去重与合并语义。

写入顺序（同一事务）：
    1. 按 source_hash 查重（走只读链路）
    2. 新增 → INSERT memory_meta + INSERT memory_outbox(UPSERT)
       已存在且摘要相同 → 只合并来源/重要度（不产生新向量）
       已存在且摘要不同 → 覆盖摘要，清空 vec_id，入队 DELETE(旧向量) + UPSERT
    3. 提交后由 Java 或 Python 的 relay 投影到 Milvus

为什么保留 Java 的 source_hash 规则：hash 是去重的唯一依据，跨端必须一致，
否则同一段对话在两个语言里会被写成两条记忆。
"""

from __future__ import annotations

import hashlib
import json
import logging
import re
from dataclasses import dataclass
from decimal import ROUND_HALF_UP, Decimal
from typing import Any, Iterable

from app.db import fetch_all
from app.write import execute_write, insert_returning_id, transaction

logger = logging.getLogger(__name__)

# 必须与 Java `com.lianyu.dao.enums.MemoryType` **完全一致**（跨语言契约）：
#   FACT, EMOTION, RELATION, RITUAL
# 曾经这里多写了 PREFERENCE/EVENT，Python 写入后 Java 的 MyBatis 枚举映射失败，
# 表现为 relay 抛空指针（message 为 null），记忆永远投影不到 Milvus。
JAVA_MEMORY_TYPES = frozenset({"FACT", "EMOTION", "RELATION", "RITUAL"})
# 历史/外部输入里可能出现的近义类型 → 归一到 Java 认识的类型
_TYPE_ALIASES = {"PREFERENCE": "FACT", "EVENT": "FACT", "FACTUAL": "FACT"}
MEMORY_TYPES = JAVA_MEMORY_TYPES


@dataclass(frozen=True)
class MemoryInput:
    summary: str
    memory_type: str = "FACT"
    importance: float = 0.5
    source_msg_ids: tuple[int, ...] = ()


@dataclass
class WriteOutcome:
    created: int = 0
    updated: int = 0
    skipped: int = 0
    ids: list[int] = None  # type: ignore[assignment]

    def __post_init__(self) -> None:
        if self.ids is None:
            self.ids = []

    def as_dict(self) -> dict[str, Any]:
        return {"created": self.created, "updated": self.updated,
                "skipped": self.skipped, "ids": self.ids}


def compute_source_ids_hash(source_ids: Iterable[int], summary: str = "") -> str:
    """来源消息哈希。

    相对 Java 旧实现的修正：旧版只哈希 sorted(ids)，导致**同一条消息里的多个槽位**
    （如「我叫阿澈，我喜欢冰美式」同时抽出姓名与爱好）互相覆盖 —— 后写的会把先写的
    顶掉，用户只会看到其中一条。现在把 summary 也纳入哈希：
      - 同一条消息 + 不同内容 → 两条不同记忆（正常）
      - 同一条消息 + 相同内容 → 仍然去重（幂等）
    Java 侧 `MemoryWriter.computeSourceIdsHash` 已同步改成同样的规则。
    """
    joined = ",".join(str(x) for x in sorted(source_ids))
    payload = f"{joined}|{summary.strip()}" if summary else joined
    return hashlib.sha256(payload.encode("utf-8")).hexdigest()


def compute_legacy_hash(user_id: int, character_id: int, summary: str) -> str:
    """对齐 Java `computeLegacyMemoryHash`（无来源消息时的兜底）。"""
    return hashlib.sha256(f"u:{user_id}|c:{character_id}|text:{summary}".encode("utf-8")).hexdigest()


def resolve_source_hash(user_id: int, character_id: int, source_ids: tuple[int, ...], summary: str) -> str:
    return compute_source_ids_hash(source_ids, summary) if source_ids else compute_legacy_hash(
        user_id, character_id, summary)


def to_importance(value: float) -> Decimal:
    """对齐 Java `toImportance`：clamp 0..1，保留两位，HALF_UP。"""
    clamped = max(0.0, min(1.0, float(value or 0.0)))
    return Decimal(str(clamped)).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)


def _merge_ids(old: Any, new: tuple[int, ...]) -> list[int]:
    merged: list[int] = []
    if isinstance(old, str):
        try:
            old = json.loads(old)
        except json.JSONDecodeError:
            old = []
    for value in (old or []):
        if value is not None and int(value) not in merged:
            merged.append(int(value))
    for value in new:
        if int(value) not in merged:
            merged.append(int(value))
    return merged


async def _find_existing(source_hash: str, user_id: int, character_id: int, summary: str) -> dict[str, Any] | None:
    rows = await fetch_all(
        "SELECT id, summary, importance, source_msg_ids, source_hash, milvus_vec_id "
        "FROM memory_meta WHERE source_hash = %s LIMIT 1", (source_hash,))
    if rows:
        return rows[0]
    legacy = compute_legacy_hash(user_id, character_id, summary)
    rows = await fetch_all(
        "SELECT id, summary, importance, source_msg_ids, source_hash, milvus_vec_id "
        "FROM memory_meta WHERE source_hash = %s LIMIT 1", (legacy,))
    return rows[0] if rows else None


async def write_memories(user_id: int, character_id: int, memories: list[MemoryInput],
                         *, review=None) -> WriteOutcome:
    """批量写入；整批在同一事务内，任一失败全回滚。

    `review(messages) -> str` 只在「灰区 + 开关打开」时被调用一次，用来判断
    两条记忆是不是同一件事；不传就保持「灰区当 ADD」。
    """
    outcome = WriteOutcome()
    if not memories:
        return outcome

    for memory in memories:
        summary = (memory.summary or "").strip()
        if not summary:
            outcome.skipped += 1
            continue
        if len(summary) > 120:
            summary = summary[:120]
        memory_type = (memory.memory_type or "FACT").strip().upper()
        if memory_type not in MEMORY_TYPES:
            memory_type = _TYPE_ALIASES.get(memory_type, "FACT")
        importance = to_importance(memory.importance)
        source_ids = tuple(int(x) for x in (memory.source_msg_ids or ()))
        source_hash = resolve_source_hash(user_id, character_id, source_ids, summary)

        existing = await _find_existing(source_hash, user_id, character_id, summary)

        if existing is None:
            decision, similar_id = await _semantic_dedupe(character_id, user_id, summary,
                                                          review=review)
            if decision == "noop" and similar_id:
                # 同一件事已经记过：不再插入，把 id 报给调用方即可
                outcome.skipped += 1
                outcome.ids.append(similar_id)
                continue
            memory_id = await _insert_new(user_id, character_id, summary, memory_type,
                                          importance, source_ids, source_hash)
            outcome.created += 1
            outcome.ids.append(memory_id)
            continue

        memory_id = int(existing["id"])
        if summary == (existing.get("summary") or ""):
            # 同一件事：只合并来源与重要度，不重建向量
            merged = _merge_ids(existing.get("source_msg_ids"), source_ids)
            await _update_meta(memory_id, summary, memory_type,
                               max(importance, Decimal(str(existing.get("importance") or "0"))),
                               merged, source_hash)
            outcome.skipped += 1
            continue

        merged = _merge_ids(existing.get("source_msg_ids"), source_ids)
        old_vec = existing.get("milvus_vec_id")
        await _replace_and_enqueue(memory_id, summary, memory_type, importance,
                                   merged, source_hash, old_vec)
        outcome.updated += 1
        outcome.ids.append(memory_id)

    logger.info("memory write done: user=%s character=%s created=%d updated=%d skipped=%d",
                user_id, character_id, outcome.created, outcome.updated, outcome.skipped)
    return outcome


async def _semantic_dedupe(character_id: int, user_id: int, summary: str,
                           review=None) -> tuple[str, int | None]:
    """语义去重三态；开关关着时一律 ADD（保持旧行为）。"""
    from app.config import get_settings

    settings = get_settings()
    if not settings.memory_dedupe_similarity_enabled:
        return "add", None
    from app.memory import dedupe

    best, memory_id = await dedupe.best_similar(
        character_id=character_id, user_id=user_id, summary=summary
    )
    decision = dedupe.decide(best)
    if decision != "review" or memory_id is None or review is None:
        return decision, memory_id
    if not getattr(settings, "memory_dedupe_llm_review_enabled", False):
        return decision, memory_id

    rows = await fetch_all("SELECT summary FROM memory_meta WHERE id = %s LIMIT 1", (memory_id,))
    existing_summary = str((rows[0].get("summary") if rows else "") or "").strip()
    if not existing_summary:
        return decision, memory_id
    verdict = await dedupe.review_same(existing_summary, summary, review)
    if verdict is True:
        return "noop", memory_id
    if verdict is False:
        return "add", None
    return decision, memory_id


_SLOT_RE = re.compile(r"^【长期记忆/([^】]{1,8})】")


def slot_of(summary: str) -> str | None:
    """画像槽位（姓名/爱好/忌口/偏好/禁忌/事件/习惯）；非槽位返回 None。"""
    match = _SLOT_RE.match((summary or "").strip())
    return match.group(1) if match else None


async def _supersede_slot(cur, *, memory_id: int, user_id: int, character_id: int,
                          slot: str) -> None:
    """同一槽位只留一条 active：旧行标 superseded 并关掉有效期，不删。

    换名字、改口味这类"新事实推翻旧事实"必须能追溯；物理删会让"她以前叫什么"
    彻底消失，也会让旧向量的清理与行删除脱节。
    """
    await execute_write(
        cur,
        "UPDATE memory_meta SET status = 'superseded', valid_to = NOW(), superseded_by = %s "
        "WHERE user_id = %s AND character_id = %s AND id <> %s AND status = 'active' "
        "AND summary LIKE %s",
        (memory_id, user_id, character_id, memory_id, f"【长期记忆/{slot}】%"),
    )


async def _insert_new(user_id: int, character_id: int, summary: str, memory_type: str,
                      importance: Decimal, source_ids: tuple[int, ...], source_hash: str) -> int:
    async with transaction() as cur:
        memory_id = await insert_returning_id(
            cur,
            "INSERT INTO memory_meta (character_id, user_id, summary, memory_type, importance, "
            "source_msg_ids, source_hash, milvus_vec_id, event_time, last_decay_at, created_at) "
            "VALUES (%s, %s, %s, %s, %s, %s, %s, NULL, NOW(), NOW(), NOW())",
            (character_id, user_id, summary, memory_type, str(importance),
             json.dumps(list(source_ids), ensure_ascii=False), source_hash),
        )
        await insert_returning_id(
            cur,
            "INSERT INTO memory_outbox (memory_id, op, status, attempts, next_attempt_at) "
            "VALUES (%s, %s, %s, %s, NOW())",
            (memory_id, "UPSERT", "PENDING", 0),
        )
        slot = slot_of(summary)
        if slot:
            await _supersede_slot(cur, memory_id=memory_id, user_id=user_id,
                                  character_id=character_id, slot=slot)
    return memory_id


async def _update_meta(memory_id: int, summary: str, memory_type: str,
                       importance: Decimal, merged: list[int], source_hash: str) -> None:
    """摘要未变：只合并来源与重要度，**不触碰 vec_id、不入队**（向量仍有效）。"""
    async with transaction() as cur:
        await execute_write(
            cur,
            "UPDATE memory_meta SET memory_type = %s, importance = %s, "
            "source_msg_ids = %s, source_hash = %s WHERE id = %s",
            (memory_type, str(importance), json.dumps(merged, ensure_ascii=False),
             source_hash, memory_id),
        )


async def _replace_and_enqueue(memory_id: int, summary: str, memory_type: str,
                               importance: Decimal, merged: list[int], source_hash: str,
                               old_vec: str | None) -> None:
    """摘要变化：覆盖内容、清空 vec_id，并入队「删旧向量 + 重投影」，同一事务。"""
    async with transaction() as cur:
        await execute_write(
            cur,
            "UPDATE memory_meta SET summary = %s, memory_type = %s, importance = %s, "
            "source_msg_ids = %s, source_hash = %s, milvus_vec_id = NULL WHERE id = %s",
            (summary, memory_type, str(importance),
             json.dumps(merged, ensure_ascii=False), source_hash, memory_id),
        )
        if old_vec:
            await cur.execute(
                "INSERT INTO memory_outbox (memory_id, op, vec_id, status, attempts, next_attempt_at) "
                "VALUES (%s, %s, %s, %s, %s, NOW())",
                (memory_id, "DELETE", old_vec, "PENDING", 0),
            )
        await cur.execute(
            "INSERT INTO memory_outbox (memory_id, op, status, attempts, next_attempt_at) "
            "VALUES (%s, %s, %s, %s, NOW())",
            (memory_id, "UPSERT", "PENDING", 0),
        )


__all__ = [
    "JAVA_MEMORY_TYPES",
    "MEMORY_TYPES",
    "MemoryInput",
    "WriteOutcome",
    "compute_legacy_hash",
    "compute_source_ids_hash",
    "resolve_source_hash",
    "to_importance",
    "write_memories",
]
