"""把已有记忆回填进 `memory_vectors_v3`。

双写只覆盖"开关打开之后新写/新改"的记忆，历史行得单独补一遍——否则刚开 v3 召回时，
只有最近几天的记忆能命中，看起来像"她突然忘了很多"。

跑法：

    python -m scripts.backfill_milvus_v3 --limit 5000 --batch-size 64
    python -m scripts.backfill_milvus_v3 --dry-run          # 只看会写多少条

幂等：先按 `memory_id in [...]` 查一遍 v3，已有的跳过；重复跑不会写重。
"""

from __future__ import annotations

import argparse
import asyncio
import sys

from app.config import get_settings

_EPISODE_SELECT = (
    "SELECT id, character_id, user_id, summary, event_time "
    "FROM memory_episode "
    "WHERE status = 'active' AND summary IS NOT NULL AND summary <> '' "
    "ORDER BY id LIMIT %s"
)

_SELECT = (
    "SELECT id, character_id, user_id, summary, memory_type, layer, status, event_time "
    "FROM memory_meta "
    "WHERE status IN ('active', 'faded', 'archived') AND summary IS NOT NULL AND summary <> '' "
    "ORDER BY id LIMIT %s OFFSET %s"
)


def chunks(items: list, size: int):
    for start in range(0, len(items), size):
        yield items[start:start + size]


def missing_ids(rows: list[dict], existing: set[int]) -> list[dict]:
    """已有向量的行跳过；返回这一批里还要写的行。"""
    return [row for row in rows if int(row["id"]) not in existing]


def _event_time_ms(value) -> int:
    if value is None:
        return 0
    if hasattr(value, "timestamp"):
        return int(value.timestamp() * 1000)
    return 0


async def backfill(limit: int = 5000, batch_size: int = 64, dry_run: bool = False) -> dict:
    from app import milvus_store
    from app.db import fetch_all
    from app.memory.embedding import embed

    stats = {"scanned": 0, "would_write": 0, "written": 0, "skipped": 0, "failed": 0}
    offset = 0
    while stats["scanned"] < limit:
        rows = await fetch_all(_SELECT, (min(batch_size, limit - stats["scanned"]), offset))
        if not rows:
            break
        stats["scanned"] += len(rows)
        offset += len(rows)

        if dry_run:
            # 不连 Milvus：dry-run 只回答"会处理多少行"，真正的去重发生在正式跑里。
            stats["would_write"] += len(rows)
            continue

        client = milvus_store._connect_v3()  # noqa: SLF001 - 脚本就是运维入口
        if client is None:
            print("v3 collection not ready; stop")
            break
        ids = [int(row["id"]) for row in rows]
        try:
            found = client.query(
                collection_name=get_settings().milvus_v3_collection,
                filter=f"memory_id in [{', '.join(str(i) for i in ids)}]",
                output_fields=["memory_id"],
            )
            existing = {int(item.get("memory_id") or 0) for item in found or []}
        except Exception as exc:  # noqa: BLE001
            print(f"query existing failed, write anyway: {exc}")
            existing = set()

        pending = missing_ids(rows, existing)
        stats["skipped"] += len(rows) - len(pending)
        if not pending:
            continue

        vectors = await embed([row["summary"] for row in pending])
        if not vectors or len(vectors) != len(pending):
            stats["failed"] += len(pending)
            continue
        for row, vector in zip(pending, vectors):
            written = milvus_store.insert_v3(
                int(row["character_id"]), int(row["user_id"]), int(row["id"]),
                str(row["summary"]), str(row.get("memory_type") or "FACT"),
                str(row.get("layer") or "fact"), str(row.get("status") or "active"),
                _event_time_ms(row.get("event_time")), vector,
            )
            if written is None:
                stats["failed"] += 1
            else:
                stats["written"] += 1
    if not dry_run:
        # 原话单独一遍：它们和 memory_meta 共用集合，靠 EPISODE_ID_OFFSET 区分。
        episodes = await fetch_all(_EPISODE_SELECT, (limit,))
        stats["scanned"] += len(episodes or [])
        if episodes:
            client = milvus_store._connect_v3()  # noqa: SLF001
            offset = int(milvus_store.EPISODE_ID_OFFSET)
            vector_ids = [offset + int(row["id"]) for row in episodes]
            try:
                found = client.query(
                    collection_name=get_settings().milvus_v3_collection,
                    filter="memory_id in [" + ", ".join(str(i) for i in vector_ids) + "]",
                    output_fields=["memory_id"],
                )
                existing = {int(item.get("memory_id") or 0) for item in found or []}
            except Exception as exc:  # noqa: BLE001
                print(f"query existing episodes failed, write anyway: {exc}")
                existing = set()
            pending = [row for row in episodes if offset + int(row["id"]) not in existing]
            stats["skipped"] += len(episodes) - len(pending)
            if pending:
                vectors = await embed([row["summary"] for row in pending])
                if not vectors or len(vectors) != len(pending):
                    stats["failed"] += len(pending)
                else:
                    for row, vector in zip(pending, vectors):
                        written = milvus_store.insert_v3(
                            int(row["character_id"]), int(row["user_id"]),
                            offset + int(row["id"]), str(row["summary"]),
                            "EPISODE", "episode", "active",
                            _event_time_ms(row.get("event_time")), vector,
                        )
                        if written is None:
                            stats["failed"] += 1
                        else:
                            stats["written"] += 1

    if stats["written"]:
        # 和 relay 一样：不刷的话刚回填的向量在 search 里看不见。
        milvus_store.flush_v3()
    return stats


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="backfill memory_vectors_v3")
    parser.add_argument("--limit", type=int, default=5000)
    parser.add_argument("--batch-size", type=int, default=64)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args(argv)
    stats = asyncio.run(backfill(args.limit, args.batch_size, args.dry_run))
    print(f"v3 backfill: {stats}")
    return 0 if stats["failed"] == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
