"""把已回填进 v3 的记忆补上 `embedding_state='projected'`。

为什么需要：`backfill_milvus_v3` 是运维脚本，不走 relay，所以那些行在 MySQL 里
`embedding_state` 仍是 NULL。对账（`reconcile`）按这个字段挑候选，NULL 会被当成
"没投影过"→ 上万行重跑 embedding，白烧 CPU，也把真正缺的行挤出本批预算。

用法：
    python -m scripts.mark_projected                # 全量补齐
    python -m scripts.mark_projected --limit 2000   # 只处理前 2000 行
    python -m scripts.mark_projected --dry-run      # 只统计，不写库
"""

from __future__ import annotations

import argparse
import asyncio
import logging

from app.db import fetch_all
from app.memory import relay

logger = logging.getLogger("mark_projected")

BATCH = 500


async def _ids(after_id: int, batch: int) -> list[int]:
    rows = await fetch_all(
        "SELECT id FROM memory_meta "
        "WHERE id > %s "
        # superseded 的旧事实不该再进向量库（backfill 也只扫现存三档）；
        # dormant 的向量已经删了，重投影等于把它复活。
        "AND status IN ('active', 'faded', 'archived') "
        "AND summary IS NOT NULL AND summary <> '' "
        # 列默认值是 'pending'（不是 NULL）；'dropped' 是已删向量的沉底记忆，不要动。
        "AND (embedding_state IS NULL OR embedding_state NOT IN ('projected', 'dropped')) "
        "ORDER BY id ASC LIMIT %s",
        (after_id, batch),
    )
    return [int(row["id"]) for row in rows]


async def run(*, limit: int = 0, batch: int = BATCH, dry_run: bool = False) -> dict[str, int]:
    """按 id 键集翻页补齐；返回 {"scanned": n, "marked": n, "missing": n}。"""
    stats = {"scanned": 0, "marked": 0, "missing": 0}
    after_id = 0
    while True:
        size = min(batch, limit - stats["scanned"]) if limit else batch
        if size <= 0:
            break
        ids = await _ids(after_id, size)
        if not ids:
            break
        after_id = ids[-1]
        stats["scanned"] += len(ids)
        if dry_run:
            stats["missing"] += len(ids)
            continue
        from app import milvus_store

        missing = milvus_store.missing_v3_ids(ids)
        stats["missing"] += len(missing)
        stats["marked"] += await relay.mark_projected(ids)
        logger.info("mark projected: scanned=%s marked=%s missing=%s",
                    stats["scanned"], stats["marked"], stats["missing"])
    return stats


def main() -> None:
    parser = argparse.ArgumentParser(description="补齐 embedding_state=projected")
    parser.add_argument("--limit", type=int, default=0, help="最多扫描多少行（0=全部）")
    parser.add_argument("--batch", type=int, default=BATCH)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    stats = asyncio.run(run(limit=args.limit, batch=args.batch, dry_run=args.dry_run))
    logger.info("mark projected done: %s", stats)


if __name__ == "__main__":
    main()
