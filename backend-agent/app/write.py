"""受控写层：Python 侧唯一的写库入口。

与 `db.py`（只读）互为镜像。这里的约束比只读守卫更强，因为写错会污染数据：

1. **语句白名单**：只允许 `INSERT INTO memory_meta` / `INSERT INTO memory_outbox` /
   `INSERT INTO memory_episode`，以及 relay 回填 vec_id、槽位 supersede 两类固定形状的 UPDATE。
   连 SELECT 都不放行（读走 `db.py`）、DELETE 一律拒绝。
2. **显式事务**：一次写操作要么全成要么全不成，避免「记忆写了一半、outbox 没入队」
   导致向量永不投影。
3. **参数化**：只接受 `%s` 占位，禁止拼接用户输入。

为什么白名单而不是只读账号：记忆写入本来就是 agent 层的职责（抽取是 LLM 任务），
把整条链路留在 Java 会让 agent 层永远迁不干净。但「可写」必须收敛到**极少数表**，
所以这里用表级白名单把爆炸半径钉死。
"""

from __future__ import annotations

import logging
import re
from contextlib import asynccontextmanager
from typing import Any, AsyncIterator

import aiomysql

from app.db import pool

logger = logging.getLogger(__name__)

# 允许的写目标：(表名, 必需出现的列前缀校验用不到，改为语句形状校验)
_ALLOWED_INSERTS = frozenset({"memory_meta", "memory_outbox", "memory_episode"})


class WriteNotAllowed(RuntimeError):
    """试图写入白名单外的表，或使用非 INSERT 语句。"""


_UID_LIST = r"\((?:%s, )*%s\)"

# 每条 UPDATE 都按「整条语句」全等匹配，不是前缀。前缀匹配曾经允许
# `UPDATE memory_meta SET status='superseded', summary=%s WHERE id=%s`
# 这类追加列的句子一路放行——白名单就变成了摆设。
_ALLOWED_UPDATES = (
    re.compile(r"^update memory_meta set milvus_vec_id = %s where id = %s$"),
    # 画像槽位换值：旧行标 superseded + 关闭有效期，不物理删（可追溯"她以前叫过什么"）
    re.compile(
        r"^update memory_meta set status = 'superseded', valid_to = now\(\), "
        r"superseded_by = %s where user_id = %s and character_id = %s and id <> %s "
        r"and status = 'active' and summary like %s$"
    ),
    # 摘要未变：只合并来源与重要度，不碰 vec_id
    re.compile(
        r"^update memory_meta set memory_type = %s, importance = %s, "
        r"source_msg_ids = %s, source_hash = %s where id = %s$"
    ),
    # 摘要变化：覆盖内容并清空 vec_id，由 outbox 重投影
    re.compile(
        r"^update memory_meta set summary = %s, memory_type = %s, importance = %s, "
        r"source_msg_ids = %s, source_hash = %s, milvus_vec_id = null where id = %s$"
    ),
    # 召回注入后打点 + 巩固：冷却、被想起次数、强度 +0.05、衰减起点重置
    re.compile(
        r"^update memory_meta set last_injected_at = now\(\), last_recalled_at = now\(\), "
        r"recall_count = recall_count \+ 1, strength = least\(1\.000, strength \+ 0\.05\), "
        r"last_decay_at = now\(\) where id in " + _UID_LIST + r"$"
    ),
    # 复活：沉底的记忆被强 cue 命中，抬回 active（只抬强度与档位，不动摘要）
    re.compile(
        r"^update memory_meta set strength = greatest\(strength, 0\.55\), "
        r"fade_level = 'active', status = 'active', archived_at = null, "
        r"last_decay_at = now\(\), revival_count = revival_count \+ 1 where id in "
        + _UID_LIST + r"$"
    ),
    # 手动复活单条：记忆页「让她想起来」
    re.compile(
        r"^update memory_meta set strength = %s, fade_level = 'active', status = 'active', "
        r"archived_at = null, last_decay_at = now\(\), "
        r"revival_count = revival_count \+ 1 where id = %s and user_id = %s$"
    ),
    # 原话也有冷却（V59）：不记的话同一段 quote 每轮都会冒出来
    re.compile(r"^update memory_episode set last_injected_at = now\(\) where id in " + _UID_LIST + r"$"),
    re.compile(
        r"^update memory_outbox set status = %s where id = %s(?: and status = %s)?$"
    ),
    re.compile(
        r"^update memory_outbox set attempts = %s, status = %s, last_error = %s where id = %s$"
    ),
    re.compile(
        r"^update memory_outbox set next_attempt_at = "
        r"(?:now\(\)|date_add\(now\(\), interval %s second\)) where id = %s$"
    ),
    # 运维重投：把 DEAD / 卡住的 RUNNING 放回队列（attempts 归零，退避重算）
    re.compile(
        r"^update memory_outbox set status = 'pending', attempts = 0, "
        r"next_attempt_at = now\(\) where id = %s$"
    ),
)


def _assert_allowed(sql: str) -> None:
    normalized = " ".join((sql or "").split())
    lower = normalized.lower()
    if lower.startswith("update "):
        # 全等匹配：多一个 SET 列、多一个 WHERE 条件都不放行。
        for pattern in _ALLOWED_UPDATES:
            if pattern.match(lower):
                return
        raise WriteNotAllowed(f"update not allowed on the write pool: {sql[:80]}")
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


async def execute_write(cursor: aiomysql.Cursor, sql: str, params: tuple[Any, ...] = ()) -> int:
    """执行白名单内的 INSERT/UPDATE，返回影响行数。

    UPDATE 只允许 `memory_meta SET milvus_vec_id` 与 `memory_outbox SET status/attempts/...`，
    都是 relay 的状态机更新，语句硬编码在服务层（不含用户输入拼接）。
    """
    _assert_allowed(sql)
    return int(await cursor.execute(sql, params) or 0)


async def insert_returning_id(cursor: aiomysql.Cursor, sql: str, params: tuple[Any, ...]) -> int:
    """执行白名单 INSERT 并返回自增主键。"""
    _assert_allowed(sql)
    await cursor.execute(sql, params)
    return int(cursor.lastrowid or 0)


__all__ = ["WriteNotAllowed", "insert_returning_id", "transaction"]
