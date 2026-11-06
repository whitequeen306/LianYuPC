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


REVIEW_PROMPT = """判断两条记忆说的是不是同一件事。

只按「事实是否相同」判断，不管措辞、时间先后和情绪：
- 同一件事的补充说明（多了一个细节）算同一件；
- 换了个人/换了时间/换了地点算不同；
- 只是话题相近（都在聊工作）算不同。

只输出 JSON：{"same": true} 或 {"same": false}"""


def build_review_messages(existing_summary: str, candidate_summary: str) -> list[dict[str, str]]:
    return [
        {"role": "system", "content": REVIEW_PROMPT},
        {"role": "user", "content": f"已记住：{existing_summary}\n新记忆：{candidate_summary}"},
    ]


def parse_same(text: str | None) -> bool | None:
    """模型说「是同一件」才返回 True；说不清一律 None（调用方按 ADD 兜底）。"""
    import json

    if not text:
        return None
    raw = text.strip()
    if raw.startswith("```"):
        raw = raw.strip("`")
        raw = raw[4:] if raw.lower().startswith("json") else raw
    start, end = raw.find("{"), raw.rfind("}")
    if start == -1 or end <= start:
        return None
    try:
        payload = json.loads(raw[start:end + 1])
    except (ValueError, TypeError):
        return None
    same = payload.get("same")
    return same if isinstance(same, bool) else None


async def review_same(existing_summary: str, candidate_summary: str, ask) -> bool | None:
    """灰区才走这里。`ask(messages) -> str` 由调用方注入，异常返回 None。"""
    try:
        reply = await ask(build_review_messages(existing_summary, candidate_summary))
    except Exception as exc:  # noqa: BLE001 - 判重调用失败不能挡住写入
        logger.warning("dedupe review failed: %s", exc)
        return None
    return parse_same(reply)
