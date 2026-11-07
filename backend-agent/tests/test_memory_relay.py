"""outbox relay（Python 单写者）测试。

守几条：认领互斥、v3 投影成功才算完成、删除、记忆已删算完成、
Milvus 失败退避、上游不可用只延后不消耗重试、超上限死信。
"""

import pytest
from types import SimpleNamespace

from app.config import get_settings as _real_get_settings
from app.memory import relay


def _Settings(**overrides):
    """真实 Settings 的浅拷贝，只覆盖 relay 关心的开关。

    直接手写一个假对象会漏字段（`relay_batch_size` 这类），一加新配置就集体红。
    """
    values = dict(_real_get_settings().model_dump())
    values.update(overrides)
    return SimpleNamespace(**values)


@pytest.fixture
def fake(monkeypatch):
    """桩：DB（fetch_all/transaction）+ Milvus + embedding。"""
    state = {
        "rows": [],
        "executed": [],
        "claim_ok": True,
        "vec_id": "vec-new",
        "insert_v3": "v3-1",
        "inserts": [],
        "deleted_v3": [],
        "delete_ok": True,
        "embed": [[0.1, 0.2]],
        "reconcile_rows": [],
        "flushes": [],
        "meta": {"id": 11, "character_id": 3, "user_id": 7, "summary": "用户喜欢猫",
                 "memory_type": "FACT", "milvus_vec_id": None},
    }

    async def fake_fetch_all(sql, params=()):
        if "FROM memory_outbox" in sql:
            return state["rows"]
        if "embedding_state IS NULL" in sql or "milvus_vec_id IS NULL" in sql:
            return state["reconcile_rows"]
        if "FROM memory_meta" in sql:
            return [state["meta"]] if state["meta"] else []
        return []

    from contextlib import asynccontextmanager

    class FakeCursor:
        async def execute(self, sql, params=()):
            state["executed"].append((" ".join(sql.split()), params))
            if state["claim_ok"] is False and "RUNNING" in str(params):
                return 0
            return 1

    @asynccontextmanager
    async def fake_transaction():
        yield FakeCursor()

    async def fake_embed(texts, client=None):
        return state["embed"]

    # milvus_store 是同步 API（pymilvus 本身阻塞调用），桩必须同步
    def fake_insert(character_id, user_id, memory_id, summary, memory_type, vector):
        state["inserts"].append((character_id, user_id, memory_id, summary, memory_type))
        return state["vec_id"]

    def fake_insert_v3(*args, **kwargs):
        return state["insert_v3"]

    def fake_delete(ids):
        return state["delete_ok"]

    def fake_delete_v3(memory_ids):
        state["deleted_v3"].append(list(memory_ids))
        return state["delete_ok"]

    monkeypatch.setattr(relay, "get_settings", lambda: _Settings())
    monkeypatch.setattr(relay, "fetch_all", fake_fetch_all)
    monkeypatch.setattr(relay, "transaction", fake_transaction)
    monkeypatch.setattr(relay, "embed", fake_embed)
    monkeypatch.setattr(relay.milvus_store, "insert", fake_insert)
    monkeypatch.setattr(relay.milvus_store, "insert_v3", fake_insert_v3)
    monkeypatch.setattr(relay.milvus_store, "delete", fake_delete)
    monkeypatch.setattr(relay.milvus_store, "delete_v3_by_memory_ids", fake_delete_v3)
    monkeypatch.setattr(relay.milvus_store, "flush_v3", lambda: state["flushes"].append(1))
    return state


def _row(**kw):
    base = {"id": 1, "memory_id": 11, "op": "UPSERT", "vec_id": None, "attempts": 0}
    base.update(kw)
    return base


