"""把「这条记忆需要投影」写进 outbox —— 所有写 `memory_meta` 的路径必须走这里。

为什么单独一个模块：抽取（writer）/ 印象（reflection_store）/ 周记（consolidate）
三条路径各自 INSERT `memory_meta`，但 relay 只认 `memory_outbox` 行。漏写这一句
**不会报错、也不会在 health 上留痕**（`outbox_pending` 看着就是 0），只是这条记忆
永远缺席向量召回。生产上 10 条 reflection 一条都没投影，就是这么静默丢的。

事务边界由调用方负责：必须在「写 memory_meta 的同一个事务」里调用，否则会出现
记忆已落库、outbox 没入队的半截状态（relay 永远看不到它）。
"""

from __future__ import annotations

from typing import Any

from app.write import insert_returning_id

_UPSERT_SQL = (
    "INSERT INTO memory_outbox (memory_id, op, status, attempts, next_attempt_at) "
    "VALUES (%s, %s, %s, %s, NOW())"
)


async def enqueue_upsert(cursor: Any, memory_id: int) -> None:
    """排一次 UPSERT 投影（当前状态立刻可跑，不需要退避）。"""
    await insert_returning_id(
        cursor, _UPSERT_SQL, (int(memory_id), "UPSERT", "PENDING", 0)
    )


__all__ = ["enqueue_upsert"]
