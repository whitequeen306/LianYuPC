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

# 原话（memory_episode）也进同一集合：用偏移量把两类 id 区分开，避免和
# memory_meta 的自增 id 撞车（episode 901 与记忆 901 是两条不同的东西）。
EPISODE_ID_OFFSET = 1_000_000_000


def episode_vector_id(episode_id: int) -> int:
    return EPISODE_ID_OFFSET + int(episode_id)


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


def search_hits(
    character_id: int,
    user_id: int,
    vector: list[float],
    top_k: int = 20,
) -> list[tuple[int, float]]:
    """向量检索只取 memory_id 与分数，供调用方回 MySQL 取权威字段。

    为什么不让 Milvus 直接返回 summary：向量库是投影，不是事实源。摘要可能已被
    supersede/改写，召回必须以 MySQL 的当前行为准，否则会把旧名字当新名字用。
    """
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
            output_fields=["memory_id"],
        )
        results: list[tuple[int, float]] = []
        for batch in hits or []:
            for hit in batch:
                entity = hit.get("entity") or {}
                memory_id = int(entity.get("memory_id") or 0)
                if memory_id:
                    results.append((memory_id, float(hit.get("distance", 0.0))))
        return results
    except Exception as exc:  # noqa: BLE001
        logger.warning("milvus search_hits failed: %s", exc)
        return []


_client_v3: Any = None


def v3_collection_name() -> str:
    """v3 集合名：配置留空时按当前向量维度自动取（`memory_vectors_d768`）。

    集合维度是建表时定死的，换 embedding 模型必须换集合。名字跟维度绑定以后，
    改模型只改配置里的后端，不会出现"512 维向量写进 1024 维集合"的运行时错误。
    """
    configured = (get_settings().milvus_v3_collection or "").strip()
    if configured:
        return configured
    from app.memory.embedding import active_dim

    return f"memory_vectors_d{active_dim()}"


def _connect_v3() -> Any | None:
    """v3 集合的连接；集合不存在时只 warning，不挡住 v2 主链路。"""
    global _client_v3
    if _client_v3 is not None:
        return _client_v3
    settings = get_settings()
    try:
        from pymilvus import MilvusClient

        client = MilvusClient(
            uri=f"http://{settings.milvus_host}:{settings.milvus_port}",
            timeout=settings.milvus_timeout_seconds,
            # 刚写进去的行还在 growing segment 里，Bounded 一致性下可能搜不到；
            # 记忆是"这一轮刚记住、下一轮就要想起来"，这里用 Strong 换确定性。
            consistency_level="Strong",
        )
        name = v3_collection_name()
        if not client.has_collection(name):
            logger.warning("milvus v3 collection missing: %s (run scripts/init_milvus_v3.py)", name)
            return None
        client.load_collection(name)
        _client_v3 = client
        logger.info("milvus v3 collection ready: %s", name)
        return _client_v3
    except Exception as exc:  # noqa: BLE001
        logger.warning("milvus v3 connect failed: %s", exc)
        _client_v3 = None
        return None


def insert_v3(
    character_id: int,
    user_id: int,
    memory_id: int,
    summary: str,
    memory_type: str,
    layer: str,
    status: str,
    event_time_ms: int,
    vector: list[float],
) -> str | None:
    """往 v3 集合再写一份（带 layer/status/event_time 标量）。

    双写是增量投影：失败只 warning，不影响 v2 的 vec_id 回填——向量库没了可以重建，
    写库事务不能因为 Milvus 抖动而回滚。
    """
    client = _connect_v3()
    if client is None or not vector:
        return None
    settings = get_settings()
    # 同一条记忆重试/重建会把同一个 memory_id 再插一遍；先删旧投影，避免同一条记忆
    # 在向量库里出现两份（验证组 B11：集合从 49 涨到 95 就是重复投影堆出来的）。
    if memory_id:
        try:
            client.delete(
                collection_name=v3_collection_name(),
                filter=f"memory_id == {int(memory_id)}",
            )
        except Exception as exc:  # noqa: BLE001 - 删不掉就让 insert 自己兜底
            logger.debug("milvus v3 dedupe failed: memoryId=%s reason=%s", memory_id, exc)
    clipped = summary if len(summary) <= 512 else summary[:512]
    try:
        result = client.insert(
            collection_name=v3_collection_name(),
            data=[{
                "character_id": int(character_id),
                "user_id": int(user_id),
                "memory_id": int(memory_id or 0),
                "summary": clipped,
                "memory_type": memory_type or "FACT",
                "layer": layer or "fact",
                "status": status or "active",
                "event_time": int(event_time_ms or 0),
                "vector": vector,
            }],
        )
        ids = (result or {}).get("ids") or []
        return str(ids[0]) if ids else None
    except Exception as exc:  # noqa: BLE001
        logger.warning("milvus v3 insert failed: %s", exc)
        return None


