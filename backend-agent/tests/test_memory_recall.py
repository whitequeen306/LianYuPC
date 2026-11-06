"""v3 召回的接线：core 永远在、冷却会过滤、渲染带置信标记。"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
from types import SimpleNamespace

import pytest

from app.memory import recall as recall_mod

NOW = datetime(2026, 11, 6, 12, 0, tzinfo=timezone.utc)


def _row(row_id, **overrides):
    payload = dict(
        id=row_id,
        summary="她说加班到很晚",
        quote="今天加班到很晚，好累",
        memory_type="EMOTION",
        importance=0.8,
        strength=1.0,
        tau_days=30,
        fade_level="active",
        layer="fact",
        pinned=0,
        rel=0.5,
        last_decay_at=NOW - timedelta(days=1),
        event_time=NOW - timedelta(days=1),
    )
    payload.update(overrides)
    return payload


def test_core_rows_survive_zero_relevance():
    rows = [
        _row(1, layer="core", pinned=1, rel=0.0, memory_type="FACT", quote="她叫阿岚"),
        _row(2, rel=0.9),
    ]

    ranked = recall_mod.rank_rows(rows, limit=2, now=NOW)

    assert {row["id"] for row in ranked} == {1, 2}
    assert ranked[0]["id"] == 1  # 设定永远排最前


def test_cooldown_rows_are_dropped():
    rows = [
        _row(1, last_injected_at=NOW - timedelta(hours=2)),
        _row(2, last_injected_at=NOW - timedelta(days=10)),
    ]

    ranked = recall_mod.rank_rows(rows, limit=5, now=NOW)

    assert [row["id"] for row in ranked] == [2]


@pytest.mark.asyncio
async def test_vector_lane_merges_rows_and_uses_hit_score(monkeypatch):
    monkeypatch.setattr(
        recall_mod, "get_settings",
        lambda: SimpleNamespace(memory_vector_recall_enabled=True, memory_vector_recall_top_k=10),
    )

    async def fake_fetch_all(sql, params=()):
        if "IN (" in sql:
            return [_row(9, quote="她说过想去看海")]
        return []

    async def fake_embed(texts):
        return [[0.1, 0.2, 0.3]]

    monkeypatch.setattr(recall_mod, "fetch_all", fake_fetch_all)
    monkeypatch.setattr("app.memory.embedding.embed", fake_embed)
    monkeypatch.setattr("app.milvus_store.search_hits", lambda c, u, v, top_k=20: [(9, 0.77)])

    rows = await recall_mod.load_candidates(3, 7, "海")

    assert {row["id"] for row in rows} == {9}
    assert rows[0]["rel"] == 0.77


@pytest.mark.asyncio
async def test_vector_lane_is_off_by_default(monkeypatch):
    def boom(*args, **kwargs):
        raise AssertionError("vector lane must not run when disabled")

    monkeypatch.setattr(
        recall_mod, "get_settings",
        lambda: SimpleNamespace(memory_vector_recall_enabled=False, memory_vector_recall_top_k=10),
    )
    monkeypatch.setattr("app.milvus_store.search_hits", boom)

    async def fake_fetch_all(sql, params=()):
        return []

    monkeypatch.setattr(recall_mod, "fetch_all", fake_fetch_all)
    assert await recall_mod.load_candidates(3, 7, "海") == []


def test_relevance_is_normalised_per_lane():
    # ngram 的 MATCH 分数可以到 20+，向量的 cosine 只在 0-1；不按池子归一，
    # 向量路会被永久压死。
    ngram = _row(1, rel=30.0, _lane="ngram")
    vector = _row(2, rel=0.6, _lane="vector")

    ranked = recall_mod.rank_rows([ngram, vector], limit=2, now=NOW)

    assert [row["score"] for row in ranked][0] == pytest.approx(ranked[1]["score"])


def test_core_rows_ignore_cooldown():
    rows = [_row(1, layer="core", last_injected_at=NOW)]

    ranked = recall_mod.rank_rows(rows, limit=3, now=NOW)

    assert [row["id"] for row in ranked] == [1]


@pytest.mark.asyncio
async def test_mark_injected_writes_both_tables(monkeypatch):
    from contextlib import asynccontextmanager

    calls = []

    @asynccontextmanager
    async def fake_tx():
        yield object()

    async def fake_execute(cur, sql, params=()):
        calls.append((sql, params))
        return 1

    monkeypatch.setattr(recall_mod.write, "transaction", fake_tx)
    monkeypatch.setattr(recall_mod.write, "execute_write", fake_execute)

    written = await recall_mod.mark_injected([_row(4), _row("ep-9"), _row(5)])

    assert written == 2
    meta_sql, meta_params = calls[0]
    assert "UPDATE memory_meta SET last_injected_at = NOW()" in meta_sql
    assert meta_params == (4, 5)
    episode_sql, episode_params = calls[1]
    assert "UPDATE memory_episode SET last_injected_at = NOW()" in episode_sql
    assert episode_params == (9,)   # 原话也要进冷却，否则每轮都冒出来


def test_render_groups_by_type_and_marks_confidence():
    rows = [
        _row(1, memory_type="FACT", quote="她养了一只叫团子的猫"),
        _row(2, memory_type="EMOTION", fade_level="archived", quote="她说最近睡不好"),
    ]

    block = recall_mod.render_rows(rows, NOW)

    assert "[用户画像]" in block and "[近期情绪线索]" in block
    assert "[11-05·确定] 她养了一只叫团子的猫" in block
    assert "模糊" in block


def test_render_limited_drops_whole_rows_and_reports_only_used_ids():
    rows = [
        _row(i, memory_type="FACT", quote="这是一条很长的记忆" * 12)
        for i in range(1, 7)
    ]

    block, used = recall_mod.render_rows_limited(rows, NOW, max_tokens=260)

    assert used, "至少要留下一条"
    assert len(used) < len(rows), "预算卡住时必须丢掉尾巴"
    assert block.count("- [") == len(used)


async def test_recall_queries_core_candidates_and_episodes(monkeypatch):
    calls = []

    async def fake_fetch_all(sql, params):
        if "memory_episode" in sql:
            calls.append("episodes")
            return []
        calls.append("core" if "layer = 'core'" in sql else "candidates")
        if "layer = 'core'" in sql:
            return [_row(1, layer="core", pinned=1, rel=1.0)]
        return [_row(2, rel=0.7)]

    monkeypatch.setattr(recall_mod, "fetch_all", fake_fetch_all)

    rows = await recall_mod.recall(3, 7, "加班", now=NOW)

    assert calls == ["candidates", "core", "episodes"]
    assert [row["id"] for row in rows] == [1, 2]


async def test_episode_rows_join_and_render_under_shared_experience(monkeypatch):
    async def fake_fetch_all(sql, params):
        if "memory_episode" in sql:
            return [
                _row(
                    "ep-9",
                    memory_type="EPISODE",
                    fade_level=None,
                    quote="凌晨两点她还在改方案，我陪她把窗户打开了",
                    rel=0.9,
                )
            ]
        return []

    monkeypatch.setattr(recall_mod, "fetch_all", fake_fetch_all)

    rows = await recall_mod.recall(3, 7, "改方案", limit=3, now=NOW)
    assert [row["id"] for row in rows] == ["ep-9"]

    block = recall_mod.render_rows(rows, NOW)
    assert "[共同经历]" in block
    assert "凌晨两点她还在改方案" in block
    assert "确定" in block  # episode 没有 fade_level，强度够就该说死


async def test_profile_block_uses_v3_when_enabled(monkeypatch):
    from app import data_readers

    async def fake_recall(character_id, user_id, query, **kwargs):
        return [_row(1, memory_type="FACT", quote="她叫阿岚")]

    monkeypatch.setattr("app.memory.recall.recall", fake_recall)
    monkeypatch.setattr(
        data_readers,
        "get_settings",
        lambda: SimpleNamespace(memory_recall_v3_enabled=True),
    )

    block = await data_readers.load_profile_block(3, 7, "在吗")

    assert "她叫阿岚" in block
    assert "[用户画像]" in block


async def test_profile_block_falls_back_when_v3_raises(monkeypatch):
    from app import data_readers

    async def boom(*args, **kwargs):
        raise RuntimeError("Table memory_meta has no column named layer")

    monkeypatch.setattr("app.memory.recall.recall", boom)
    monkeypatch.setattr(
        data_readers,
        "get_settings",
        lambda: SimpleNamespace(memory_recall_v3_enabled=True),
    )

    async def fake_facts(character_id, user_id):
        return [_row(9, memory_type="FACT", summary="她叫阿岚")]

    monkeypatch.setattr(data_readers, "_load_profile_facts", fake_facts)
    monkeypatch.setattr(data_readers, "_load_recent_rows", lambda *a: _empty())
    monkeypatch.setattr(data_readers, "_load_like_boosted", lambda *a: _empty())

    block = await data_readers.load_profile_block(3, 7, "在吗")

    assert "她叫阿岚" in block


async def _empty():
    return []


async def test_shadow_logs_overlap_without_changing_block(monkeypatch, caplog):
    from app import data_readers

    legacy = [{"id": 1, "memory_type": "FACT", "summary": "她叫阿岚", "importance": 0.9}]

    async def fake_facts(character_id, user_id):
        return legacy

    async def fake_base(character_id, user_id):
        return []

    async def fake_like(character_id, user_id, query):
        return []

    async def fake_recall(character_id, user_id, query, **kwargs):
        return [{"id": 2, "memory_type": "FACT", "summary": "别的"}]

    monkeypatch.setattr(data_readers, "_load_profile_facts", fake_facts)
    monkeypatch.setattr(data_readers, "_load_recent_rows", fake_base)
    monkeypatch.setattr(data_readers, "_load_like_boosted", fake_like)
    monkeypatch.setattr("app.memory.recall.recall", fake_recall)
    monkeypatch.setattr(
        data_readers,
        "get_settings",
        lambda: SimpleNamespace(memory_recall_v3_enabled=False, memory_shadow_enabled=True),
    )

    with caplog.at_level("INFO", logger="app.data_readers"):
        block = await data_readers.load_profile_block(3, 7, "在吗")

    assert "她叫阿岚" in block          # 影子不改变注入内容
    assert "memory shadow overlap=0.00" in caplog.text
    assert "missing=['1']" in caplog.text


async def test_v3_block_marks_only_rows_that_made_it_in(monkeypatch):
    from app import data_readers

    seen = {}

    async def fake_recall(character_id, user_id, query, **kwargs):
        return [{"id": 1, "memory_type": "FACT", "summary": "a"},
                {"id": 2, "memory_type": "FACT", "summary": "b"}]

    def fake_render(rows, now=None, max_tokens=400):
        return "块", [1]

    async def fake_mark(rows):
        seen["ids"] = [row["id"] for row in rows]

    monkeypatch.setattr("app.memory.recall.recall", fake_recall)
    monkeypatch.setattr("app.memory.recall.render_rows_limited", fake_render)
    monkeypatch.setattr("app.memory.recall.mark_injected", fake_mark)

    block = await data_readers._load_v3_block(3, 7, "在吗")

    assert block == "块"
    assert seen["ids"] == [1]   # 被预算丢掉的行不算注入过


def test_episode_row_in_cooldown_is_skipped():
    fresh = _row("ep-1", memory_type="EPISODE")
    fresh["last_injected_at"] = None
    stale = _row("ep-2", memory_type="EPISODE")
    stale["last_injected_at"] = NOW - timedelta(hours=12)

    ranked = recall_mod.rank_rows([fresh, stale], limit=5, now=NOW)

    assert [row["id"] for row in ranked] == ["ep-1"]


def test_episode_decays_from_its_event_time():
    # 原话没有单独的"上次结算"列：衰减起点就是它发生的那天。
    assert "event_time AS last_decay_at" in recall_mod._EPISODE_SQL


def test_old_episode_ranks_below_recent_one():
    fresh = _row("ep-1", memory_type="EPISODE", rel=0.9,
                 last_decay_at=NOW - timedelta(days=1))
    old = _row("ep-2", memory_type="EPISODE", rel=0.9,
               last_decay_at=NOW - timedelta(days=240))

    ranked = recall_mod.rank_rows([old, fresh], limit=5, now=NOW)

    assert [row["id"] for row in ranked] == ["ep-1", "ep-2"]
