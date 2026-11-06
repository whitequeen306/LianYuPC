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


async def test_v3_block_gives_up_when_recall_exceeds_budget(monkeypatch, caplog):
    """召回超时不能拖住整轮回复：直接返回 None，让调用方走旧路径。"""
    import asyncio

    from app import data_readers

    async def slow_recall(character_id, user_id, query, **kwargs):
        await asyncio.sleep(0.2)
        return [{"id": 1, "memory_type": "FACT", "summary": "a"}]

    monkeypatch.setattr("app.memory.recall.recall", slow_recall)
    monkeypatch.setattr(
        "app.data_readers.get_settings",
        lambda: SimpleNamespace(memory_recall_timeout_seconds=0.02,
                                memory_recall_mark_timeout_seconds=0.02,
                                memory_recall_v3_enabled=True),
    )

    assert await data_readers._load_v3_block(3, 7, "在吗") is None
    assert "timed out" in caplog.text


def test_core_lane_respects_mute():
    """钉住/核心记忆也要能"别再提"：muted 是用户意志，优先级高于核心身份设定。"""
    assert "muted = 0" in recall_mod._CORE_SQL


@pytest.mark.asyncio
async def test_mark_injected_revives_archived_rows(monkeypatch):
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

    await recall_mod.mark_injected([_row(7, fade_level="archived")])

    assert len(calls) == 2
    revive_sql, revive_params = calls[1]
    assert "GREATEST(strength, 0.55)" in revive_sql
    assert "fade_level = 'active'" in revive_sql
    assert revive_params == (7,)


def test_revive_statement_is_whitelisted():
    from app.write import WriteNotAllowed, _assert_allowed

    _assert_allowed(
        "UPDATE memory_meta SET strength = GREATEST(strength, 0.55), "
        "fade_level = 'active', status = 'active', archived_at = NULL, "
        "revival_count = revival_count + 1 WHERE id IN (%s)"
    )

    with pytest.raises(WriteNotAllowed):
        _assert_allowed("UPDATE memory_meta SET strength = 1 WHERE id IN (%s)")


@pytest.mark.asyncio
async def test_only_matching_rows_reach_the_candidate_pool(monkeypatch):
    seen: list[str] = []

    async def fake_fetch(sql, params):
        seen.append(sql)
        return []

    monkeypatch.setattr(recall_mod, "fetch_all", fake_fetch)

    await recall_mod.load_candidates(3, 7, "加班")
    assert any("HAVING rel > 0" in sql for sql in seen)

    seen.clear()
    await recall_mod.load_candidates(3, 7, "")
    # 没话题时保留按重要度兜底的行为，不能一刀切成空
    assert seen and all("HAVING rel > 0" not in sql for sql in seen)


def test_near_zero_match_is_not_normalised_to_a_full_hit():
    # 同一路只有一条时 IDF 会掉到 1e-8：按池子归一会被放大成 1.0，
    # 跟「真的沾边」拿一样的分，等于把无关记忆塞进块里。
    real = _row(1, rel=0.09, _lane="ngram", summary="今天加班到很晚")
    ghost = _row("ep-9", rel=1e-8, _lane="episode", summary="她养了一只猫")

    ranked = recall_mod.rank_rows([real, ghost], limit=2, now=NOW)

    assert [row["id"] for row in ranked] == [1]


def test_weak_lane_signal_ranks_below_a_stronger_one():
    meta = _row(1, rel=0.09, _lane="ngram", summary="今天加班到很晚")
    ep = _row("ep-9", rel=0.05, _lane="episode", summary="她说加班到很晚")

    ranked = recall_mod.rank_rows([meta, ep], limit=2, now=NOW)

    assert [row["id"] for row in ranked] == [1, "ep-9"]


@pytest.mark.asyncio
async def test_vector_lane_drops_hits_below_similarity_floor(monkeypatch):
    # text-embedding-v3 对无关句也给 0.4–0.5，没有下限时「今天吃了火锅」会把
    # 她昨天加班的事召回来。门槛是配置项，换 embedding 供应商时要重标。
    monkeypatch.setattr(
        recall_mod, "get_settings",
        lambda: SimpleNamespace(
            memory_vector_recall_enabled=True,
            memory_vector_recall_top_k=10,
            memory_vector_min_similarity=0.55,
        ),
    )

    async def fake_embed(texts):
        return [[0.1, 0.2, 0.3]]

    async def fake_fetch_all(sql, params=()):
        if "IN (" in sql:
            raise AssertionError("below-floor hits must not reach MySQL")
        return []

    monkeypatch.setattr(recall_mod, "fetch_all", fake_fetch_all)
    monkeypatch.setattr("app.memory.embedding.embed", fake_embed)
    monkeypatch.setattr("app.milvus_store.search_hits", lambda c, u, v, top_k=20: [(9, 0.42)])

    assert await recall_mod.load_candidates(3, 7, "今天吃了火锅") == []


@pytest.mark.asyncio
async def test_common_bigram_only_match_is_dropped(monkeypatch):
    # 「今天」单独命中不算数：短句里它太常见，会把不相干的行拉进来。
    async def fake_fetch_all(sql, params=()):
        if sql is recall_mod._CANDIDATE_SQL_RELEVANT:
            return [_row(1, rel=0.09, summary="今天加班到很晚")]
        return []

    monkeypatch.setattr(recall_mod, "fetch_all", fake_fetch_all)

    assert await recall_mod.load_candidates(3, 7, "今天吃了火锅") == []

    async def fake_fetch_all_hit(sql, params=()):
        if sql is recall_mod._CANDIDATE_SQL_RELEVANT:
            return [_row(1, rel=0.09, summary="今天加班到很晚")]
        return []

    monkeypatch.setattr(recall_mod, "fetch_all", fake_fetch_all_hit)
    rows = await recall_mod.load_candidates(3, 7, "加班")
    assert [row["id"] for row in rows] == [1]
