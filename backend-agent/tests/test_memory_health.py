"""记忆体检：开关快照、聚合结果、异常时的降级。"""

from datetime import datetime
from types import SimpleNamespace

import pytest

from app.memory import health


def _settings(**overrides):
    base = dict(
        scene_card_enabled=True,
        scene_extract_enabled=True,
        memory_recall_v3_enabled=False,
        memory_vector_recall_enabled=False,
        memory_dedupe_similarity_enabled=False,
        memory_dedupe_llm_review_enabled=False,
        memory_shadow_enabled=False,
        memory_consolidate_enabled=True,
        history_trim_enabled=False,
    )
    base.update(overrides)
    return SimpleNamespace(**base)


def health_flags(**overrides):
    base = {
        "scene_card": True,
        "recall_v3": False,
        "vector_recall": False,
        "consolidate": True,
    }
    base.update(overrides)
    return base


def test_flags_mirror_settings(monkeypatch):
    monkeypatch.setattr(health, "get_settings", lambda: _settings(memory_recall_v3_enabled=True))

    assert health.flags()["recall_v3"] is True
    assert health.flags()["history_trim"] is False


def test_build_health_counts_and_timestamps():
    results = {
        "memories_active": [{"n": 84}],
        "outbox_dead": [{"n": 0}],
        "last_decay_at": [{"at": datetime(2026, 11, 6, 3, 30)}],
    }

    report = health.build_health(health_flags(), results, now=datetime(2026, 11, 6, 9, 0))

    assert report["counters"]["memories_active"] == 84
    assert report["timestamps"]["last_decay_at"] == "2026-11-06T03:30:00"
    assert report["warnings"] == []   # 没有 episode 不算警告：v3 还没开


def test_warns_when_dead_outbox_and_v3_without_episodes():
    results = {
        "outbox_dead": [{"n": 2}],
        "outbox_pending": [{"n": 80}],
        "episodes_active": [{"n": 0}],
    }

    report = health.build_health(health_flags(recall_v3=True), results)

    assert any("outbox_dead" in item for item in report["warnings"])
    assert any("outbox_pending" in item for item in report["warnings"])
    assert any("episode" in item for item in report["warnings"])


@pytest.mark.asyncio
async def test_collect_survives_a_failing_query(monkeypatch):
    async def fake_fetch_all(sql, params=()):
        if "memory_episode" in sql:
            raise RuntimeError("table missing")
        return [{"n": 1}]

    monkeypatch.setattr(health, "fetch_all", fake_fetch_all)
    monkeypatch.setattr(health, "get_settings", lambda: _settings())

    report = await health.collect()

    assert report["counters"]["memories_active"] == 1
    assert report["counters"]["episodes_active"] is None   # 单条查询挂了只标 None


def test_collect_gives_up_at_the_total_budget(monkeypatch):
    """MySQL 全挂时不能九条各等 2s（18s），并发 + 总预算才是体检该有的样子。"""

    import asyncio

    async def slow(_sql):
        await asyncio.sleep(5)
        return []

    monkeypatch.setattr(health, "fetch_all", slow)
    monkeypatch.setattr(health, "flags", lambda: health_flags())
    monkeypatch.setattr(health, "HEALTH_TIMEOUT_SECONDS", 0.05)

    snapshot = asyncio.run(health.collect())
    assert snapshot["counters"]["memories_active"] is None
    assert set(snapshot["counters"].values()) == {None}
