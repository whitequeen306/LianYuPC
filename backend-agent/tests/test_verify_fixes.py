"""锁定大验证组报出的几个高优问题（每条都对应一个真实现场）。"""
from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path

import pytest

from app.memory import dedupe, recall, scene_close


def test_scene_window_converts_utc_card_time_to_local_wall_clock():
    """B1：卡片是 UTC，message.created_at 是 +08，直接用会让窗口恒空。"""
    utc = datetime(2026, 11, 6, 4, 30, tzinfo=timezone.utc)

    local = scene_close._db_local(utc)

    assert local == datetime(2026, 11, 6, 12, 30)
    assert local.tzinfo is None


def test_scene_window_keeps_naive_values_untouched():
    naive = datetime(2026, 11, 6, 12, 30)

    assert scene_close._db_local(naive) == naive


def test_candidate_sql_excludes_archived_memories():
    """D1：archived 已经"沉底"，不该再被当成普通候选注入。"""
    assert "fade_level <> 'archived'" in recall._CANDIDATE_SQL
    assert "fade_level <> 'archived'" in recall._VECTOR_SQL_TEMPLATE


def test_dedupe_never_matches_an_episode_vector_id():
    """B3：episode 向量 id 带 1e9 偏移，命中它等于拿一条不存在的记忆去判重。"""
    async def fake_embed(text):
        return [[0.1, 0.2]]

    monkeypatch = pytest.MonkeyPatch()
    monkeypatch.setattr("app.memory.embedding.embed_one", fake_embed)
    monkeypatch.setattr(
        "app.milvus_store.search_hits",
        lambda *args, **kwargs: [(1_000_000_901, 0.99)],
    )
    try:
        similar_id, score = pytest.importorskip("asyncio").run(
            dedupe.best_similar(character_id=3, user_id=7, summary="加班到很晚")
        )
    finally:
        monkeypatch.undo()

    assert similar_id is None
    assert score is None


def test_memory_context_is_capped_before_scene_card_is_attached():
    """A1：先截再挂场景卡，否则超预算时第一个消失的是"这一轮在聊什么"。"""
    source = (
        Path(__file__).resolve().parents[1] / "app" / "prompt_pipeline.py"
    ).read_text(encoding="utf-8")

    assert source.index("memory_context = _cap_memory_context(memory_context)") < source.index(
        "memory_context = await _attach_scene_card("
    )


@pytest.mark.asyncio
async def test_memory_failure_degrades_instead_of_raising(monkeypatch):
    """F1：记忆库挂了，整轮回复不能只剩一句"生成失败"。"""
    from app import data_readers

    async def boom(*args, **kwargs):
        raise RuntimeError("mysql down")

    monkeypatch.setattr(data_readers, "_load_profile_facts", boom)
    monkeypatch.setattr(data_readers, "_load_recent_rows", boom)
    monkeypatch.setattr(data_readers, "_load_like_boosted", boom)
    monkeypatch.setattr(
        data_readers.get_settings(), "memory_recall_v3_enabled", False, raising=False
    )

    assert await data_readers.load_profile_block(3, 7, "在吗") is None


def test_write_guard_rejects_extra_set_columns():
    """B4：前缀匹配会放行 `status='superseded', summary=%s` 这种追加列的句子。"""
    from app.write import WriteNotAllowed, _assert_allowed

    with pytest.raises(WriteNotAllowed):
        _assert_allowed(
            "UPDATE memory_meta SET status = 'superseded', summary = %s WHERE id = %s"
        )


def test_write_guard_still_allows_the_real_injection_statement():
    from app.write import _assert_allowed

    _assert_allowed(
        "UPDATE memory_meta SET last_injected_at = NOW(), last_recalled_at = NOW(), "
        "recall_count = recall_count + 1, strength = LEAST(1.000, strength + 0.05), "
        "last_decay_at = NOW() WHERE id IN (%s, %s)"
    )
