"""memory_search 的 Milvus 优先路径测试。

三级召回顺序必须是：
  1) Milvus 向量检索（与 Java 同源）
  2) Redis 余弦（Milvus 不可用/无命中时的本地语义召回）
  3) MySQL 重要性顺序
任何一级失败都不得让检索整体失败。
"""

import pytest

from app.tools import memory_search_tool as ms


@pytest.fixture
def env(monkeypatch):
    store = {}

    async def cache_get(key, field=None):
        return store.get((key, field))

    async def cache_put(key, value, ttl, field=None):
        store[(key, field)] = value

    async def fake_embed(texts, client=None):
        return [[1.0, 0.0]]

    state = {"milvus": [], "db_rows": [], "rerank": None}
    calls = {"db": 0}

    # milvus_store.search 是同步 API
    def fake_search(character_id, user_id, vector, top_k=5):
        return state["milvus"]

    async def fake_fetch_all(sql, params=()):
        calls["db"] += 1
        return [] if "LIKE" in sql else state["db_rows"]

    async def fake_rerank(query, documents, top_n=None, client=None):
        if state["rerank"] is None:
            return None
        return [(i, d, 0.9 - i * 0.01) for i, d in enumerate(documents)]

    monkeypatch.setattr(ms, "cache_get_json", cache_get)
    monkeypatch.setattr(ms, "cache_put_json", cache_put)
    monkeypatch.setattr(ms, "fetch_all", fake_fetch_all)
    monkeypatch.setattr(ms, "rerank_docs", fake_rerank)
    monkeypatch.setattr(ms.milvus_store, "search", fake_search)
    monkeypatch.setattr("app.memory.embedding.embed", fake_embed)
    return state, store, calls


@pytest.mark.asyncio
async def test_milvus_hits_take_precedence_and_skip_db(env):
    state, store, calls = env
    state["milvus"] = [("【长期记忆/爱好】听雨声", "FACT", 0.88)]
    state["db_rows"] = [{"id": 1, "summary": "数据库里的旧记忆"}]

    lines = await ms.search_semantic(7, 3, "雨")

    assert lines == ["- 【长期记忆/爱好】听雨声"]
    assert calls["db"] == 0, "Milvus 命中时不应再查 MySQL 候选"
    assert store, "结果应写入语义缓存"


@pytest.mark.asyncio
async def test_milvus_miss_falls_back_to_local_vector_then_db(env, monkeypatch):
    state, _store, calls = env
    state["milvus"] = []
    state["db_rows"] = [{"id": 1, "summary": "用户喜欢猫"}]

    async def fake_rank(query, candidates):
        return [(cid, text, 0.7) for cid, text in candidates]

    monkeypatch.setattr(ms, "rank_by_vector", fake_rank)

    lines = await ms.search_semantic(7, 3, "猫叫什么")

    assert lines == ["- 用户喜欢猫"]
    assert calls["db"] >= 1


@pytest.mark.asyncio
async def test_milvus_unavailable_does_not_break_search(env):
    state, _store, _calls = env
    state["milvus"] = []
    state["db_rows"] = [{"id": 1, "summary": "用户专程来找我"}]
    assert await ms.search_semantic(7, 3, "找我") == ["- 用户专程来找我"]


@pytest.mark.asyncio
async def test_milvus_hits_still_capped_by_top_k(env):
    state, _store, _calls = env
    state["milvus"] = [(f"记忆{i}", "FACT", 0.9 - i * 0.05) for i in range(10)]
    lines = await ms.search_semantic(7, 3, "记忆")
    assert len(lines) <= 5


@pytest.mark.asyncio
async def test_blank_query_short_circuits(env):
    assert await ms.search_semantic(7, 3, "  ") == []
