"""Milvus 向量存储（Python 侧）—— 与 Java `MemoryVectorStore` 严格对齐。

契约（读全 Java 实现后确认，不靠猜）：
- 集合：`lianyu.memory.milvus.collection`，默认 `memory_vectors_v2`
- 字段：character_id / user_id / memory_id / summary / memory_type / vector
- 主键：`id`（自增）
- 删除：按主键 `id in [...]`
- 检索：字段 `vector`，metric COSINE，expr 过滤 character_id + user_id

实现用 **MilvusClient**（不是 ORM `Collection`）：ORM API 已被标记
`will be removed in PyMilvus 3.1`，继续用会在升级时突然失效。

任何失败返回 None / 空列表，由调用方降级 —— 与 Java `log.warn` + 返回 null 一致。
"""

from __future__ import annotations

import logging
from typing import Any

from app.config import get_settings

logger = logging.getLogger(__name__)

_client: Any = None


def _connect() -> Any | None:
    """惰性连接；连接失败缓存 None 以便下次重试。"""
    global _client
    if _client is not None:
        return _client
    settings = get_settings()
    try:
        from pymilvus import MilvusClient  # 延迟导入：未启用时不增加启动成本

        client = MilvusClient(
            uri=f"http://{settings.milvus_host}:{settings.milvus_port}",
            timeout=settings.milvus_timeout_seconds,
        )
        if not client.has_collection(settings.milvus_collection):
            logger.warning("milvus collection missing: %s", settings.milvus_collection)
            return None
        client.load_collection(settings.milvus_collection)
        _client = client
        logger.info("milvus collection ready: %s (%s:%s)",
                    settings.milvus_collection, settings.milvus_host, settings.milvus_port)
        return _client
    except Exception as exc:  # noqa: BLE001 - 连接失败只降级
        logger.warning("milvus connect failed: %s", exc)
        _client = None
        return None


def insert(
    character_id: int,
    user_id: int,
    memory_id: int,
    summary: str,
    memory_type: str,
    vector: list[float],
) -> str | None:
    """写入一条向量，返回主键字符串；失败返回 None。"""
    client = _connect()
    if client is None or not vector:
        return None
    settings = get_settings()
    clipped = summary if len(summary) <= 512 else summary[:512]
    try:
        result = client.insert(
            collection_name=settings.milvus_collection,
            data=[{
                "character_id": int(character_id),
                "user_id": int(user_id),
                "memory_id": int(memory_id or 0),
                "summary": clipped,
                "memory_type": memory_type or "FACT",
                "vector": vector,
            }],
        )
        ids = (result or {}).get("ids") or []
        if ids:
            return str(ids[0])
        logger.warning("milvus insert returned no primary key (memoryId=%s)", memory_id)
        return None
    except Exception as exc:  # noqa: BLE001
        logger.warning("milvus insert failed: %s", exc)
        return None


def delete(vector_ids: list[str]) -> bool:
    """按主键删除；返回是否成功（无 id 视为成功）。"""
    ids = [str(v).strip() for v in (vector_ids or []) if v is not None and str(v).strip()]
    if not ids:
        return True
    client = _connect()
    if client is None:
        return False
    settings = get_settings()
    expr = "id in [" + ", ".join(ids) + "]"
    try:
        client.delete(collection_name=settings.milvus_collection, filter=expr)
        return True
    except Exception as exc:  # noqa: BLE001
        logger.warning("milvus delete failed: %s", exc)
        return False


def search(
    character_id: int,
    user_id: int,
    vector: list[float],
    top_k: int = 5,
) -> list[tuple[str, str, float]]:
    """向量检索：返回 [(summary, memory_type, score)]；失败返回空列表。"""
    client = _connect()
    if client is None or not vector:
        return []
    settings = get_settings()
    try:
        hits = client.search(
            collection_name=settings.milvus_collection,
            data=[vector],
            anns_field="vector",
            search_params={"metric_type": "COSINE", "params": {"nprobe": 16}},
            limit=top_k,
            filter=f"character_id == {int(character_id)} && user_id == {int(user_id)}",
            output_fields=["summary", "memory_type", "memory_id"],
        )
        results: list[tuple[str, str, float]] = []
        for batch in hits or []:
            for hit in batch:
                entity = hit.get("entity") or {}
                results.append((
                    str(entity.get("summary") or ""),
                    str(entity.get("memory_type") or "FACT"),
                    float(hit.get("distance", 0.0)),
                ))
        return results
    except Exception as exc:  # noqa: BLE001
        logger.warning("milvus search failed: %s", exc)
        return []


def reset() -> None:
    """测试用：丢弃连接缓存。"""
    global _client
    _client = None


__all__ = ["delete", "insert", "reset", "search"]
