"""向量化与重排 —— 记忆读路径的两个外部能力。

对齐 Java：
- `EmbeddingService`（POST `{base}/embeddings`，模型 text-embedding-v3，维度 1024）
- `RerankerService`（DashScope `gte-rerank-v2`）

**相对 Java 的改进（对应审计 C3）**：base_url 与 api_key 全部可配置，
不再硬编码 DashScope 地址 —— Java 侧用用户 vault 的 key 打 DashScope 固定地址，
非 DashScope 用户必然 401，重排静默降级成「按原序」。

失败语义：一律返回 None（调用方降级），绝不抛异常打断对话。
"""

from __future__ import annotations

import logging
from typing import Any

import httpx

from app.config import get_settings
from app.http_client import new_client

logger = logging.getLogger(__name__)


# DashScope text-embedding-v3 单次最多 10 条；超了直接 400，整批回填会全失败。
MAX_BATCH = 10

# 线上实测 DashScope 往返 1–11s 不等；同一句话连问两轮（重试、用户复述）不该
# 再付一次网络。缓存只活 60 秒：久一点就会把"刚纠正过的说法"召回到旧句子上。
_QUERY_CACHE: dict[str, tuple[float, list[float]]] = {}
_QUERY_TTL_SECONDS = 60.0
_QUERY_CACHE_MAX = 256


def active_backend() -> str:
    """当前向量化后端：`local`（进程内 bge）或 `dashscope`（外网 HTTP）。"""
    name = (getattr(get_settings(), "memory_embedding_backend", "dashscope") or "").strip().lower()
    return name or "dashscope"


def active_dim() -> int:
    """当前后端的向量维度。集合维度必须与它一致，否则写入直接报维度不匹配。"""
    settings = get_settings()
    if active_backend() == "local":
        return int(getattr(settings, "local_embedding_dim", 768) or 768)
    return int(settings.embedding_dim)


def is_configured() -> bool:
    """向量路是否有可用的向量化能力。本地后端只看模型文件在不在。"""
    if active_backend() == "local":
        from pathlib import Path

        from app.memory import local_embedding

        settings = get_settings()
        model_dir = Path(getattr(settings, "local_embedding_model_dir", ""))
        # 图文件名随导出方式变（model_quantized / model_int8 / …），交给同一个解析函数判断，
        # 免得换模型时 is_configured 说"没配"、向量路被静默关掉。
        graph = local_embedding.resolve_graph(model_dir)
        return graph is not None and (model_dir / local_embedding.TOKENIZER_FILE).exists()
    return bool(getattr(get_settings(), "embedding_api_key", ""))


# 不同模型的余弦尺度不可比，门槛必须跟后端走：
# - text-embedding-v3（1024 维）：无关句基线 0.46–0.52，同义 0.85+ → 0.50
# - gte-multilingual-base（768 维）：标定集正样本最低 0.566、噪声最高 0.438 → 0.50
# 数字来自 local/_threshold.py，换模型时用它重新标定。
DEFAULT_MIN_SIMILARITY: dict[str, float] = {"local": 0.50, "dashscope": 0.50}


def min_similarity() -> float:
    """向量路相似度下限；配置项为 0 时按当前后端取默认值。"""
    settings = get_settings()
    configured = float(getattr(settings, "memory_vector_min_similarity", 0.0) or 0.0)
    if configured > 0:
        return configured
    if active_backend() == "local":
        return float(getattr(settings, "local_embedding_min_similarity", DEFAULT_MIN_SIMILARITY["local"]))
    return DEFAULT_MIN_SIMILARITY["dashscope"]


async def embed_query(text: str) -> list[float] | None:
    """单条查询向量（60 秒内存缓存）。失败返回 None，调用方照常走文本路。"""
    import time

    key = (text or "").strip()
    if not key:
        return None
    now = time.monotonic()
    cached = _QUERY_CACHE.get(key)
    if cached and now - cached[0] < _QUERY_TTL_SECONDS:
        return cached[1]
    vectors = await embed([key])
    if not vectors:
        return None
    if len(_QUERY_CACHE) >= _QUERY_CACHE_MAX:
        _QUERY_CACHE.pop(next(iter(_QUERY_CACHE)))
    _QUERY_CACHE[key] = (now, vectors[0])
    return vectors[0]


