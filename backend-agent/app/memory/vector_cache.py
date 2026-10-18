"""进程内向量检索（Redis 缓存向量）—— Milvus 缺失时的真实语义召回。

背景：Java 的记忆检索依赖 Milvus 向量库；Milvus 不可用时它降级为
「重要性排序 + rerank」。本模块提供第三条路：把候选记忆的向量缓存在 Redis，
查询时在进程内做余弦相似度 —— 无需 Milvus 也能做真实语义召回。

边界与代价（必须知道）：
- 候选集来自 MySQL（有上限，默认 50 条），**不是全库检索**；
  数据量远超上限时应切到 Milvus（P5 后续）。
- 首次命中某候选集时会产生一次批量 embedding 调用，之后走 Redis 缓存。
- 任何一环不可用（无 key / Redis 挂 / 上游超时）都返回 None，由调用方降级，
  绝不影响对话主链路。
"""

from __future__ import annotations

import hashlib
import logging
import math
from typing import Any, Sequence

from app.config import get_settings
from app.db import cache_get_json, cache_put_json
from app.memory.embedding import embed

logger = logging.getLogger(__name__)

VECTOR_KEY_PREFIX = "memory:vec:"


def cosine(a: Sequence[float], b: Sequence[float]) -> float:
    """余弦相似度；零向量返回 0（避免除零）。"""
    if not a or not b or len(a) != len(b):
        return 0.0
    dot = 0.0
    na = 0.0
    nb = 0.0
    for x, y in zip(a, b):
        dot += x * y
        na += x * x
        nb += y * y
    if na <= 0.0 or nb <= 0.0:
        return 0.0
    return dot / (math.sqrt(na) * math.sqrt(nb))


def _text_key(text: str) -> str:
    """按文本内容做键：同一句话只算一次向量，跨用户可复用。"""
    return VECTOR_KEY_PREFIX + hashlib.sha256(text.encode("utf-8")).hexdigest()[:32]


async def _vectors_for(texts: list[str]) -> list[list[float]] | None:
    """取向量：先查 Redis，缺的批量补算并回填。"""
    settings = get_settings()
    ttl = settings.memory_vector_ttl_hours * 3600.0
    cached: list[list[float] | None] = []
    missing_idx: list[int] = []

    for i, text in enumerate(texts):
        vector = await cache_get_json(_text_key(text))
        if isinstance(vector, list) and vector:
            cached.append(vector)
        else:
            cached.append(None)
            missing_idx.append(i)

    if missing_idx:
        computed = await embed([texts[i] for i in missing_idx])
        if computed is None:
            return None
        for slot, idx in enumerate(missing_idx):
            cached[idx] = computed[slot]
            await cache_put_json(_text_key(texts[idx]), computed[slot], ttl)
    return [v for v in cached if v is not None]


async def rank_by_vector(
    query: str,
    candidates: list[tuple[Any, str]],
) -> list[tuple[Any, str, float]] | None:
    """按语义相似度排序候选。

    @param candidates [(id, summary)]
    @return [(id, summary, score)] 降序；不可用返回 None
    """
    if not query or not candidates:
        return []
    settings = get_settings()
    if not settings.embedding_api_key:
        return None
    if len(candidates) > settings.memory_vector_max_candidates:
        candidates = candidates[: settings.memory_vector_max_candidates]

    texts = [text for _id, text in candidates]
    query_vector = await embed([query])
    if not query_vector:
        return None
    vectors = await _vectors_for(texts)
    if vectors is None or len(vectors) != len(texts):
        logger.warning("vector rank skipped: embedding incomplete")
        return None

    qv = query_vector[0]
    scored = [
        (candidates[i][0], texts[i], cosine(qv, vectors[i]))
        for i in range(len(texts))
    ]
    scored.sort(key=lambda item: item[2], reverse=True)
    return scored


__all__ = ["VECTOR_KEY_PREFIX", "cosine", "rank_by_vector"]
