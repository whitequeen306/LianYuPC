"""评测题集生成：把「已经记住的事」改写成一句自然的提问。

人工出 200 题最贵的是"想不起来问什么"。这里反过来做：从真实记忆里抽一条，
让模型写一句**只有这条记忆能回答**的中文提问，expected 就是这条记忆的 id。
生成完仍需人工过一遍——模型可能把两条相似的记忆写成同一句提问，而
`expected` 只能有一个。

用法：
    python -m app.memory.eval_gen --character-id 3 --user-id 7 --limit 50 \
        --base-url ... --api-key ... --model ...

输出是 `app.memory.evaluation` 能直接吃的 JSONL。
"""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
import sys
from typing import Any

logger = logging.getLogger(__name__)

MAX_ROWS = 200
MAX_QUERY_CHARS = 60

CASE_SYSTEM_PROMPT = """你在为一个「恋人型角色」的长期记忆检索做评测出题。

给你一条角色记住的事实，请写一句用户可能会问的话，要求：
1. 只用中文口语，像真的在聊天，不要出现「根据记忆」「请检索」这类评测腔；
2. 这句话必须只能由这条记忆回答，不能是「你还记得我吗」这种泛问；
3. 不要照抄原文，要换成另一种说法（同义替换、省略细节、换个角度问）；
4. 不超过 40 字。

只输出 JSON：{"query": "..."}"""


def build_messages(summary: str, memory_type: str | None = None) -> list[dict[str, str]]:
    kind = (memory_type or "FACT").strip() or "FACT"
    return [
        {"role": "system", "content": CASE_SYSTEM_PROMPT},
        {"role": "user", "content": f"类型：{kind}\n记忆：{summary}"},
    ]


def parse_query(text: str | None) -> str | None:
    """从模型输出里取 query；去围栏、去引号，空/过长一律丢掉。"""
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
    query = str(payload.get("query") or "").strip().strip("「」\"'")
    if not query or len(query) > MAX_QUERY_CHARS:
        return None
    return query


async def generate_cases(rows: list[dict[str, Any]], ask, *, limit: int = MAX_ROWS) -> list[dict[str, Any]]:
    """`ask(messages) -> str` 由调用方注入（便于测试与换 provider）。"""
    cases: list[dict[str, Any]] = []
    seen: set[str] = set()
    for row in rows:
        if len(cases) >= limit:
            break
        summary = str(row.get("summary") or "").strip()
        memory_id = row.get("id")
        if not summary or memory_id is None:
            continue
        try:
            reply = await ask(build_messages(summary, row.get("memory_type")))
        except Exception as exc:  # noqa: BLE001 - 单条失败跳过，别让整批作废
            logger.warning("eval case failed for %s: %s", memory_id, exc)
            continue
        query = parse_query(reply)
        if not query or query in seen:
            continue
        seen.add(query)
        cases.append({"query": query, "expected": str(memory_id), "memoryType": row.get("memory_type")})
    return cases


SELECT_SQL = (
    "SELECT id, summary, memory_type FROM memory_meta "
    "WHERE character_id = %s AND user_id = %s AND status = 'active' "
    "ORDER BY importance DESC, created_at DESC LIMIT %s"
)


async def load_rows(character_id: int, user_id: int, limit: int) -> list[dict[str, Any]]:
    from app.db import fetch_all

    return await fetch_all(SELECT_SQL, (character_id, user_id, limit))


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="从真实记忆生成评测题集")
    parser.add_argument("--character-id", type=int, required=True)
    parser.add_argument("--user-id", type=int, required=True)
    parser.add_argument("--limit", type=int, default=50)
    parser.add_argument("--out", default="-", help="输出文件，默认 stdout")
    parser.add_argument("--base-url", default="")
    parser.add_argument("--api-key", default="")
    parser.add_argument("--model", default="")
    args = parser.parse_args(argv)

    from app.gateway import chat_messages

    async def ask(messages):
        result = await chat_messages(
            messages,
            base_url=args.base_url,
            api_key=args.api_key,
            model=args.model,
            temperature=0.3,
            max_tokens=200,
            lane_name="background",
        )
        return result.content if hasattr(result, "content") else str(result)

    async def run() -> int:
        rows = await load_rows(args.character_id, args.user_id, args.limit)
        cases = await generate_cases(rows, ask, limit=args.limit)
        text = "\n".join(json.dumps(case, ensure_ascii=False) for case in cases)
        if args.out == "-":
            print(text)
        else:
            with open(args.out, "w", encoding="utf-8") as handle:
                handle.write(text + "\n")
        logger.info("generated %d cases from %d rows", len(cases), len(rows))
        return 0

    return asyncio.run(run())


if __name__ == "__main__":  # pragma: no cover - 入口
    sys.exit(main())
