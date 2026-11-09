"""outbox 入队：写 `memory_meta` 的路径必须同时排一次投影。

守一条：这条 SQL 必须真的过受控写层白名单——绕过白名单的入队在生产上会直接被拒，
而调用方大多包在 try/except 里，失败会静默变成"记忆写了、向量没有"。
"""

import pytest

from app.memory import outbox


class _Cursor:
    lastrowid = 0

    def __init__(self):
        self.executed = []

    async def execute(self, sql, params=()):
        self.executed.append((" ".join(sql.split()), params))
        self.lastrowid = 9001


@pytest.mark.asyncio
async def test_enqueue_upsert_passes_write_guard_and_queues_immediately():
    cursor = _Cursor()

    await outbox.enqueue_upsert(cursor, 11)

    assert len(cursor.executed) == 1
    sql, params = cursor.executed[0]
    assert sql.startswith("INSERT INTO memory_outbox")
    assert params == (11, "UPSERT", "PENDING", 0)
    assert "NOW()" in sql, "新入队的行必须立即可跑，不该带退避"


@pytest.mark.asyncio
async def test_enqueue_upsert_accepts_numeric_string_ids():
    """memory_id 可能来自 JSON/字符串列，入队前统一成 int，避免 outbox 里出现 '11'。"""
    cursor = _Cursor()

    await outbox.enqueue_upsert(cursor, "11")

    assert cursor.executed[0][1][0] == 11
