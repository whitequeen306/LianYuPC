"""向量化 / 重排测试（P5 读路径）。

重点：**重排必须真的改变顺序**，且失败时保持原序（对齐 Java 的 0.5 分兜底）——
降级方向错了会让最相关的记忆排在最后。
"""

import httpx
import pytest
import respx

from app.config import get_settings
from app.http_client import outbound_timeout
from app.memory.embedding import embed, embed_one, rerank
from app.tools.memory_search_tool import search_semantic

EMB_BASE = "https://emb.test/v1"
RERANK_URL = "https://rerank.test/v1/rerank"


@pytest.fixture
def client():
    c = httpx.AsyncClient(timeout=outbound_timeout())
    yield c


@pytest.fixture(autouse=True)
def _settings(monkeypatch):
    settings = get_settings()
    monkeypatch.setattr(settings, "embedding_base_url", EMB_BASE, raising=False)
    monkeypatch.setattr(settings, "embedding_api_key", "emb-key", raising=False)
    monkeypatch.setattr(settings, "embedding_model", "text-embedding-v3", raising=False)
    monkeypatch.setattr(settings, "embedding_dim", 1024, raising=False)
    monkeypatch.setattr(settings, "rerank_base_url", RERANK_URL, raising=False)
    monkeypatch.setattr(settings, "rerank_api_key", "rr-key", raising=False)
    monkeypatch.setattr(settings, "rerank_model", "gte-rerank-v2", raising=False)
    yield


# ── embedding ──


@pytest.mark.asyncio
@respx.mock
async def test_embed_returns_vectors_in_input_order(client):
    """上游可能乱序返回，必须按 index 还原成输入顺序。"""
    respx.post(f"{EMB_BASE}/embeddings").respond(
        json={
            "data": [
                {"index": 1, "embedding": [0.2, 0.2]},
                {"index": 0, "embedding": [0.1, 0.1]},
            ]
        }
    )
    vectors = await embed(["第一句", "第二句"], client=client)
    assert vectors == [[0.1, 0.1], [0.2, 0.2]]


@pytest.mark.asyncio
@respx.mock
async def test_embed_http_error_returns_none(client):
    respx.post(f"{EMB_BASE}/embeddings").respond(status_code=401)
    assert await embed(["x"], client=client) is None


@pytest.mark.asyncio
@respx.mock
async def test_embed_count_mismatch_returns_none(client):
    respx.post(f"{EMB_BASE}/embeddings").respond(
        json={"data": [{"index": 0, "embedding": [0.1]}]}
    )
    assert await embed(["a", "b"], client=client) is None


@pytest.mark.asyncio
async def test_embed_without_key_returns_none(monkeypatch):
    monkeypatch.setattr(get_settings(), "embedding_api_key", "", raising=False)
    assert await embed(["x"]) is None


@pytest.mark.asyncio
@respx.mock
async def test_embed_one_returns_single_vector(client):
    respx.post(f"{EMB_BASE}/embeddings").respond(
        json={"data": [{"index": 0, "embedding": [0.5, 0.6]}]}
    )
    assert await embed_one("hi", client=client) == [0.5, 0.6]


@pytest.mark.asyncio
async def test_embed_empty_list_returns_empty():
    assert await embed([]) == []


# ── rerank ──


@pytest.mark.asyncio
@respx.mock
async def test_rerank_sorts_by_score_desc(client):
    respx.post(RERANK_URL).respond(
        json={
            "output": {
                "results": [
                    {"index": 0, "relevance_score": 0.12},
                    {"index": 2, "relevance_score": 0.91},
                    {"index": 1, "relevance_score": 0.55},
                ]
            }
        }
    )
    scored = await rerank("喜欢什么", ["喜欢猫", "在学吉他", "猫叫咪咪"], client=client)
    assert [idx for idx, _t, _s in scored] == [2, 1, 0]
    assert scored[0][1] == "猫叫咪咪"


@pytest.mark.asyncio
@respx.mock
async def test_rerank_http_error_returns_none(client):
    respx.post(RERANK_URL).respond(status_code=500)
    assert await rerank("q", ["a"], client=client) is None


@pytest.mark.asyncio
@respx.mock
async def test_rerank_malformed_response_returns_none(client):
    respx.post(RERANK_URL).respond(json={"unexpected": True})
    assert await rerank("q", ["a"], client=client) is None


@pytest.mark.asyncio
@respx.mock
async def test_rerank_ignores_out_of_range_index(client):
    respx.post(RERANK_URL).respond(
        json={"output": {"results": [{"index": 9, "relevance_score": 0.9}]}}
    )
    assert await rerank("q", ["only-one"], client=client) is None


@pytest.mark.asyncio
async def test_rerank_without_key_returns_none(monkeypatch):
    monkeypatch.setattr(get_settings(), "rerank_api_key", "", raising=False)
    assert await rerank("q", ["a"]) is None


# ── 与 memory_search 的集成 ──


@pytest.fixture
def memory_rows(monkeypatch):
    rows = [
        {"id": 1, "summary": "用户喜欢猫"},
        {"id": 2, "summary": "用户专程来找我"},
    ]

    async def fake_fetch_all(sql, params=()):
        return [] if "LIKE" in sql else rows

    async def no_cache(key, field=None):
        return None

    async def no_put(key, value, ttl, field=None):
        return None

    monkeypatch.setattr("app.tools.memory_search_tool.fetch_all", fake_fetch_all)
    monkeypatch.setattr("app.tools.memory_search_tool.cache_get_json", no_cache)
    monkeypatch.setattr("app.tools.memory_search_tool.cache_put_json", no_put)
    return rows


