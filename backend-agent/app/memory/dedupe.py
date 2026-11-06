"""写入前的语义去重：先问「这条是不是已经记住了」。

两阈值（不是三分类打分）：相似度 ≥0.92 认为同一件事，直接 NOOP；<0.75 认为
是新事实，直接 ADD；中间灰区才值得花一次模型判断。Mem0 的官方实现后来也
收敛到 ADD-only，我们的取舍是「宁可多记一次重复，也别把不同的事合并」——
所以灰区默认当作 ADD，只在打开开关时才扩展。

向量库是投影，不是事实源：命中后仍按 id 回 MySQL 取权威行由调用方负责。
"""

from __future__ import annotations

import logging

logger = logging.getLogger(__name__)

HIGH_SIMILARITY = 0.92
LOW_SIMILARITY = 0.75


def decide(best_similarity: float | None) -> str:
    """`noop` / `add` / `review`（灰区，调用方决定要不要问模型）。"""
    if best_similarity is None:
        return "add"
    if best_similarity >= HIGH_SIMILARITY:
        return "noop"
    if best_similarity < LOW_SIMILARITY:
        return "add"
    return "review"


async def best_similar(*, character_id: int, user_id: int, summary: str) -> tuple[float | None, int | None]:
    """返回 (最高余弦相似度, 对应 memory_id)；拿不到就 (None, None)，由调用方按 ADD 处理。"""
    from app.memory.embedding import embed_one
    from app.milvus_store import search_hits

    try:
        vector = await embed_one(summary)
        if not vector:
            return None, None
        hits = await search_hits(character_id, user_id, vector, top_k=3)
    except Exception as exc:  # noqa: BLE001 - 向量不可用不该挡住写入
        logger.debug("semantic dedupe skipped: %s", exc)
        return None, None

    best = None
    for memory_id, score in hits:
        if memory_id <= 0:
            continue
        if best is None or float(score) > best[0]:
            best = (float(score), int(memory_id))
    return best if best else (None, None)
