"""回填后补 `embedding_state='projected'`：只标真的在 v3 里的行。"""

from __future__ import annotations

import pytest

from app import milvus_store
from app.memory import relay
from scripts import mark_projected


@pytest.fixture
def state(monkeypatch):
    st = {"ids": {}, "marked": [], "missing": set()}

    async def fake_set_embedding_state(memory_id, value):
        st["marked"].append((memory_id, value))

    async def fake_fetch_all(sql, params=()):
        after_id, size = params
        return [{"id": mid} for mid in st["ids"].get("all", []) if mid > after_id][:size]

    monkeypatch.setattr(relay, "_set_embedding_state", fake_set_embedding_state)
    monkeypatch.setattr(relay, "get_settings", lambda: type("S", (), {
        "memory_vectors_v3_enabled": True})())
    monkeypatch.setattr(relay.milvus_store, "missing_v3_ids", lambda ids: st["missing"] & set(ids))
    monkeypatch.setattr(milvus_store, "missing_v3_ids", lambda ids: st["missing"] & set(ids))
    monkeypatch.setattr(mark_projected, "fetch_all", fake_fetch_all)
    return st


@pytest.mark.asyncio
async def test_mark_projected_skips_rows_missing_from_v3(state):
    state["missing"] = {2}

    marked = await relay.mark_projected([1, 2, 3])

    assert marked == 2
    assert state["marked"] == [(1, "projected"), (3, "projected")]


@pytest.mark.asyncio
async def test_mark_projected_is_noop_when_v3_disabled(state, monkeypatch):
    monkeypatch.setattr(relay, "get_settings", lambda: type("S", (), {
        "memory_vectors_v3_enabled": False})())

    assert await relay.mark_projected([1, 2]) == 0
    assert state["marked"] == []


@pytest.mark.asyncio
async def test_run_pages_by_id_and_stops_on_empty_page(state):
    state["ids"] = {"all": [10, 11, 12, 20]}

    stats = await mark_projected.run(batch=2)

    assert stats == {"scanned": 4, "marked": 4, "missing": 0}
    assert [mid for mid, _ in state["marked"]] == [10, 11, 12, 20]


@pytest.mark.asyncio
async def test_run_respects_limit(state):
    state["ids"] = {"all": [1, 2, 3, 4, 5]}

    stats = await mark_projected.run(limit=3, batch=2)

    assert stats["scanned"] == 3
    assert [mid for mid, _ in state["marked"]] == [1, 2, 3]


@pytest.mark.asyncio
async def test_dry_run_counts_without_writing(state):
    state["ids"] = {"all": [1, 2]}

    stats = await mark_projected.run(dry_run=True)

    assert stats == {"scanned": 2, "marked": 0, "missing": 2}
    assert state["marked"] == []
