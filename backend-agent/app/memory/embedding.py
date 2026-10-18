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


async def embed(texts: list[str], *, client: httpx.AsyncClient | None = None) -> list[list[float]] | None:
    """批量向量化；失败返回 None。"""
    if not texts:
        return []
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


__all__ = ["embed", "embed_one", "rerank"]
