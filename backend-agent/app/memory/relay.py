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


async def _set_embedding_state(memory_id: int, state: str) -> None:
    """投影状态打点。v3 之后不再有 vec_id 可写，对账靠这一列判断。"""
    async with transaction() as cur:
        await execute_write(
            cur, "UPDATE memory_meta SET embedding_state = %s WHERE id = %s",
            (state, memory_id))


async def _load_meta(memory_id: int) -> dict[str, Any] | None:
    rows = await fetch_all(
        "SELECT id, character_id, user_id, summary, memory_type, layer, status, "
        "event_time, milvus_vec_id FROM memory_meta WHERE id = %s", (memory_id,))
    return rows[0] if rows else None


async def project_upsert(memory_id: int) -> str:
    """投影一条记忆；返回 'done' / 'skip' / 'retry' / 'defer'。

    v3（按 embedding 维度自动命名的集合）现在是唯一的召回源；v2 是 1024 维的老集合，
    换成本地 768 维 embedding 之后往里写只会被 Milvus 以维度不符拒绝，所以默认不写。
    """
    meta = await _load_meta(memory_id)
    if meta is None or not (meta.get("summary") or "").strip():
        return "skip"

    summary = _clip(str(meta["summary"]).strip())
    old_vec = meta.get("milvus_vec_id")

    vectors = await embed([summary])
    if not vectors:
        return "defer"  # embedding 不可用：不算业务失败

    v3_enabled = bool(getattr(get_settings(), "memory_vectors_v3_enabled", False))
    if v3_enabled:
        # v3 投影失败不能当成功：outbox 一旦标 DONE 就没人再来，这条记忆会永远失去向量召回。
        if not _project_v3(meta, summary, vectors[0]):
            return "retry"
        await _set_embedding_state(int(meta["id"]), "projected")

    if not bool(getattr(get_settings(), "memory_vectors_v2_enabled", False)):
        return "done"

    if old_vec:
        milvus_store.delete([str(old_vec)])
    vec_id = milvus_store.insert(
        int(meta["character_id"]), int(meta["user_id"]), int(meta["id"]),
        summary, str(meta.get("memory_type") or "FACT"), vectors[0],
    )
    if vec_id is None:
        # v3 已经写成功了，v2 只是历史遗留：记一笔 warning 就好，不要把整条任务拖成 DEAD。
        logger.warning("legacy v2 projection failed: memoryId=%s", meta["id"])
        return "done"
    await _set_vec_id(int(meta["id"]), vec_id)
    return "done"


def _project_v3(meta: dict[str, Any], summary: str, vector: list[float]) -> bool:
    """v3 双写；返回是否投影成功。关着开关时视为成功（本来就不该写）。"""
    if not getattr(get_settings(), "memory_vectors_v3_enabled", False):
        return True
    return milvus_store.insert_v3(
        int(meta["character_id"]),
        int(meta["user_id"]),
        int(meta["id"]),
        summary,
        str(meta.get("memory_type") or "FACT"),
        str(meta.get("layer") or "fact"),
        str(meta.get("status") or "active"),
        _event_time_ms(meta.get("event_time")),
        vector,
    ) is not None


def _event_time_ms(value: Any) -> int:
    if value is None:
        return 0
    if hasattr(value, "timestamp"):
        return int(value.timestamp() * 1000)
    return 0


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
                ok = True
                if getattr(get_settings(), "memory_vectors_v3_enabled", False):
                    # v3 的主键是自增 id，外部只认 memory_id，所以按 memory_id 删。
                    ok = milvus_store.delete_v3_by_memory_ids([int(row["memory_id"])])
                if vec and getattr(get_settings(), "memory_vectors_v2_enabled", False):
                    ok = ok and milvus_store.delete([str(vec)])
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

    if stats.get("done"):
        # 写批末尾刷一次，刚记住的事下一轮就能被想起来（见 milvus_store.flush_v3）。
        milvus_store.flush_v3()
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


_RECONCILE_SAMPLE = (
    "SELECT id, summary, milvus_vec_id FROM memory_meta "
    "WHERE summary IS NOT NULL AND summary <> '' "
    "AND status <> 'dormant' "
    "AND milvus_vec_id IS NOT NULL AND milvus_vec_id <> '' "
    "ORDER BY id ASC LIMIT %s"
)


