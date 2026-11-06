"""memory_search 工具的三级降级：v3 → Milvus → MySQL/LIKE。"""

from types import SimpleNamespace

import pytest

from app.tools import memory_search_tool as tool


def _settings(**overrides):
    base = dict(
        memory_tool_top_k=5,
        memory_search_candidate_count=20,
        memory_importance_threshold=0.5,
        memory_like_boost_limit=5,
        memory_semantic_ttl_minutes=15,
        memory_recall_v3_enabled=False,
    )
    base.update(overrides)
    return SimpleNamespace(**base)


@pytest.fixture(autouse=True)
def no_cache(monkeypatch):
    async def empty_get(*args, **kwargs):
        return None

    async def noop_put(*args, **kwargs):
        return None

    monkeypatch.setattr(tool, "cache_get_json", empty_get)
    monkeypatch.setattr(tool, "cache_put_json", noop_put)


async def test_v3_hit_short_circuits_milvus(monkeypatch):
    monkeypatch.setattr(tool, "get_settings", lambda: _settings(memory_recall_v3_enabled=True))

    async def fake_recall(character_id, user_id, query, *, limit=5, now=None):
        assert (character_id, user_id, query) == (3, 7, "上次说的")
        return [{"id": 1, "summary": "她喜欢冰美式", "quote": "我每天都要一杯冰美式"}]

    async def boom(*args, **kwargs):
        raise AssertionError("v3 命中时不该再查 Milvus")

    monkeypatch.setattr("app.memory.recall.recall", fake_recall)
    monkeypatch.setattr(tool.milvus_store, "search", boom)

    lines = await tool.search_semantic(7, 3, "上次说的")

    assert lines == ["- 我每天都要一杯冰美式"]


async def test_v3_failure_falls_back_to_mysql(monkeypatch):
    monkeypatch.setattr(tool, "get_settings", lambda: _settings(memory_recall_v3_enabled=True))

    async def broken_recall(*args, **kwargs):
        raise RuntimeError("v3 down")

    async def no_hits(*args, **kwargs):
        return []

    async def fake_fetch_all(sql, params=()):
        return [{"id": 5, "summary": "她喜欢冰美式", "memory_type": "FACT", "importance": 0.8}]

    async def no_vectors(query):
        return []

    async def no_rank(query, candidates):
        return []

    async def no_rerank(query, pool, top_n=5):
        return []

    monkeypatch.setattr("app.memory.recall.recall", broken_recall)
    monkeypatch.setattr(tool.milvus_store, "search", no_hits)
    monkeypatch.setattr("app.memory.embedding.embed", no_vectors)
    monkeypatch.setattr(tool, "fetch_all", fake_fetch_all)
    monkeypatch.setattr(tool, "rank_by_vector", no_rank)
    monkeypatch.setattr(tool, "rerank_docs", no_rerank)

    lines = await tool.search_semantic(7, 3, "咖啡")

    assert lines == ["- 她喜欢冰美式"]
