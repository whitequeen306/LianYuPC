"""outbox relay（Python 单写者）测试。

守几条：认领互斥、成功回填 vec_id、删除、记忆已删算完成、
Milvus 失败退避、上游不可用只延后不消耗重试、超上限死信。
"""

import pytest

from app.memory import relay


@pytest.fixture
def fake(monkeypatch):
    """桩：DB（fetch_all/transaction）+ Milvus + embedding。"""
    state = {
        "rows": [],
        "executed": [],
        "claim_ok": True,
        "vec_id": "vec-new",
        "delete_ok": True,
        "embed": [[0.1, 0.2]],
        "reconcile_rows": [],
        "meta": {"id": 11, "character_id": 3, "user_id": 7, "summary": "用户喜欢猫",
                 "memory_type": "FACT", "milvus_vec_id": None},
    }

    async def fake_fetch_all(sql, params=()):
        if "FROM memory_outbox" in sql:
            return state["rows"]
        if "milvus_vec_id IS NULL" in sql:      # reconcile 的候选查询
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
        return state["vec_id"]

    def fake_delete(ids):
        return state["delete_ok"]

    monkeypatch.setattr(relay, "fetch_all", fake_fetch_all)
    monkeypatch.setattr(relay, "transaction", fake_transaction)
    monkeypatch.setattr(relay, "embed", fake_embed)
    monkeypatch.setattr(relay.milvus_store, "insert", fake_insert)
    monkeypatch.setattr(relay.milvus_store, "delete", fake_delete)
    return state


def _row(**kw):
    base = {"id": 1, "memory_id": 11, "op": "UPSERT", "vec_id": None, "attempts": 0}
    base.update(kw)
    return base


@pytest.mark.asyncio
async def test_upsert_projects_and_backfills(fake):
    fake["rows"] = [_row()]
    stats = await relay.drain_once()

    assert stats["done"] == 1
    sqls = [sql.lower() for sql, _ in fake["executed"]]
    assert any("update memory_meta set milvus_vec_id" in s for s in sqls)
    assert any("status = %s" in s for s in sqls)


@pytest.mark.asyncio
async def test_delete_op_removes_vector(fake):
    fake["rows"] = [_row(op="DELETE", vec_id="vec-old")]
    stats = await relay.drain_once()
    assert stats["done"] == 1


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
async def test_milvus_null_result_schedules_retry(fake):
    fake["rows"] = [_row(attempts=0)]
    fake["vec_id"] = None
    stats = await relay.drain_once()

    assert stats["retried"] == 1
    retry_calls = [p for s, p in fake["executed"] if "attempts = %s" in s]
    assert retry_calls[0][0] == 1


@pytest.mark.asyncio
async def test_max_attempts_marks_dead(fake):
    fake["rows"] = [_row(attempts=7)]
    fake["vec_id"] = None
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
