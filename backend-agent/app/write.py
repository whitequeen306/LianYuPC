"""受控写层：Python 侧唯一的写库入口。

与 `db.py`（只读）互为镜像。这里的约束比只读守卫更强，因为写错会污染数据：

1. **语句白名单**：只允许 `INSERT INTO memory_meta` / `INSERT INTO memory_outbox`。
   连 SELECT 都不放行（读走 `db.py`）、UPDATE/DELETE 一律拒绝。
2. **显式事务**：一次写操作要么全成要么全不成，避免「记忆写了一半、outbox 没入队」
   导致向量永不投影。
3. **参数化**：只接受 `%s` 占位，禁止拼接用户输入。

为什么白名单而不是只读账号：记忆写入本来就是 agent 层的职责（抽取是 LLM 任务），
把整条链路留在 Java 会让 agent 层永远迁不干净。但「可写」必须收敛到**极少数表**，
所以这里用表级白名单把爆炸半径钉死。
"""

from __future__ import annotations

import logging
from contextlib import asynccontextmanager
from typing import Any, AsyncIterator

import aiomysql

from app.db import pool

logger = logging.getLogger(__name__)

# 允许的写目标：(表名, 必需出现的列前缀校验用不到，改为语句形状校验)
_ALLOWED_INSERTS = frozenset({"memory_meta", "memory_outbox"})


class WriteNotAllowed(RuntimeError):
    """试图写入白名单外的表，或使用非 INSERT 语句。"""


def _assert_allowed(sql: str) -> None:
    normalized = " ".join((sql or "").split())
    lower = normalized.lower()
    if not lower.startswith("insert into "):
        raise WriteNotAllowed(f"only INSERT is allowed on the write pool: {sql[:60]}")
    # 取出表名（支持反引号）
    rest = normalized[len("insert into "):].lstrip()
    table = rest.split("(")[0].strip().strip("`").split()[0].strip("`").lower()
    if table not in _ALLOWED_INSERTS:
        raise WriteNotAllowed(
            f"table '{table}' is not writable from the agent runtime; "
            f"allowed: {sorted(_ALLOWED_INSERTS)}"
        )


@asynccontextmanager
async def transaction() -> AsyncIterator[aiomysql.Cursor]:
    """在单个事务内提供游标；异常回滚。"""
    connection_pool = await pool()
    conn = await connection_pool.acquire()
    try:
        await conn.begin()
        async with conn.cursor() as cursor:
            yield cursor
        await conn.commit()
    except Exception:
        try:
            await conn.rollback()
        except Exception:  # noqa: BLE001 - 回滚失败不应掩盖原异常
            logger.warning("write transaction rollback failed", exc_info=True)
        raise
    finally:
        connection_pool.release(conn)


async def insert_returning_id(cursor: aiomysql.Cursor, sql: str, params: tuple[Any, ...]) -> int:
    """执行白名单 INSERT 并返回自增主键。"""
    _assert_allowed(sql)
    await cursor.execute(sql, params)
    return int(cursor.lastrowid or 0)


__all__ = ["WriteNotAllowed", "insert_returning_id", "transaction"]
