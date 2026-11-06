"""v3 回填脚本：只测纯逻辑与"幂等跳过"，不连 Milvus / MySQL。"""

from __future__ import annotations

import datetime as dt

import pytest

from scripts import backfill_milvus_v3 as backfill


def test_missing_ids_skips_rows_already_projected():
    rows = [{"id": 1}, {"id": 2}, {"id": 3}]
    assert [row["id"] for row in backfill.missing_ids(rows, {2})] == [1, 3]


def test_chunks_keeps_tail_batch():
    assert list(backfill.chunks([1, 2, 3, 4, 5], 2)) == [[1, 2], [3, 4], [5]]


def test_event_time_ms_handles_none_and_datetime():
    assert backfill._event_time_ms(None) == 0
    expected = int(dt.datetime(2026, 11, 1, 8, 0).timestamp() * 1000)
    assert backfill._event_time_ms(dt.datetime(2026, 11, 1, 8, 0)) == expected


@pytest.mark.asyncio
async def test_backfill_dry_run_does_not_write(monkeypatch):
    calls: list[str] = []

    async def fake_fetch_all(sql, params=()):
        calls.append(sql)
        if len(calls) == 1:
            return [
                {"id": 1, "character_id": 3, "user_id": 7, "summary": "a",
                 "memory_type": "FACT", "layer": "fact", "status": "active", "event_time": None},
            ]
        return []

    monkeypatch.setattr("app.db.fetch_all", fake_fetch_all)

    stats = await backfill.backfill(limit=10, batch_size=10, dry_run=True)

    assert stats == {"scanned": 1, "would_write": 1, "written": 0, "skipped": 0, "failed": 0}


@pytest.mark.asyncio
async def test_backfill_writes_only_missing_rows(monkeypatch):
    async def fake_fetch_all(sql, params=()):
        if sql.endswith("LIMIT %s OFFSET %s") and params[1] == 0:
            return [
                {"id": 1, "character_id": 3, "user_id": 7, "summary": "a",
                 "memory_type": "FACT", "layer": "fact", "status": "active", "event_time": None},
                {"id": 2, "character_id": 3, "user_id": 7, "summary": "b",
                 "memory_type": "EMOTION", "layer": "episode", "status": "active", "event_time": None},
            ]
        return []

    class FakeClient:
        def query(self, **kwargs):
            return [{"memory_id": 1}]

    written: list[int] = []

    async def fake_embed(texts):
        return [[0.1] for _ in texts]

    monkeypatch.setattr("app.db.fetch_all", fake_fetch_all)
    monkeypatch.setattr("app.memory.embedding.embed", fake_embed)
    monkeypatch.setattr("app.milvus_store._connect_v3", lambda: FakeClient())
    monkeypatch.setattr(
        "app.milvus_store.insert_v3",
        lambda cid, uid, mid, *a, **kw: written.append(mid) or "pk",
    )
    stats = await backfill.backfill(limit=10, batch_size=10)

    assert written == [2]
    assert stats["written"] == 1 and stats["skipped"] == 1 and stats["failed"] == 0
