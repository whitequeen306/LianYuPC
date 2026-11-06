"""episode 写入的形状与幂等性。

这些断言不连库：SQL 形状错了（列数/占位符对不上）在真库上才炸，代价太高。
"""

from __future__ import annotations

from datetime import datetime
from types import SimpleNamespace

import pytest

from app import write
from app.memory import episode_store


def _kwargs(**overrides):
    payload = dict(
        user_id=7,
        character_id=3,
        conversation_id=11,
        summary="她说加班到很晚，很累",
        quote="今天加班到很晚，好累，你在吗？",
        event_time=datetime(2026, 11, 6, 21, 30),
        valence=-0.4,
        arousal=0.7,
    )
    payload.update(overrides)
    return payload


def test_insert_shape_matches_placeholders():
    sql, params = episode_store.build_insert(**_kwargs())

    assert sql.startswith("INSERT INTO memory_episode")
    assert sql.count("%s") == len(params) == 14


def test_hash_is_deterministic_and_sensitive_to_quote():
    a = episode_store.episode_hash(user_id=7, character_id=3, quote="甲", event_time=None)
    b = episode_store.episode_hash(user_id=7, character_id=3, quote="甲", event_time=None)
    c = episode_store.episode_hash(user_id=7, character_id=3, quote="乙", event_time=None)

    assert a == b and a != c


def test_values_are_clamped_and_truncated():
    _, params = episode_store.build_insert(
        **_kwargs(valence=-9, arousal=9, quote="字" * 900, summary="摘" * 900)
    )

    assert params[7] == -1.0
    assert params[8] == 1.0
    assert len(params[5]) == episode_store.MAX_QUOTE
    assert len(params[4]) == episode_store.MAX_SUMMARY


def test_episode_table_is_writable_but_others_are_not():
    write._assert_allowed("INSERT INTO memory_episode (summary) VALUES (%s)")
    with pytest.raises(write.WriteNotAllowed):
        write._assert_allowed("INSERT INTO `user` (name) VALUES (%s)")


async def test_duplicate_episode_is_idempotent(monkeypatch):
    class FakeCursor:
        lastrowid = 42

        async def execute(self, sql, params):
            raise RuntimeError("(1062, \"Duplicate entry 'x' for key 'uk_ep_hash'\")")

    class FakeTxn:
        async def __aenter__(self):
            return FakeCursor()

        async def __aexit__(self, *exc):
            return False

    monkeypatch.setattr(write, "transaction", lambda: FakeTxn())

    assert await episode_store.insert_episode(**_kwargs()) == 0


async def test_insert_returns_new_id(monkeypatch):
    class FakeCursor:
        lastrowid = 99

        async def execute(self, sql, params):
            return 1

    class FakeTxn:
        async def __aenter__(self):
            return FakeCursor()

        async def __aexit__(self, *exc):
            return False

    async def fake_insert(cursor, sql, params):
        await cursor.execute(sql, params)
        return int(cursor.lastrowid)

    monkeypatch.setattr(write, "transaction", lambda: FakeTxn())
    monkeypatch.setattr(write, "insert_returning_id", fake_insert)

    assert await episode_store.insert_episode(**_kwargs()) == 99


def test_scene_json_is_serialized_without_ascii_escape():
    sql, params = episode_store.build_insert(**_kwargs(scene={"topic": "加班", "mood": "低落"}))

    assert '"topic": "加班"' in params[3]


@pytest.mark.asyncio
async def test_same_quote_within_a_week_is_not_stored_twice(monkeypatch):
    from app.memory import episode_store

    async def seen(sql, params):
        return [{"id": 9}]

    monkeypatch.setattr("app.db.fetch_all", seen)

    async def boom():
        raise AssertionError("duplicate must not reach INSERT")

    monkeypatch.setattr(episode_store.write, "transaction", boom)

    assert await episode_store.insert_episode(**_kwargs()) == 0


@pytest.mark.asyncio
async def test_duplicate_check_failure_still_writes(monkeypatch):
    from app.memory import episode_store

    async def broken(sql, params):
        raise RuntimeError("mysql down")

    monkeypatch.setattr("app.db.fetch_all", broken)

    class _Cursor:
        pass

    class _Tx:
        async def __aenter__(self):
            return _Cursor()

        async def __aexit__(self, *exc):
            return False

    monkeypatch.setattr(episode_store.write, "transaction", lambda: _Tx())

    async def returning(cursor, sql, params):
        return 42

    monkeypatch.setattr(episode_store.write, "insert_returning_id", returning)

    assert await episode_store.insert_episode(**_kwargs()) == 42
