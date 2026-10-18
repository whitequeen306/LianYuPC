"""Milvus 向量存储（Python 侧）—— 与 Java `MemoryVectorStore` 严格对齐。

契约（不能凭猜）：
- 集合：`lianyu.memory.milvus.collection`，默认 `memory_vectors_v2`
- 字段：character_id / user_id / memory_id / summary / memory_type / vector
- 主键：`id`（自增，insert 后返回）
- 删除：按主键表达式 `id in [...]`
- 检索：字段 `vector`，metric COSINE，expr 过滤 character_id + user_id

任何失败返回 None / 空列表，由调用方降级 —— 与 Java 的 `log.warn` + 返回 null 一致。
"""

from __future__ import annotations

import logging
from typing import Any

from app.config import get_settings

logger = logging.getLogger(__name__)

_client: Any = None


def _connect() -> Any | None:
    """惰性连接并确认 collection 存在。"""
    global _client
    if _client is not None:
        return _client
    settings = get_settings()
    try:
        from pymilvus import Collection, connections  # 延迟导入：未启用时不增加启动成本

        connections.connect(
            alias="default",
            host=settings.milvus_host,
            port=str(settings.milvus_port),
            timeout=settings.milvus_timeout_seconds,
        )
        collection = Collection(settings.milvus_collection)
        collection.load()
        _client = collection
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
    collection = _connect()
    if collection is None or not vector:
        return None
    clipped = summary if len(summary) <= 512 else summary[:512]
    try:
        result = collection.insert([{
            "character_id": int(character_id),
            "user_id": int(user_id),
            "memory_id": int(memory_id or 0),
            "summary": clipped,
            "memory_type": memory_type or "FACT",
            "vector": vector,
        }])
        collection.flush()
        primary_keys = getattr(result, "primary_keys", None) or []
        if primary_keys:
            return str(primary_keys[0])
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
    collection = _connect()
    if collection is None:
        return False
    expr = "id in [" + ", ".join(ids) + "]"
    try:
        collection.delete(expr)
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
    collection = _connect()
    if collection is None or not vector:
        return []
    try:
        hits = collection.search(
            data=[vector],
            anns_field="vector",
            param={"metric_type": "COSINE", "params": {"nprobe": 16}},
            limit=top_k,
            expr=f"character_id == {int(character_id)} && user_id == {int(user_id)}",
            output_fields=["summary", "memory_type", "memory_id"],
        )
        results: list[tuple[str, str, float]] = []
        for batch in hits:
            for hit in batch:
                entity = hit.entity
                results.append((
                    str(entity.get("summary") or ""),
                    str(entity.get("memory_type") or "FACT"),
                    float(hit.score),
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
