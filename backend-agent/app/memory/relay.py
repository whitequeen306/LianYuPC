"""outbox relay（Python 侧单写者）。

与 Java `MemoryOutboxRelay` 行为一致，但由 Python 独占执行，达成记忆域单写者：
- 认领：`PENDING → RUNNING` 且校验影响行数（多实例不重复处理）
- UPSERT：读 memory_meta → 算向量 → Milvus insert → 回填 vec_id → DONE
- DELETE：按 vec_id 删除 → DONE
- 失败：attempts+1，指数退避（2,4,8… 上限 1h），超上限置 DEAD 并留 last_error
- 记忆已删除/为空：视为完成（不无限重试）

边界：Milvus 与 embedding 都不可用时**不消耗重试次数**（避免上游抖动把任务打成 DEAD），
而是延后 30s 再试 —— 这与「业务失败」区分开，否则一次 Milvus 重启会让当天所有记忆进入死信。
"""

from __future__ import annotations

import logging
from typing import Any

from app import milvus_store
from app.config import get_settings
from app.db import fetch_all
from app.memory.embedding import embed
from app.write import execute_write, transaction

logger = logging.getLogger(__name__)

OP_UPSERT = "UPSERT"
OP_DELETE = "DELETE"
STATUS_PENDING = "PENDING"
STATUS_RUNNING = "RUNNING"
STATUS_DONE = "DONE"
STATUS_DEAD = "DEAD"

_PENDING_TTL_SECONDS = 30.0


def _clip(text: str, size: int = 512) -> str:
    return text if len(text) <= size else text[:size]


async def _claim(outbox_id: int) -> bool:
    async with transaction() as cur:
        affected = await execute_write(
            cur,
            "UPDATE memory_outbox SET status = %s WHERE id = %s AND status = %s",
            (STATUS_RUNNING, outbox_id, STATUS_PENDING),
        )
    return affected > 0


async def _mark(outbox_id: int, status: str, *, attempts: int | None = None,
                last_error: str | None = None, delay_seconds: float = 0.0) -> None:
    async with transaction() as cur:
        if attempts is None:
            await execute_write(
                cur, "UPDATE memory_outbox SET status = %s WHERE id = %s",
                (status, outbox_id))
        else:
            # next_attempt_at 由 SQL 计算，避免应用与数据库时区不一致
            await execute_write(
                cur,
                "UPDATE memory_outbox SET attempts = %s, status = %s, last_error = %s "
                "WHERE id = %s",
                (attempts, status, (last_error or "")[:480], outbox_id))
            await execute_write(
                cur,
                "UPDATE memory_outbox SET next_attempt_at = DATE_ADD(NOW(), INTERVAL %s SECOND) "
                "WHERE id = %s",
                (int(delay_seconds), outbox_id))


async def _set_vec_id(memory_id: int, vec_id: str) -> None:
    async with transaction() as cur:
        await execute_write(
            cur, "UPDATE memory_meta SET milvus_vec_id = %s WHERE id = %s",
            (vec_id, memory_id))


async def _load_meta(memory_id: int) -> dict[str, Any] | None:
    rows = await fetch_all(
        "SELECT id, character_id, user_id, summary, memory_type, milvus_vec_id "
        "FROM memory_meta WHERE id = %s", (memory_id,))
    return rows[0] if rows else None


async def project_upsert(memory_id: int) -> str:
    """投影一条记忆；返回 'done' / 'skip' / 'retry' / 'defer'。"""
    meta = await _load_meta(memory_id)
    if meta is None or not (meta.get("summary") or "").strip():
        return "skip"

    summary = _clip(str(meta["summary"]).strip())
    old_vec = meta.get("milvus_vec_id")

    vectors = await embed([summary])
    if not vectors:
        return "defer"  # embedding 不可用：不算业务失败

    if old_vec:
        milvus_store.delete([str(old_vec)])
    vec_id = milvus_store.insert(
        int(meta["character_id"]), int(meta["user_id"]), int(meta["id"]),
        summary, str(meta.get("memory_type") or "FACT"), vectors[0],
    )
    if vec_id is None:
        return "retry"
    await _set_vec_id(int(meta["id"]), vec_id)
    return "done"