@pytest.mark.asyncio
async def test_memory_search_reorders_by_rerank(memory_rows, monkeypatch):
    """重排命中时，输出顺序必须按相关性而不是 DB 顺序。"""
    async def fake_rerank(query, documents, top_n=None, client=None):
        # 反转：让 DB 里第 2 条排第一
        return [(1, documents[1], 0.9), (0, documents[0], 0.3)]

    monkeypatch.setattr("app.tools.memory_search_tool.rerank_docs", fake_rerank)
    lines = await search_semantic(7, 3, "我上次是不是说过什么")
    assert lines == ["- 用户专程来找我", "- 用户喜欢猫"]


@pytest.mark.asyncio
async def test_memory_search_falls_back_to_db_order_when_rerank_fails(memory_rows, monkeypatch):
    async def failing_rerank(query, documents, top_n=None, client=None):
        return None

    monkeypatch.setattr("app.tools.memory_search_tool.rerank_docs", failing_rerank)
    lines = await search_semantic(7, 3, "我上次是不是说过什么")
    assert lines == ["- 用户喜欢猫", "- 用户专程来找我"]


# ── 进程内向量检索（Milvus 缺失时的真实语义召回）──


def test_cosine_basics():
    from app.memory.vector_cache import cosine

    assert cosine([1.0, 0.0], [1.0, 0.0]) == pytest.approx(1.0)
    assert cosine([1.0, 0.0], [0.0, 1.0]) == pytest.approx(0.0)
    assert cosine([1.0, 0.0], [-1.0, 0.0]) == pytest.approx(-1.0)
    assert cosine([], [1.0]) == 0.0            # 空向量
    assert cosine([0.0, 0.0], [1.0, 0.0]) == 0.0  # 零向量不除零
    assert cosine([1.0, 2.0], [1.0]) == 0.0       # 维度不一致


@pytest.mark.asyncio
async def test_rank_by_vector_orders_by_similarity(monkeypatch):
    from app.memory import vector_cache

    async def fake_embed(texts, client=None):
        table = {
            "猫叫什么": [1.0, 0.0, 0.0],
            "用户养了一只猫": [0.9, 0.1, 0.0],
            "用户喜欢喝咖啡": [0.0, 1.0, 0.0],
            "用户在换工作": [0.0, 0.0, 1.0],
        }
        return [table[t] for t in texts]

    async def no_cache(key, field=None):
        return None

    async def no_put(key, value, ttl, field=None):
        return None

    monkeypatch.setattr(vector_cache, "embed", fake_embed)
    monkeypatch.setattr(vector_cache, "cache_get_json", no_cache)
    monkeypatch.setattr(vector_cache, "cache_put_json", no_put)

    ranked = await vector_cache.rank_by_vector(
        "猫叫什么", [(1, "用户喜欢喝咖啡"), (2, "用户养了一只猫"), (3, "用户在换工作")]
    )
    assert [text for _id, text, _s in ranked] == ["用户养了一只猫", "用户喜欢喝咖啡", "用户在换工作"]
    assert ranked[0][2] > ranked[1][2]


@pytest.mark.asyncio
async def test_rank_by_vector_reuses_cached_vectors(monkeypatch):
    """已缓存的文本不应再触发 embedding（成本保护）。"""
    from app.memory import vector_cache

    store = {}
    embedded_texts = []

    async def fake_embed(texts, client=None):
        embedded_texts.extend(texts)
        return [[1.0, 0.0] for _ in texts]

    async def cache_get(key, field=None):
        return store.get(key)

    async def cache_put(key, value, ttl, field=None):
        store[key] = value

    monkeypatch.setattr(vector_cache, "embed", fake_embed)
    monkeypatch.setattr(vector_cache, "cache_get_json", cache_get)
    monkeypatch.setattr(vector_cache, "cache_put_json", cache_put)

    await vector_cache.rank_by_vector("q1", [(1, "同一句话")])
    assert embedded_texts == ["q1", "同一句话"]

    embedded_texts.clear()
    await vector_cache.rank_by_vector("q1", [(1, "同一句话")])
    # 查询也要重新算（查询不缓存），但文档向量应命中缓存
    assert embedded_texts == ["q1"]


@pytest.mark.asyncio
async def test_rank_by_vector_degrades_without_key(monkeypatch):
    from app.memory import vector_cache

    monkeypatch.setattr(get_settings(), "embedding_api_key", "", raising=False)
    assert await vector_cache.rank_by_vector("q", [(1, "x")]) is None


@pytest.mark.asyncio
async def test_memory_search_uses_vector_recall(memory_rows, monkeypatch):
    """向量召回命中时，应把最相似的排到最前（而不是 DB 重要性顺序）。"""
    async def fake_rank(query, candidates):
        return [(cid, text, 0.9 if "猫" in text else 0.1) for cid, text in candidates]

    async def no_rerank(query, documents, top_n=None, client=None):
        return None

    monkeypatch.setattr("app.tools.memory_search_tool.rank_by_vector", fake_rank)
    monkeypatch.setattr("app.tools.memory_search_tool.rerank_docs", no_rerank)

    lines = await search_semantic(7, 3, "猫")
    assert lines[0] == "- 用户喜欢猫"
