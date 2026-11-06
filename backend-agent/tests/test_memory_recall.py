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


def test_render_groups_by_type_and_marks_confidence():
    rows = [
        _row(1, memory_type="FACT", quote="她养了一只叫团子的猫"),
        _row(2, memory_type="EMOTION", fade_level="archived", quote="她说最近睡不好"),
    ]

    block = recall_mod.render_rows(rows, NOW)

    assert "[用户画像]" in block and "[近期情绪线索]" in block
    assert "[11-05·确定] 她养了一只叫团子的猫" in block
    assert "模糊" in block


async def test_recall_queries_core_and_candidates(monkeypatch):
    calls = []

    async def fake_fetch_all(sql, params):
        calls.append("core" if "layer = 'core'" in sql else "candidates")
        if "layer = 'core'" in sql:
            return [_row(1, layer="core", pinned=1, rel=1.0)]
        return [_row(2, rel=0.7)]

    monkeypatch.setattr(recall_mod, "fetch_all", fake_fetch_all)

    rows = await recall_mod.recall(3, 7, "加班", now=NOW)

    assert calls == ["candidates", "core"]
    assert [row["id"] for row in rows] == [1, 2]


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
