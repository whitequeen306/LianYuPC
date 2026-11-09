"""reflection 层：一句 impression 怎么落成一条可衰减、可追溯的记忆。"""

import pytest

from app.memory import reflection_store as store


class _Tx:
    async def __aenter__(self):
        return object()

    async def __aexit__(self, *exc):
        return False


def test_build_summary_prefixes_and_caps():
    assert store.build_summary("她今天笑得很少见").startswith("【印象】")
    assert len(store.build_summary("啊" * 400)) == store.MAX_SUMMARY_CHARS


@pytest.mark.asyncio
async def test_short_impression_is_not_a_memory(monkeypatch):
    monkeypatch.setattr(store, "fetch_all", _boom)
    assert await store.insert_reflection(
        user_id=7, character_id=3, conversation_id=9, impression="嗯"
    ) == 0


async def _boom(*args, **kwargs):
    raise AssertionError("short impression should not touch the database")


@pytest.mark.asyncio
async def test_reflection_is_written_into_memory_meta(monkeypatch):
    captured = {"enqueued": []}

    async def fetch_all(sql, params):
        captured["dedupe"] = params
        return []

    async def insert_returning_id(cur, sql, params):
        captured["sql"] = sql
        captured["params"] = params
        return 88

    async def enqueue_upsert(cur, memory_id):
        captured["enqueued"].append(memory_id)

    monkeypatch.setattr(store, "fetch_all", fetch_all)
    monkeypatch.setattr(store, "insert_returning_id", insert_returning_id)
    monkeypatch.setattr(store, "enqueue_upsert", enqueue_upsert)
    monkeypatch.setattr(store, "transaction", lambda: _Tx())

    new_id = await store.insert_reflection(
        user_id=7,
        character_id=3,
        conversation_id=9,
        impression="她嘴上说没事，其实很累",
        source_msg_ids=[11, 12],
    )

    assert new_id == 88
    assert "'reflection'" in captured["sql"]
    assert "tau_days" in captured["sql"]
    params = captured["params"]
    assert params[0] == 3 and params[1] == 7
    assert params[2].startswith("【印象】")
    assert params[4] == store.REFLECTION_TAU_DAYS
    assert params[5] == "[11, 12]"
    assert params[7] == "她嘴上说没事，其实很累"
    # 印象不排投影就永远进不了向量库，而 outbox 计数还是 0（生产上真丢过 10 条）。
    assert captured["enqueued"] == [88]


@pytest.mark.asyncio
async def test_same_impression_within_a_week_is_not_duplicated(monkeypatch):
    async def fetch_all(sql, params):
        return [{"id": 5}]

    async def insert_returning_id(cur, sql, params):  # pragma: no cover - 不应被调用
        raise AssertionError("duplicate should not be inserted")

    monkeypatch.setattr(store, "fetch_all", fetch_all)
    monkeypatch.setattr(store, "insert_returning_id", insert_returning_id)
    monkeypatch.setattr(store, "transaction", lambda: _Tx())

    assert await store.insert_reflection(
        user_id=7, character_id=3, conversation_id=9, impression="她嘴上说没事，其实很累"
    ) == 0


@pytest.mark.asyncio
async def test_dedupe_failure_does_not_block_writing(monkeypatch):
    async def fetch_all(sql, params):
        raise RuntimeError("db down")

    async def insert_returning_id(cur, sql, params):
        return 91

    async def enqueue_upsert(cur, memory_id):
        return None

    monkeypatch.setattr(store, "fetch_all", fetch_all)
    monkeypatch.setattr(store, "insert_returning_id", insert_returning_id)
    monkeypatch.setattr(store, "enqueue_upsert", enqueue_upsert)
    monkeypatch.setattr(store, "transaction", lambda: _Tx())

    assert await store.insert_reflection(
        user_id=7, character_id=3, conversation_id=9, impression="她嘴上说没事，其实很累"
    ) == 91