async def embed(texts: list[str], *, client: httpx.AsyncClient | None = None) -> list[list[float]] | None:
    """批量向量化；本地后端直接算，外网后端按供应商上限切片。失败返回 None。"""
    if not texts:
        return []
    if active_backend() == "local":
        from app.memory import local_embedding

        return await local_embedding.embed(texts)
    vectors: list[list[float]] = []
    for start in range(0, len(texts), MAX_BATCH):
        chunk = texts[start:start + MAX_BATCH]
        part = await _embed_batch(chunk, client=client)
        if part is None:
            return None
        vectors.extend(part)
    return vectors


async def _embed_batch(texts: list[str], *, client: httpx.AsyncClient | None = None) -> list[list[float]] | None:
    """一次 HTTP 请求（调用方保证 <= MAX_BATCH）。"""
    settings = get_settings()
    if not settings.embedding_api_key:
        logger.debug("embedding skipped: no api key")
        return None

    url = f"{settings.embedding_base_url.rstrip('/')}/embeddings"
    payload: dict[str, Any] = {
        "model": settings.embedding_model,
        "input": texts,
        "dimensions": settings.embedding_dim,
    }
    headers = {"Authorization": f"Bearer {settings.embedding_api_key}"}

    try:
        if client is not None:
            response = await client.post(url, json=payload, headers=headers)
        else:
            async with new_client() as own:
                response = await own.post(url, json=payload, headers=headers)
    except Exception as exc:  # noqa: BLE001 - 降级
        logger.warning("embedding request failed: %s", exc)
        return None

    if response.status_code != 200:
        logger.warning("embedding http %s", response.status_code)
        return None
    try:
        body = response.json()
        data = body.get("data") or []
        # 按 index 排序，保证与输入顺序一致
        ordered = sorted(data, key=lambda d: d.get("index", 0))
        vectors = [item.get("embedding") or [] for item in ordered]
    except Exception as exc:  # noqa: BLE001
        logger.warning("embedding malformed response: %s", exc)
        return None

    if len(vectors) != len(texts) or not vectors or not vectors[0]:
        logger.warning("embedding size mismatch: got %d, want %d", len(vectors), len(texts))
        return None
    return vectors


async def embed_one(text: str, *, client: httpx.AsyncClient | None = None) -> list[float] | None:
    vectors = await embed([text], client=client)
    return vectors[0] if vectors else None


async def rerank(
    query: str,
    documents: list[str],
    *,
    top_n: int | None = None,
    client: httpx.AsyncClient | None = None,
) -> list[tuple[int, str, float]] | None:
    """重排；返回 [(原索引, 文本, 分数)]，按分数降序。失败返回 None。

    对齐 Java `RerankerService` 的降级语义：失败时调用方按原序补 0.5 分。
    """
    if not query or not documents:
        return []
    settings = get_settings()
    if not settings.rerank_api_key:
        logger.debug("rerank skipped: no api key")
        return None

    url = settings.rerank_base_url
    payload: dict[str, Any] = {
        "model": settings.rerank_model,
        "input": {"query": query, "documents": documents},
        "parameters": {"return_documents": False, "top_n": top_n or min(len(documents), 10)},
    }
    headers = {"Authorization": f"Bearer {settings.rerank_api_key}"}

    try:
        if client is not None:
            response = await client.post(url, json=payload, headers=headers)
        else:
            async with new_client() as own:
                response = await own.post(url, json=payload, headers=headers)
    except Exception as exc:  # noqa: BLE001
        logger.warning("rerank request failed: %s", exc)
        return None

    if response.status_code != 200:
        logger.warning("rerank http %s", response.status_code)
        return None
    try:
        body = response.json()
        results = (body.get("output") or {}).get("results") or body.get("results") or []
        scored = [
            (int(item.get("index", i)), documents[int(item.get("index", i))], float(item.get("relevance_score", 0.0)))
            for i, item in enumerate(results)
            if 0 <= int(item.get("index", i)) < len(documents)
        ]
    except Exception as exc:  # noqa: BLE001
        logger.warning("rerank malformed response: %s", exc)
        return None

    if not scored:
        return None
    scored.sort(key=lambda x: x[2], reverse=True)
    return scored


__all__ = [
    "DEFAULT_MIN_SIMILARITY",
    "active_backend",
    "active_dim",
    "embed",
    "embed_one",
    "embed_query",
    "is_configured",
    "min_similarity",
    "rerank",
]