async def drain_once(batch_size: int | None = None, max_attempts: int | None = None) -> dict[str, int]:
    """处理一批到期任务，返回计数。"""
    settings = get_settings()
    limit = batch_size or settings.relay_batch_size
    attempts_cap = max_attempts or settings.relay_max_attempts

    rows = await fetch_all(
        "SELECT id, memory_id, op, vec_id, attempts FROM memory_outbox "
        "WHERE status = %s AND next_attempt_at <= NOW() ORDER BY id ASC LIMIT %s",
        (STATUS_PENDING, limit),
    )

    stats = {"picked": len(rows), "done": 0, "skipped": 0, "retried": 0, "deferred": 0, "dead": 0}
    for row in rows:
        outbox_id = int(row["id"])
        if not await _claim(outbox_id):
            continue

        op = str(row.get("op") or OP_UPSERT)
        try:
            if op == OP_DELETE:
                vec = row.get("vec_id")
                ok = milvus_store.delete([str(vec)]) if vec else True
                if ok:
                    await _mark(outbox_id, STATUS_DONE)
                    stats["done"] += 1
                else:
                    await _retry_or_dead(outbox_id, row, attempts_cap,
                                         "milvus delete failed", stats)
                continue

            outcome = await project_upsert(int(row["memory_id"]))
            if outcome == "done":
                await _mark(outbox_id, STATUS_DONE)
                stats["done"] += 1
            elif outcome == "skip":
                await _mark(outbox_id, STATUS_DONE)
                stats["skipped"] += 1
            elif outcome == "defer":
                await _mark(outbox_id, STATUS_PENDING, attempts=row.get("attempts") or 0,
                            last_error="upstream unavailable", delay_seconds=_PENDING_TTL_SECONDS)
                stats["deferred"] += 1
            else:
                await _retry_or_dead(outbox_id, row, attempts_cap, "milvus insert returned null", stats)
        except Exception as exc:  # noqa: BLE001 - 单行失败不影响整批
            await _retry_or_dead(outbox_id, row, attempts_cap, str(exc), stats)

    return stats


async def _retry_or_dead(outbox_id: int, row: dict[str, Any], cap: int,
                         reason: str, stats: dict[str, int]) -> None:
    attempts = int(row.get("attempts") or 0) + 1
    if attempts >= cap:
        await _mark(outbox_id, STATUS_DEAD, attempts=attempts, last_error=reason)
        stats["dead"] += 1
        logger.error("memory outbox DEAD: id=%s memoryId=%s attempts=%s reason=%s",
                     outbox_id, row.get("memory_id"), attempts, reason)
        return
    delay = min(3600.0, float(2 ** min(attempts, 12)))
    await _mark(outbox_id, STATUS_PENDING, attempts=attempts, last_error=reason,
                delay_seconds=delay)
    stats["retried"] += 1
    logger.warning("memory outbox retry in %ss: id=%s memoryId=%s attempts=%s reason=%s",
                   int(delay), outbox_id, row.get("memory_id"), attempts, reason)


async def reconcile(limit: int = 500) -> dict[str, int]:
    """全量对账：修复 vec_id 缺失/失效的记忆（等价 Java MemoryMilvusSyncService.resyncAll）。

    与增量 relay 的区别：relay 只处理「有 outbox 行」的变更，
    对账负责历史遗留（早期双写失败留下的空 vec_id）与手工删库后的恢复。
    """
    rows = await fetch_all(
        "SELECT id, summary, milvus_vec_id FROM memory_meta "
        "WHERE summary IS NOT NULL AND summary <> '' "
        "AND (milvus_vec_id IS NULL OR milvus_vec_id = '') "
        "ORDER BY id ASC LIMIT %s",
        (limit,),
    )
    stats = {"candidates": len(rows), "repaired": 0, "failed": 0}
    for row in rows:
        try:
            outcome = await project_upsert(int(row["id"]))
        except Exception as exc:  # noqa: BLE001 - 单条失败不中断对账
            logger.warning("reconcile failed: memoryId=%s reason=%s", row["id"], exc)
            outcome = "retry"
        if outcome in ("done", "skip"):
            stats["repaired"] += 1
        else:
            stats["failed"] += 1
    if stats["candidates"]:
        logger.info("memory reconcile: %s", stats)
    return stats


async def requeue_stuck() -> int:
    """把 DEAD / 卡在 RUNNING 超过 10 分钟的行重新排队（运维用）。"""
    rows = await fetch_all(
        "SELECT id FROM memory_outbox WHERE status IN (%s, %s) "
        "AND updated_at <= DATE_SUB(NOW(), INTERVAL 10 MINUTE)",
        (STATUS_DEAD, STATUS_RUNNING),
    )
    count = 0
    for row in rows:
        async with transaction() as cur:
            await execute_write(
                cur, "UPDATE memory_outbox SET status = %s WHERE id = %s",
                (STATUS_PENDING, int(row["id"])))
            await execute_write(
                cur, "UPDATE memory_outbox SET next_attempt_at = NOW() WHERE id = %s",
                (int(row["id"]),))
        count += 1
    return count


__all__ = ["drain_once", "project_upsert", "requeue_stuck"]