def insert_v3_batch(entries: list[dict], *, dedupe: bool = False) -> int:
    """批量投影（回填专用）：一次 insert 几十条。

    逐条 `insert_v3` 是"先 delete 再 insert"两次 RPC，生产上 15k 条要跑一个多小时；
    回填前已经用 `memory_id in [...]` 查过缺失，这里不需要再去重。
    返回成功写入条数（0 表示失败，调用方按批计失败）。
    """
    client = _connect_v3()
    if client is None or not entries:
        return 0
    settings = get_settings()
    collection = v3_collection_name()
    data: list[dict] = []
    for entry in entries:
        summary = str(entry.get("summary") or "")
        data.append({
            "character_id": int(entry["character_id"]),
            "user_id": int(entry["user_id"]),
            "memory_id": int(entry["memory_id"]),
            "summary": summary if len(summary) <= 512 else summary[:512],
            "memory_type": entry.get("memory_type") or "FACT",
            "layer": entry.get("layer") or "fact",
            "status": entry.get("status") or "active",
            "event_time": int(entry.get("event_time") or 0),
            "vector": list(entry["vector"]),
        })
    if dedupe:
        for item in data:
            try:
                client.delete(
                    collection_name=collection,
                    filter=f"memory_id == {item['memory_id']}",
                )
            except Exception as exc:  # noqa: BLE001
                logger.debug("milvus v3 dedupe failed: memoryId=%s reason=%s", item["memory_id"], exc)
    try:
        client.insert(collection_name=collection, data=data)
        return len(data)
    except Exception as exc:  # noqa: BLE001
        logger.warning("milvus v3 batch insert failed: %s", exc)
        return 0


def flush_v3() -> None:
    """把刚写进 growing segment 的行刷成可检索的段。

    实测（Milvus 2.4.15 standalone）刚 insert 的行在 search 里看不见，flush 之后立刻可见；
    记忆是"这一轮记住、下一轮就要想起来"，所以写批末尾必须刷一次。
    """
    client = _connect_v3()
    if client is None:
        return
    try:
        client.flush(v3_collection_name())
    except Exception as exc:  # noqa: BLE001
        logger.warning("milvus v3 flush failed: %s", exc)


def missing_v3_ids(memory_ids: list[int]) -> set[int]:
    """在 v3 集合里查这些 memory_id 是否真的有投影，返回缺失的那部分。

    `milvus_vec_id` 只说明"曾经写成过"，向量库被重建/清空后它照样在；
    对账必须问向量库本身，而不是信任 MySQL 里的那一列。
    """
    if not memory_ids:
        return set()
    client = _connect_v3()
    if client is None:
        return set()
    settings = get_settings()
    wanted = [int(mid) for mid in memory_ids if int(mid or 0) > 0]
    if not wanted:
        return set()
    try:
        rows = client.query(
            collection_name=v3_collection_name(),
            filter=f"memory_id in {wanted}",
            output_fields=["memory_id"],
            limit=len(wanted),
        )
    except Exception as exc:  # noqa: BLE001 - 查不到就当没缺，别把对账变成故障点
        logger.warning("milvus v3 presence query failed: %s", exc)
        return set()
    present = {int((row or {}).get("memory_id") or 0) for row in (rows or [])}
    return {mid for mid in wanted if mid not in present}


def search_hits_v3(
    character_id: int,
    user_id: int,
    vector: list[float],
    top_k: int = 20,
) -> list[tuple[int, float]]:
    """v3 检索：只返回 active 的 memory_id 与分数，字段语义同 `search_hits`。"""
    client = _connect_v3()
    if client is None or not vector:
        return []
    settings = get_settings()
    try:
        hits = client.search(
            collection_name=v3_collection_name(),
            data=[vector],
            anns_field="vector",
            search_params={"metric_type": "COSINE", "params": {"nprobe": 16}},
            limit=top_k,
            filter=(
                f"character_id == {int(character_id)} && user_id == {int(user_id)}"
                ' && status == "active"'
            ),
            output_fields=["memory_id"],
        )
        results: list[tuple[int, float]] = []
        for batch in hits or []:
            for hit in batch:
                entity = hit.get("entity") or {}
                memory_id = int(entity.get("memory_id") or 0)
                if memory_id:
                    results.append((memory_id, float(hit.get("distance", 0.0))))
        return results
    except Exception as exc:  # noqa: BLE001
        logger.warning("milvus v3 search_hits failed: %s", exc)
        return []


def reset() -> None:
    """测试用：丢弃连接缓存。"""
    global _client, _client_v3
    _client = None
    _client_v3 = None


__all__ = ["EPISODE_ID_OFFSET", "episode_vector_id", "delete", "flush_v3", "insert", "insert_v3",
    "insert_v3_batch", "reset", "search", "search_hits", "search_hits_v3", "missing_v3_ids"]