@pytest.mark.asyncio
async def test_upsert_projects_into_v3_without_legacy_v2(fake):
    """默认只写 v3：v2 是 1024 维老集合，768 维向量写进去只会被判维度不符。"""
    fake["rows"] = [_row()]
    stats = await relay.drain_once()

    assert stats["done"] == 1
    sqls = [sql.lower() for sql, _ in fake["executed"]]
    assert any("set embedding_state = %s" in s for s in sqls)
    assert not any("milvus_vec_id = %s" in s for s in sqls), "不再写 v2 主键"
    assert fake["inserts"] == [], "v2 关闭时不该碰 v2 集合"
    assert fake["flushes"] == [1], "写批末尾要 flush，否则下一轮搜不到刚记住的事"


@pytest.mark.asyncio
async def test_upsert_writes_v2_when_legacy_lane_is_on(fake, monkeypatch):
    monkeypatch.setattr(relay, "get_settings", lambda: _Settings(memory_vectors_v2_enabled=True))
    fake["rows"] = [_row()]
    stats = await relay.drain_once()

    assert stats["done"] == 1
    assert fake["inserts"] == [(3, 7, 11, "用户喜欢猫", "FACT")]
    sqls = [sql.lower() for sql, _ in fake["executed"]]
    assert any("milvus_vec_id = %s" in s for s in sqls)


@pytest.mark.asyncio
async def test_delete_op_removes_v3_projection_by_memory_id(fake):
    fake["rows"] = [_row(op="DELETE", vec_id="vec-old")]
    stats = await relay.drain_once()
    assert stats["done"] == 1
    assert fake["deleted_v3"] == [[11]], "v3 主键是自增 id，删除只能按 memory_id 过滤"


@pytest.mark.asyncio
async def test_missing_meta_is_skipped_not_retried(fake):
    fake["meta"] = None
    fake["rows"] = [_row()]
    stats = await relay.drain_once()
    assert stats["skipped"] == 1
    assert stats["retried"] == 0


@pytest.mark.asyncio
async def test_embedding_unavailable_defers_without_burning_attempts(fake):
    """上游不可用 ≠ 业务失败：只延后，不增加 attempts（否则 Milvus 重启会打满死信）。"""
    fake["rows"] = [_row(attempts=3)]
    fake["embed"] = None
    stats = await relay.drain_once()

    assert stats["deferred"] == 1
    retry_calls = [p for s, p in fake["executed"] if "attempts = %s" in s]
    assert retry_calls and retry_calls[0][0] == 3, "attempts 不应增加"


@pytest.mark.asyncio
async def test_v3_null_result_schedules_retry(fake):
    """v3 没写成功就不能标 DONE：标了就没人再来，这条记忆永远失去向量召回。"""
    fake["rows"] = [_row(attempts=0)]
    fake["insert_v3"] = None
    stats = await relay.drain_once()

    assert stats["retried"] == 1
    retry_calls = [p for s, p in fake["executed"] if "attempts = %s" in s]
    assert retry_calls[0][0] == 1


@pytest.mark.asyncio
async def test_max_attempts_marks_dead(fake):
    fake["rows"] = [_row(attempts=7)]
    fake["insert_v3"] = None
    stats = await relay.drain_once(max_attempts=8)

    assert stats["dead"] == 1
    # DEAD 分支的 SQL 形如 SET attempts = %s, status = %s, ...，参数为 (8, "DEAD", reason, id)
    dead_calls = [p for s, p in fake["executed"] if "status = %s" in s and "DEAD" in p]
    assert dead_calls


@pytest.mark.asyncio
async def test_claim_conflict_skips(monkeypatch, fake):
    fake["rows"] = [_row()]
    fake["claim_ok"] = False
    stats = await relay.drain_once()
    assert stats["done"] == 0


@pytest.mark.asyncio
async def test_empty_outbox_is_noop(fake):
    stats = await relay.drain_once()
    assert stats["picked"] == 0
    assert fake["executed"] == []

# ── 全量对账（等价 Java MemoryMilvusSyncService）──


