"""v3 向量集合：双写与检索切换。

这些测试只碰"投影"这一层：不连 Milvus、不连 MySQL。真集合由
`python -m scripts.init_milvus_v3` 建，形态对不上会在这里先炸。
"""

from __future__ import annotations

import datetime as dt
import types

import pytest

from app import milvus_store
from app.memory import recall as recall_mod
from app.memory import relay as relay_mod


def _settings(**overrides):
    base = types.SimpleNamespace(
        milvus_v3_collection="memory_vectors_v3",
        memory_vectors_v3_enabled=True,
        memory_vector_recall_enabled=True,
        memory_vector_recall_top_k=20,
    )
    for key, value in overrides.items():
        setattr(base, key, value)
    return base


class _FakeClient:
    def __init__(self):
        self.inserted: list[dict] = []
        self.searches: list[dict] = []

    def insert(self, collection_name, data):
        self.inserted.append({"collection": collection_name, "row": data[0]})
        return {"ids": [42]}

    def search(self, **kwargs):
        self.searches.append(kwargs)
        return [[{"distance": 0.81, "entity": {"memory_id": 7}}]]


def test_insert_v3_writes_layer_status_and_event_time(monkeypatch):
    fake = _FakeClient()
    monkeypatch.setattr(milvus_store, "_connect_v3", lambda: fake)
    monkeypatch.setattr(milvus_store, "get_settings", lambda: _settings())

    pk = milvus_store.insert_v3(
        3, 7, 11, "她喜欢冰美式", "FACT", "fact", "active", 1_760_000_000_000, [0.1, 0.2],
    )

    assert pk == "42"
    row = fake.inserted[0]["row"]
    assert row["layer"] == "fact"
    assert row["status"] == "active"
    assert row["event_time"] == 1_760_000_000_000
    assert row["memory_id"] == 11


def test_insert_v3_degrades_when_collection_missing(monkeypatch):
    monkeypatch.setattr(milvus_store, "_connect_v3", lambda: None)
    assert milvus_store.insert_v3(1, 2, 3, "x", "FACT", "fact", "active", 0, [0.1]) is None


def test_search_hits_v3_only_returns_active(monkeypatch):
    fake = _FakeClient()
    monkeypatch.setattr(milvus_store, "_connect_v3", lambda: fake)
    monkeypatch.setattr(milvus_store, "get_settings", lambda: _settings())

    hits = milvus_store.search_hits_v3(3, 7, [0.1, 0.2], top_k=5)

    assert hits == [(7, pytest.approx(0.81))]
    call = fake.searches[0]
    assert call["collection_name"] == "memory_vectors_v3"
    assert 'status == "active"' in call["filter"]
    assert "character_id == 3" in call["filter"]


def test_project_v3_is_off_by_default(monkeypatch):
    called: list[tuple] = []
    monkeypatch.setattr(
        relay_mod.milvus_store, "insert_v3", lambda *a, **kw: called.append((a, kw))
    )
    monkeypatch.setattr(
        relay_mod, "get_settings", lambda: _settings(memory_vectors_v3_enabled=False)
    )

    relay_mod._project_v3(
        {"id": 1, "character_id": 3, "user_id": 7, "memory_type": "FACT",
         "layer": "fact", "status": "active", "event_time": dt.datetime(2026, 11, 1, 12, 0)},
        "她喜欢冰美式", [0.1],
    )

    assert called == []


def test_project_v3_maps_columns_when_enabled(monkeypatch):
    called: dict = {}
    monkeypatch.setattr(
        relay_mod.milvus_store, "insert_v3",
        lambda *a, **kw: called.update({"args": a, "kwargs": kw}),
    )
    monkeypatch.setattr(relay_mod, "get_settings", lambda: _settings())

    relay_mod._project_v3(
        {"id": 1, "character_id": 3, "user_id": 7, "memory_type": "EMOTION",
         "layer": "episode", "status": "active", "event_time": dt.datetime(2026, 11, 1, 12, 0)},
        "她说过那天很累", [0.2],
    )

    args = called["args"]
    assert args[:7] == (3, 7, 1, "她说过那天很累", "EMOTION", "episode", "active")
    assert args[7] == int(dt.datetime(2026, 11, 1, 12, 0).timestamp() * 1000)


@pytest.mark.asyncio
async def test_vector_lane_prefers_v3_when_enabled(monkeypatch):
    calls: list[str] = []

    async def fake_embed(texts):
        return [[0.3, 0.4]]

    monkeypatch.setattr(recall_mod, "get_settings", lambda: _settings())
    monkeypatch.setattr("app.memory.embedding.embed", fake_embed)
    monkeypatch.setattr(milvus_store, "search_hits", lambda *a, **kw: calls.append("v2") or [])
    monkeypatch.setattr(milvus_store, "search_hits_v3", lambda *a, **kw: calls.append("v3") or [])

    await recall_mod._vector_rows(3, 7, "冰美式", top_k=5)

    assert calls == ["v3"]
