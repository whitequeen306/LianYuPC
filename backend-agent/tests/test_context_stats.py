"""上下文日统计：写不进去不能影响回复，读不出来不能假装是 0。"""

import pytest

from app.context import stats


class _Pipe:
    def __init__(self):
        self.calls = []

    def hincrby(self, key, field, amount):
        self.calls.append(("hincrby", key, field, amount))

    def expire(self, key, ttl):
        self.calls.append(("expire", key, ttl))

    async def execute(self):
        return []


@pytest.mark.asyncio
async def test_record_turn_increments_counts(monkeypatch):
    pipe = _Pipe()

    class FakeRedis:
        def pipeline(self):
            return pipe

    async def fake_client():
        return FakeRedis()

    monkeypatch.setattr(stats, "redis_client", fake_client)
    await stats.record_turn(estimated_tokens=3400, cache_hit=800, cache_miss=200, trimmed=2)

    fields = {call[2]: call[3] for call in pipe.calls if call[0] == "hincrby"}
    assert fields == {"turns": 1, "est_tokens": 3400, "cache_hit": 800, "cache_miss": 200, "trimmed": 2}
    assert ("expire", stats.day_key(), stats.TTL_SECONDS) in pipe.calls


@pytest.mark.asyncio
async def test_redis_failure_is_swallowed(monkeypatch):
    async def boom():
        raise RuntimeError("redis down")

    monkeypatch.setattr(stats, "redis_client", boom)
    await stats.record_turn(estimated_tokens=100)  # 不抛


def test_summarize_computes_rates():
    result = stats.summarize({"turns": 10, "est_tokens": 34000, "cache_hit": 800,
                              "cache_miss": 200, "trimmed": 3})

    assert result["turns"] == 10
    assert result["est_tokens_per_turn"] == 3400.0
    assert result["cache_hit_rate"] == 0.8
    assert result["trimmed_turns"] == 3


def test_summarize_returns_none_without_denominator():
    result = stats.summarize({})

    assert result["turns"] == 0
    assert result["est_tokens_per_turn"] is None
    assert result["cache_hit_rate"] is None


def test_endpoint_returns_today_summary(monkeypatch):
    from fastapi.testclient import TestClient

    from app.main import app

    async def fake_today(now=None):
        return {"turns": 4, "est_tokens_per_turn": 3200.0, "cache_hit_rate": 0.75, "trimmed_turns": 1}

    monkeypatch.setattr(stats, "read_today", fake_today)
    response = TestClient(app).get("/agent/context/stats")

    assert response.status_code == 200
    assert response.json()["cache_hit_rate"] == 0.75
