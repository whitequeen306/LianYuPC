"""Milvus 只回 memory_id + 分数，权威字段回 MySQL 取。"""

from __future__ import annotations

from types import SimpleNamespace

from app import milvus_store


class _FakeClient:
    def __init__(self):
        self.calls = []

    def search(self, **kwargs):
        self.calls.append(kwargs)
        return [[
            {"entity": {"memory_id": 12}, "distance": 0.81},
            {"entity": {}, "distance": 0.5},
        ]]


def test_search_hits_returns_memory_ids_and_scores(monkeypatch):
    client = _FakeClient()
    monkeypatch.setattr(milvus_store, "_connect", lambda: client)
    monkeypatch.setattr(
        milvus_store, "get_settings",
        lambda: SimpleNamespace(milvus_collection="memory_vectors_v2"),
    )

    hits = milvus_store.search_hits(3, 7, [0.1, 0.2], top_k=5)

    assert hits == [(12, 0.81)]
    assert "character_id == 3 && user_id == 7" in client.calls[0]["filter"]
    assert client.calls[0]["output_fields"] == ["memory_id"]


def test_search_hits_degrades_when_client_unavailable(monkeypatch):
    monkeypatch.setattr(milvus_store, "_connect", lambda: None)
    assert milvus_store.search_hits(3, 7, [0.1]) == []
