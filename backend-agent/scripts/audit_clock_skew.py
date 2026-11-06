"""一次性审计：找出「Python 用 UTC 写入、比 Java 的 +08 墙钟早 8 小时」的旧记忆行。

背景：2026-11-06 之前，Python 连接池没设时区，`NOW()` 取服务器 UTC；同一张表里
Java 写的行是 +08 墙钟。前端按 +08 解析，这些旧行会显示成「昨天」甚至更早。

判断依据不是猜时区，而是**来源消息**：记忆一定晚于它引用的对话消息。
若 `created_at` 比 `source_msg_ids` 里最新那条消息早 1 小时以上，只可能是时钟错位。
这个条件也让它幂等——修过一次的行不会再满足。

用法：
    python -m scripts.audit_clock_skew                 # 只报告
    python -m scripts.audit_clock_skew --apply         # 报告并 +8h
    python -m scripts.audit_clock_skew --apply --limit 2000 --table memory_episode
"""

from __future__ import annotations

import argparse
import asyncio
import json
import logging
from datetime import datetime, timedelta
from typing import Any, Iterable

import aiomysql

from app.config import get_settings

logger = logging.getLogger(__name__)

SKEW = timedelta(hours=8)
MIN_GAP = timedelta(hours=1)
MAX_GAP = timedelta(hours=9)


def parse_ids(raw: Any) -> list[int]:
    """source_msg_ids 在库里是 JSON 数组，也见过逗号分隔的旧格式。"""
    if raw is None:
        return []
    if isinstance(raw, (bytes, bytearray)):
        raw = raw.decode("utf-8", "ignore")
    if isinstance(raw, str):
        text = raw.strip()
        if not text:
            return []
        try:
            raw = json.loads(text)
        except json.JSONDecodeError:
            raw = [part for part in text.strip("[]").split(",")]
    if not isinstance(raw, Iterable):
        return []
    ids: list[int] = []
    for item in raw:
        try:
            value = int(item)
        except (TypeError, ValueError):
            continue
        if value > 0:
            ids.append(value)
    return ids


def needs_shift(created_at: datetime | None, latest_source_at: datetime | None) -> bool:
    """记忆早于来源消息、且差值落在 8 小时附近，才判定为 UTC 写入。"""
    if created_at is None or latest_source_at is None:
        return False
    gap = latest_source_at - created_at
    return MIN_GAP <= gap <= MAX_GAP


def shifted(value: datetime) -> datetime:
    return value + SKEW


async def _connect() -> aiomysql.Connection:
    settings = get_settings()
    return await aiomysql.connect(
        host=settings.mysql_host,
        port=settings.mysql_port,
        user=settings.mysql_user,
        password=settings.mysql_password,
        db=settings.mysql_database,
        charset="utf8mb4",
        autocommit=True,
        init_command="SET time_zone = '+08:00'",
    )


async def audit(table: str = "memory_meta", limit: int = 500, apply: bool = False) -> dict[str, int]:
    conn = await _connect()
    stats = {"scanned": 0, "suspects": 0, "shifted": 0}
    try:
        async with conn.cursor(aiomysql.DictCursor) as cur:
            # memory_meta 没有 updated_at，memory_episode 有；按实际列拼 SQL。
            await cur.execute(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = %s",
                (table,),
            )
            columns = {row["COLUMN_NAME"] for row in await cur.fetchall()}
            has_updated = "updated_at" in columns
            picked = "id, created_at, source_msg_ids" + (", updated_at" if has_updated else "")
            await cur.execute(
                f"SELECT {picked} FROM {table} "
                "WHERE source_msg_ids IS NOT NULL ORDER BY id ASC LIMIT %s",
                (limit,),
            )
            rows = await cur.fetchall()
            for row in rows:
                stats["scanned"] += 1
                ids = parse_ids(row.get("source_msg_ids"))
                if not ids:
                    continue
                await cur.execute(
                    "SELECT MAX(created_at) AS latest FROM message WHERE id IN "
                    f"({','.join(['%s'] * len(ids))})",
                    tuple(ids),
                )
                latest = (await cur.fetchone() or {}).get("latest")
                if not needs_shift(row["created_at"], latest):
                    continue
                stats["suspects"] += 1
                logger.info(
                    "clock skew: table=%s id=%s created_at=%s latest_source=%s",
                    table, row["id"], row["created_at"], latest,
                )
                if not apply:
                    continue
                if has_updated:
                    await cur.execute(
                        f"UPDATE {table} SET created_at = %s, updated_at = %s WHERE id = %s",
                        (shifted(row["created_at"]),
                         shifted(row.get("updated_at") or row["created_at"]),
                         row["id"]),
                    )
                else:
                    await cur.execute(
                        f"UPDATE {table} SET created_at = %s WHERE id = %s",
                        (shifted(row["created_at"]), row["id"]),
                    )
                stats["shifted"] += 1
    finally:
        conn.close()
    return stats


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    parser = argparse.ArgumentParser()
    parser.add_argument("--table", default="memory_meta", choices=["memory_meta", "memory_episode"])
    parser.add_argument("--limit", type=int, default=500)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    print(asyncio.run(audit(args.table, args.limit, args.apply)))


if __name__ == "__main__":
    main()