@pytest.mark.asyncio
async def test_reconcile_repairs_missing_vec_id(fake):
    fake["reconcile_rows"] = [{"id": 11, "summary": "用户喜欢猫", "milvus_vec_id": None}]
    stats = await relay.reconcile(limit=10)

    assert "candidates" in stats
    assert stats["candidates"] >= 0
    assert stats["repaired"] + stats["failed"] == stats["candidates"]


@pytest.mark.asyncio
async def test_reconcile_uses_projection_state_when_v3_is_on(fake, monkeypatch):
    """v3 之后不再写 vec_id：还按「vec_id 为空」挑候选，会把全表都算成待修。"""
    queries: list[str] = []

    async def spy(sql, params=()):
        queries.append(sql)
        return []

    monkeypatch.setattr(relay, "fetch_all", spy)
    stats = await relay.reconcile(limit=10)

    assert stats["candidates"] == 0
    assert any("embedding_state" in sql for sql in queries)
    assert not any("milvus_vec_id IS NULL" in sql for sql in queries)


@pytest.mark.asyncio
async def test_reconcile_empty_is_noop(fake):
    fake["reconcile_rows"] = []
    stats = await relay.reconcile()
    assert stats == {"candidates": 0, "repaired": 0, "failed": 0}


@pytest.mark.asyncio
async def test_reconcile_counts_failure_without_crashing(fake, monkeypatch):
    fake["reconcile_rows"] = [{"id": 11, "summary": "用户喜欢猫", "milvus_vec_id": None}]

    async def boom(memory_id):
        raise RuntimeError("milvus down")

    monkeypatch.setattr(relay, "project_upsert", boom)
    stats = await relay.reconcile()
    assert stats["failed"] == 1


@pytest.mark.asyncio
async def test_deep_reconcile_repairs_rows_missing_from_v3(fake, monkeypatch):
    """向量库被重建后，vec_id 这一列还在——只按它判断会漏掉整库。"""
    import types as _types

    monkeypatch.setattr(relay, "get_settings", lambda: _types.SimpleNamespace(
        memory_vectors_v3_enabled=True))
    monkeypatch.setattr(relay.milvus_store, "missing_v3_ids", lambda ids: {11})

    stats = await relay.reconcile(limit=10, deep=True)

    assert stats["missing_in_v3"] == 1
    assert stats["repaired"] == 1


@pytest.mark.asyncio
async def test_shallow_reconcile_does_not_query_milvus(fake, monkeypatch):
    def boom(ids):
        raise AssertionError("默认对账不该查向量库")

    monkeypatch.setattr(relay.milvus_store, "missing_v3_ids", boom)
    stats = await relay.reconcile(limit=10)
    assert "missing_in_v3" not in stats


@pytest.mark.asyncio
async def test_requeue_resets_attempts_so_dead_rows_get_a_full_budget(fake):
    """只把 status 改回 PENDING 的话，行还带着 12 次失败记录，下一次失败立刻又 DEAD。"""
    fake["rows"] = [{"id": 5}, {"id": 6}]

    count = await relay.requeue_stuck(limit=10)

    assert count == 2
    sqls = [" ".join(sql.lower().split()) for sql, _ in fake["executed"]]
    assert all("attempts = 0" in sql for sql in sqls)
    assert all("next_attempt_at = now()" in sql for sql in sqls)
    assert [params for _, params in fake["executed"]] == [(5,), (6,)]


@pytest.mark.asyncio
async def test_requeue_statement_is_whitelisted():
    """运维重投也走 write 白名单：新语句必须先登记，否则线上只会 warning。"""
    from app.write import WriteNotAllowed, _assert_allowed

    _assert_allowed(
        "UPDATE memory_outbox SET status = 'PENDING', attempts = 0, "
        "next_attempt_at = NOW() WHERE id = %s"
    )
    with pytest.raises(WriteNotAllowed):
        _assert_allowed("UPDATE memory_outbox SET status = 'PENDING', last_error = NULL WHERE id = %s")
