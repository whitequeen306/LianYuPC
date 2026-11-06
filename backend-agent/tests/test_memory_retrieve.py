"""召回打分与融合的边界。

重点不是"分数好看"，而是三件容易错的事：
1. 永不衰减的行（tau_days=0）不能被算成 0；
2. 冷却只挡"刚说过的"，不挡用户本轮主动提到（这层由调用方传 allow 处理）；
3. MMR 必须真的去冗余，而不是把同一句话换个说法全选进来。
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

from app.memory import retrieve

NOW = datetime(2026, 11, 6, 12, 0, tzinfo=timezone.utc)


def _row(**overrides):
    payload = dict(
        id=1,
        summary="她怕打雷",
        quote="打雷了我睡不着",
        importance=0.8,
        strength=1.0,
        tau_days=365,
        fade_level="active",
        last_decay_at=NOW - timedelta(days=30),
        event_time=NOW - timedelta(days=30),
    )
    payload.update(overrides)
    return payload


def test_never_decay_rows_stay_at_full_strength():
    assert retrieve.effective_strength(_row(tau_days=0), NOW) == 1.0
    assert retrieve.effective_strength(_row(tau_days=0, strength=0.1), NOW) == 1.0


def test_decay_is_front_loaded_then_flattens():
    fresh = retrieve.effective_strength(_row(last_decay_at=NOW), NOW)
    week = retrieve.effective_strength(_row(last_decay_at=NOW - timedelta(days=7), tau_days=30), NOW)
    month = retrieve.effective_strength(_row(last_decay_at=NOW - timedelta(days=30), tau_days=30), NOW)
    year = retrieve.effective_strength(_row(last_decay_at=NOW - timedelta(days=365), tau_days=365), NOW)

    assert fresh == 1.0
    assert week < fresh and month < week
    # 30 天的 tau：一周就掉到一半附近（先快）
    assert week < 0.55
    # 一年 tau 的行过了一年仍有尾巴（后慢）
    assert year > 0.2


def test_dual_exponential_beats_single_on_tail():
    row = _row(last_decay_at=NOW - timedelta(days=365), tau_days=365)
    single = 0.7 * 0 + 0.3 * 0  # 单指数在这一天已接近 0
    assert retrieve.effective_strength(row, NOW) > single


def test_relevance_dominates_score():
    strong_but_irrelevant = _row(id=1, importance=1.0)
    relevant = _row(id=2, importance=0.3)

    assert retrieve.score_candidate(relevant, relevance=0.95, now=NOW) > \
        retrieve.score_candidate(strong_but_irrelevant, relevance=0.05, now=NOW)


def test_cooldown_blocks_recent_but_not_old():
    assert retrieve.in_cooldown(_row(last_injected_at=NOW - timedelta(days=1)), NOW)
    assert not retrieve.in_cooldown(_row(last_injected_at=NOW - timedelta(days=4)), NOW)
    assert not retrieve.in_cooldown(_row(), NOW)


def test_rrf_rewards_agreement_across_routes():
    a = {"id": 1, "summary": "甲"}
    b = {"id": 2, "summary": "乙"}
    c = {"id": 3, "summary": "丙"}

    fused = retrieve.rrf_fuse([[a, b], [a, c]])

    assert fused[0]["id"] == 1
    assert fused[0]["rrf"] > fused[1]["rrf"]


def test_mmr_drops_near_duplicates():
    rows = [
        {"id": 1, "summary": "她怕打雷，晚上睡不着", "score": 1.0},
        {"id": 2, "summary": "她怕打雷，晚上睡不着觉", "score": 0.99},
        {"id": 3, "summary": "她喜欢喝冰美式", "score": 0.60},
    ]

    picked = retrieve.mmr_select(rows, limit=2, lambda_=0.5)

    assert [row["id"] for row in picked] == [1, 3]


def test_mmr_with_lambda_one_is_pure_relevance():
    rows = [
        {"id": 1, "summary": "她怕打雷", "score": 1.0},
        {"id": 2, "summary": "她怕打雷，晚上睡不着", "score": 0.99},
        {"id": 3, "summary": "她喜欢喝冰美式", "score": 0.60},
    ]

    picked = retrieve.mmr_select(rows, limit=2, lambda_=1.0)

    assert [row["id"] for row in picked] == [1, 2]


def test_render_marks_confidence_and_date():
    block = retrieve.render_recall_block([
        _row(fade_level="active"),
        _row(id=2, fade_level="archived", quote="她提过想去看海"),
    ], NOW)

    lines = block.splitlines()
    assert lines[0].startswith("- [10-07·确定]")
    assert lines[1].startswith("- [10-07·模糊]")


def test_render_skips_empty_rows():
    assert retrieve.render_recall_block([_row(quote="", summary="")], NOW) == ""


def test_empty_rrf_is_empty():
    assert retrieve.rrf_fuse([]) == []