async def _missing_in_v3(limit: int) -> list[dict[str, Any]]:
    """找出「vec_id 看起来有、v3 集合里其实没有」的行。

    向量库被重建或手工清空后，milvus_vec_id 这一列仍然在，只按它判断会漏掉整库；
    这里按批问 Milvus 本身，缺的才重投影。
    """
    if not getattr(get_settings(), "memory_vectors_v3_enabled", False):
        return []
    sample = await fetch_all(_RECONCILE_SAMPLE, (limit,))
    missing: list[dict[str, Any]] = []
    for start in range(0, len(sample), 200):
        chunk = sample[start:start + 200]
        absent = milvus_store.missing_v3_ids([int(row["id"]) for row in chunk])
        missing.extend(row for row in chunk if int(row["id"]) in absent)
    return missing


async def reconcile(limit: int = 500, deep: bool = False) -> dict[str, int]:
    """全量对账：修复 vec_id 缺失/失效的记忆（等价 Java MemoryMilvusSyncService.resyncAll）。

    与增量 relay 的区别：relay 只处理「有 outbox 行」的变更，
    对账负责历史遗留（早期双写失败留下的空 vec_id）与手工删库后的恢复。

    deep=True 时额外抽查「vec_id 有值但 v3 集合里没有」的行（向量库被重建的场景），
    代价是多一次 Milvus 查询，所以默认关闭，由运维在重建后手动跑一次。
    """
    if getattr(get_settings(), "memory_vectors_v3_enabled", False):
        # v3 之后不再写 vec_id：再按「vec_id 为空」挑候选，会把全表都算成待修。
        rows = await fetch_all(
            "SELECT id, summary, milvus_vec_id FROM memory_meta "
            "WHERE summary IS NOT NULL AND summary <> '' "
            "AND status <> 'dormant' "
            "AND (embedding_state IS NULL OR embedding_state <> 'projected') "
            "ORDER BY id ASC LIMIT %s",
            (limit,),
        )
    else:
        rows = await fetch_all(
            "SELECT id, summary, milvus_vec_id FROM memory_meta "
            "WHERE summary IS NOT NULL AND summary <> '' "
            "AND status <> 'dormant' "
            "AND (milvus_vec_id IS NULL OR milvus_vec_id = '') "
            "ORDER BY id ASC LIMIT %s",
            (limit,),
        )
    stats = {"candidates": len(rows), "repaired": 0, "failed": 0}
    if deep:
        known = {int(row["id"]) for row in rows}
        extra = [row for row in await _missing_in_v3(limit) if int(row["id"]) not in known]
        stats["missing_in_v3"] = len(extra)
        rows = list(rows) + extra
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
    if stats.get("repaired"):
        milvus_store.flush_v3()
    return stats


async def requeue_stuck(limit: int = 200, stuck_minutes: int = 10) -> int:
    """把 DEAD / 卡在 RUNNING 的行重新排队（运维用）。

    两处细节是踩过的：
    1. `attempts` 必须归零。只把 status 改回 PENDING 的话，行还带着 12 次失败记录，
       下一次失败立刻又 DEAD —— 等于白重投一次。
    2. 保留 `last_error`：重投之后如果还是同一句 error，说明不是抖动而是真坏了。
    3. 一次最多重投 `limit` 条：DEAD 攒到几百条时，一次性全放回去会把 relay 打满。
    """
    rows = await fetch_all(
        "SELECT id FROM memory_outbox WHERE status IN (%s, %s) "
        "AND updated_at <= DATE_SUB(NOW(), INTERVAL %s MINUTE) ORDER BY id ASC LIMIT %s",
        (STATUS_DEAD, STATUS_RUNNING, max(0, int(stuck_minutes)), max(1, int(limit))),
    )
    count = 0
    for row in rows:
        async with transaction() as cur:
            await execute_write(
                cur,
                "UPDATE memory_outbox SET status = 'PENDING', attempts = 0, "
                "next_attempt_at = NOW() WHERE id = %s",
                (int(row["id"]),))
        count += 1
    if count:
        logger.info("memory outbox requeued %d rows (dead/stuck)", count)
    return count


async def mark_projected(memory_ids: list[int]) -> int:
    """把"其实已经在 v3 里"的行标成 `embedding_state='projected'`。

    回填脚本（backfill_milvus_v3）绕过 relay 直接写 v3，这些行的 state 仍然是 NULL；
    再调 `reconcile` 会把它们当"未投影"重跑一遍 embedding（一万多行白烧 CPU）。
    这里先问 Milvus 本身，只标真正存在的，缺的留给 reconcile 补。
    """
    ids = [int(mid) for mid in (memory_ids or []) if int(mid or 0) > 0]
    if not ids or not getattr(get_settings(), "memory_vectors_v3_enabled", False):
        return 0
    missing = milvus_store.missing_v3_ids(ids)
    marked = 0
    for mid in ids:
        if mid in missing:
            continue
        await _set_embedding_state(mid, "projected")
        marked += 1
    return marked


__all__ = ["drain_once", "mark_projected", "project_upsert", "requeue_stuck"]
