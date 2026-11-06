"""主动浮现：频控、周年判定、挑选与"不贴题就不提"。"""

from __future__ import annotations

import datetime as dt

import pytest

from app.memory import resurface


def _row(mid, strength=0.45, event_time=None, summary="她说想去看海"):
    return {
        "id": mid,
        "summary": summary,
        "importance": 0.7,
        "strength": strength,
        "fade_level": "faded",
        "status": "active",
        "pinned": 0,
        "muted": 0,
        "event_time": event_time,
        "last_recalled_at": None,
    }


NOW = dt.datetime(2026, 11, 6, 20, 0)


def test_week_key_is_per_iso_week():
    same_week = dt.datetime(2026, 11, 8, 9, 0)
    next_week = dt.datetime(2026, 11, 9, 9, 0)

    assert resurface.week_key(3, NOW) == resurface.week_key(3, same_week)
    assert resurface.week_key(3, NOW) != resurface.week_key(3, next_week)
    assert resurface.week_key(3, NOW).startswith("resurface:3:")


def test_anniversary_needs_same_day_and_a_year():
    assert resurface.is_anniversary(dt.datetime(2025, 11, 6, 21, 0), NOW)
    assert not resurface.is_anniversary(dt.datetime(2026, 6, 6, 21, 0), NOW)   # 同月同日但不到一年
    assert not resurface.is_anniversary(dt.datetime(2025, 11, 7, 21, 0), NOW)   # 差一天
    assert not resurface.is_anniversary(None, NOW)


def test_in_band_edges():
    assert resurface.in_band(_row(1, strength=0.35))
    assert resurface.in_band(_row(1, strength=0.55))
    assert resurface.in_band(_row(1, strength="0.40"))
    assert not resurface.in_band(_row(1, strength=0.34))
    assert not resurface.in_band(_row(1, strength=0.56))
    assert not resurface.in_band(_row(1, strength=None))


def test_pick_prefers_anniversary_even_if_fainter():
    rows = [
        _row(1, strength=0.46),
        _row(2, strength=0.36, event_time=dt.datetime(2024, 11, 6, 9, 0)),
    ]

    chosen = resurface.pick(rows, NOW)

    assert chosen["id"] == 2
    assert chosen["reason"] == "anniversary"


def test_pick_takes_the_one_closest_to_half_faded():
    rows = [_row(1, strength=0.36), _row(2, strength=0.45), _row(3, strength=0.54)]

    assert resurface.pick(rows, NOW)["id"] == 2


def test_pick_returns_none_without_candidates():
    assert resurface.pick([], NOW) is None
    assert resurface.pick([_row(1, strength=0.9)], NOW) is None


def test_pick_drops_off_topic_rows():
    rows = [_row(1), _row(2)]

    assert resurface.pick(rows, NOW, topic_similarity={1: 0.05, 2: 0.19}) is None
    assert resurface.pick(rows, NOW, topic_similarity={1: 0.05, 2: 0.31})["id"] == 2


class _FakeRedis:
    def __init__(self, claimed=True):
        self.claimed = claimed
        self.calls: list[tuple] = []

    async def set(self, key, value, ex=None, nx=False):
        self.calls.append((key, value, ex, nx))
        return True if self.claimed and nx else None


@pytest.mark.asyncio
async def test_suggest_respects_weekly_cap(monkeypatch):
    redis = _FakeRedis(claimed=False)
    monkeypatch.setattr("app.db.redis_client", _async_callable(redis))

    assert await resurface.suggest(3, 7, now=NOW) is None
    assert redis.calls and redis.calls[0][2] == resurface.WEEKLY_CAP_SECONDS


@pytest.mark.asyncio
async def test_suggest_returns_candidate_and_claims_week(monkeypatch):
    redis = _FakeRedis()
    rows = [_row(9, strength=0.44)]
    monkeypatch.setattr("app.db.redis_client", _async_callable(redis))
    monkeypatch.setattr("app.db.fetch_all", _async(rows))

    picked = await resurface.suggest(3, 7, now=NOW)

    assert picked["id"] == 9
    assert picked["reason"] == "fading"


@pytest.mark.asyncio
async def test_suggest_fails_closed_when_redis_is_down(monkeypatch):
    def boom():
        raise RuntimeError("redis down")

    monkeypatch.setattr("app.db.redis_client", boom)

    assert await resurface.suggest(3, 7, now=NOW) is None


def _async(value):
    async def _run(*args, **kwargs):
        return value

    return _run


def _async_callable(value):
    async def _call(*args, **kwargs):
        return value

    return _call
