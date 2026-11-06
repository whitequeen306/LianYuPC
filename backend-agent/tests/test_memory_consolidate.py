"""周记：同一周同一角色只写一条，重复跑不能叠。"""

from datetime import datetime

import pytest

from app.memory import consolidate as c


class _Cursor:
    def __init__(self, exists=False):
        self.exists = exists
        self.executed = []

    async def execute(self, sql, params=()):
        self.executed.append((sql, params))

    async def fetchone(self):
        return {"id": 1} if self.exists else None


class _Tx:
    def __init__(self, cursor):
        self.cursor = cursor

    async def __aenter__(self):
        return self.cursor

    async def __aexit__(self, *exc):
        return False


def test_week_start_is_monday_midnight():
    start = c.week_start(datetime(2026, 11, 6, 15, 30))  # 周五
    assert start == datetime(2026, 11, 2)


def test_digest_orders_by_time_but_picks_by_weight():
    start = datetime(2026, 11, 2)
    episodes = [
        {"summary": "她说想养一只猫", "event_time": datetime(2026, 11, 3, 10), "arousal": 0.2, "valence": 0.4},
        {"summary": "她今天被领导当众批评，很委屈", "event_time": datetime(2026, 11, 4, 20), "arousal": 0.9, "valence": -0.6},
        {"summary": "一起吃了我做的汤", "event_time": datetime(2026, 11, 5, 19), "arousal": 0.5, "valence": 0.7},
        {"summary": "随口聊了天气", "event_time": datetime(2026, 11, 2, 9), "arousal": 0.1, "valence": 0.0},
        {"summary": "她说周末想去看海", "event_time": datetime(2026, 11, 6, 8), "arousal": 0.8, "valence": 0.6},
    ]

    digest = c.build_digest(episodes, start, datetime(2026, 11, 6, 12))

    assert digest.startswith("【周记】11-02～至今：")
    assert "被领导当众批评" in digest and "想去看海" in digest
    assert "随口聊了天气" not in digest
    assert digest.index("被领导当众批评") < digest.index("想去看海")


def test_digest_is_capped_and_survives_empty_sides():
    start = datetime(2026, 11, 2)
    long = [{"summary": "啊" * 200, "event_time": datetime(2026, 11, 3), "arousal": 1, "valence": 1}]
    assert len(c.build_digest(long, start, datetime(2026, 11, 3))) <= c.MAX_DIGEST_CHARS
    assert c.build_digest([], start, datetime(2026, 11, 3)).startswith("【周记】")


def test_digest_hash_is_stable_per_week_and_owner():
    start = c.week_start(datetime(2026, 11, 6))
    assert c.digest_hash(7, 3, start) == c.digest_hash(7, 3, start)
    assert c.digest_hash(7, 3, start) != c.digest_hash(8, 3, start)
    assert c.digest_hash(7, 3, start) != c.digest_hash(7, 3, start + c.timedelta(days=7))


@pytest.mark.asyncio
async def test_consolidate_writes_only_full_weeks(monkeypatch):
    rows = [
        {"id": 1, "user_id": 7, "character_id": 3, "summary": "搬家", "event_time": datetime(2026, 11, 3), "arousal": 0.8, "valence": 0.4},
        {"id": 2, "user_id": 7, "character_id": 3, "summary": "收拾到半夜", "event_time": datetime(2026, 11, 4), "arousal": 0.6, "valence": -0.2},
        {"id": 3, "user_id": 7, "character_id": 3, "summary": "新家第一顿饭", "event_time": datetime(2026, 11, 5), "arousal": 0.5, "valence": 0.8},
        {"id": 4, "user_id": 9, "character_id": 3, "summary": "只聊了一句", "event_time": datetime(2026, 11, 5), "arousal": 0.1, "valence": 0.1},
    ]

    async def fetch_all(sql, params=()):
        return rows

    inserted = []
    cursor = _Cursor()

    async def insert_returning_id(cur, sql, params):
        inserted.append(params)
        return 5

    async def already_written(cur, content_hash):
        return False

    monkeypatch.setattr(c, "load_week_episodes", fetch_all)
    monkeypatch.setattr(c, "transaction", lambda: _Tx(cursor))
    monkeypatch.setattr(c, "insert_returning_id", insert_returning_id)
    monkeypatch.setattr(c, "_already_written", already_written)

    assert await c.consolidate(datetime(2026, 11, 6, 12)) == 1
    assert len(inserted) == 1
    assert inserted[0][0] == 3 and inserted[0][1] == 7
    assert "搬家" in inserted[0][2]


@pytest.mark.asyncio
async def test_consolidate_skips_a_week_already_written(monkeypatch):
    rows = [
        {"id": i, "user_id": 7, "character_id": 3, "summary": f"事{i}",
         "event_time": datetime(2026, 11, 3 + i), "arousal": 0.5, "valence": 0.2}
        for i in range(3)
    ]

    async def fetch_all(sql, params=()):
        return rows

    async def already_written(cur, content_hash):
        return True

    monkeypatch.setattr(c, "load_week_episodes", fetch_all)
    monkeypatch.setattr(c, "transaction", lambda: _Tx(_Cursor(exists=True)))
    monkeypatch.setattr(c, "_already_written", already_written)

    assert await c.consolidate(datetime(2026, 11, 6, 12)) == 0


def test_endpoint_reports_written_count(monkeypatch):
    from fastapi.testclient import TestClient

    from app.main import app
    from app.memory import consolidate as consolidate_mod

    async def fake():
        return 3

    monkeypatch.setattr(consolidate_mod, "consolidate", fake)
    response = TestClient(app).post("/agent/memory/consolidate")
    assert response.status_code == 200
    assert response.json()["written"] == 3
