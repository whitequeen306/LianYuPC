"""场景关闭链路：闲置判定、 transcript、抽取解析、结算编排。"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
from types import SimpleNamespace

import pytest

from app.memory import scene_close
from app.memory.extract_scene import (  # noqa: F401
    MAX_EPISODES,
    SceneEpisode,
    SceneResult,
    build_transcript,
    parse_episodes,
)
from app.context import scene_card
from app.memory.scene_close import close_pending_scene, load_scene_messages

NOW = datetime(2026, 11, 6, 12, 0, tzinfo=timezone.utc)


def _card(**overrides):
    card = scene_card.new_card(9, NOW)
    card.update(overrides)
    return card


# ── 闲置判定 ───────────────────────────────────────────────────────────────

def test_stale_after_45_minutes():
    fresh = _card(updatedAt=(NOW - timedelta(minutes=10)).isoformat())
    old = _card(updatedAt=(NOW - timedelta(minutes=46)).isoformat())
    assert scene_card.is_stale(fresh, NOW) is False
    assert scene_card.is_stale(old, NOW) is True
    assert scene_card.is_stale(None, NOW) is False


def test_stale_tolerates_missing_or_bad_timestamp():
    assert scene_card.is_stale({"updatedAt": "not-a-date"}, NOW) is False
    assert scene_card.is_stale({}, NOW) is False


def test_card_keeps_started_at_when_updated():
    card = _card(startedAt=(NOW - timedelta(minutes=50)).isoformat())
    card = scene_card.update_card(card, user_text="今天加班到很晚", now=NOW)
    assert card["startedAt"] == (NOW - timedelta(minutes=50)).isoformat()
    # 新卡两段时间一致，关闭时区间不会退化成空。
    assert scene_card.new_card(3, NOW)["startedAt"] == scene_card.new_card(3, NOW)["updatedAt"]


def test_normalize_backfills_started_at_from_updated_at():
    raw = {"conversationId": 4, "updatedAt": "2026-11-06T03:00:00+00:00", "topic": "加班"}
    card = scene_card.normalize_card(raw, 4)
    assert card["startedAt"] == raw["updatedAt"]


# ── transcript 与解析 ─────────────────────────────────────────────────────

def test_transcript_keeps_roles_and_drops_system():
    text = build_transcript([
        {"role": "SYSTEM", "content": "规则"},
        {"role": "USER", "content": "今天  加班到很晚", "createdAt": "2026-11-06 21:10"},
        {"role": "ASSISTANT", "content": "辛苦啦"},
    ])
    assert "系统" not in text and "规则" not in text
    assert "[2026-11-06 21:10] 用户: 今天 加班到很晚" in text
    assert "角色: 辛苦啦" in text


def test_parse_episodes_requires_quote_and_clamps():
    payload = """```json
    {"episodes":[
      {"summary":"她加班到很晚","quote":"今天加班到很晚，好累","eventTime":"2026-11-06T21:10:00+08:00","valence":-3,"arousal":2,"openLoop":"明天还要早起"},
      {"summary":"没有原话","quote":""},
      {"summary":"她说怕打雷","quote":"打雷了我睡不着","valence":-0.6,"arousal":0.9}
    ]}
    ```"""
    episodes = parse_episodes(payload)
    assert len(episodes) == 2
    first = episodes[0]
    assert first.valence == -1.0 and first.arousal == 1.0
    assert first.event_time is not None and first.open_loop == "明天还要早起"
    assert episodes[1].summary == "她说怕打雷"


def test_parse_episodes_caps_and_survives_garbage():
    nodes = ",".join(
        '{"summary":"s%d","quote":"q%d"}' % (index, index) for index in range(MAX_EPISODES + 3)
    )
    assert len(parse_episodes('{"episodes":[%s]}' % nodes)) == MAX_EPISODES
    assert parse_episodes("not json at all") == []
    assert parse_episodes('{"episodes": "oops"}') == []


# ── 结算编排 ─────────────────────────────────────────────────────────────

@pytest.fixture()
def wired(monkeypatch):
    state = {"inserted": [], "pending": None, "messages": []}

    async def take_pending(conversation_id):
        return state["pending"]

    async def load_messages(conversation_id, started_at, updated_at):
        return state["messages"]

    async def extract(gateway, **kwargs):
        return SceneResult(episodes=state["episodes"], impression=state.get("impression"))

    async def insert(**kwargs):
        state["inserted"].append(kwargs)
        return 100 + len(state["inserted"])

    monkeypatch.setattr(scene_close, "take_pending", take_pending)
    monkeypatch.setattr(scene_close, "load_scene_messages", load_messages)
    monkeypatch.setattr(scene_close, "extract_scene", extract)
    monkeypatch.setattr(scene_close, "insert_episode", insert)

    async def insert_reflection(**kwargs):
        state.setdefault("reflections", []).append(kwargs)
        return 1

    monkeypatch.setattr(scene_close, "insert_reflection", insert_reflection)
    monkeypatch.setattr(
        scene_close, "get_settings", lambda: SimpleNamespace(scene_extract_enabled=True)
    )
    return state


def _messages(count=6):
    return [
        {"id": index, "role": "USER" if index % 2 else "ASSISTANT", "content": f"消息{index}"}
        for index in range(1, count + 1)
    ]


@pytest.mark.asyncio
async def test_close_writes_each_episode(wired):
    wired["pending"] = _card(updatedAt=NOW.isoformat())
    wired["messages"] = _messages()
    wired["episodes"] = [
        SceneEpisode(summary="她加班到很晚", quote="消息1", valence=-0.4, arousal=0.6),
        SceneEpisode(summary="她说怕打雷", quote="消息3"),
    ]
    created = await close_pending_scene(
        9, 7, 3, base_url="https://x", api_key="k", model="m", gateway=object(),
    )
    assert created == 2
    assert [row["summary"] for row in wired["inserted"]] == ["她加班到很晚", "她说怕打雷"]
    assert wired["inserted"][0]["source_msg_ids"] == [1]
    # 没给 eventTime 的 episode 落在场景关闭时间，不留空值。
    assert wired["inserted"][1]["event_time"] == NOW


@pytest.mark.asyncio
async def test_close_skips_short_or_missing_scene(wired):
    wired["pending"] = None
    assert await close_pending_scene(9, 7, 3, base_url="u", api_key="k", model="m") == 0

    wired["pending"] = _card()
    wired["messages"] = _messages(2)
    wired["episodes"] = [SceneEpisode(summary="太短", quote="消息1")]
    assert await close_pending_scene(9, 7, 3, base_url="u", api_key="k", model="m") == 0


@pytest.mark.asyncio
async def test_close_requires_credentials(wired):
    wired["pending"] = _card()
    assert await close_pending_scene(9, 7, 3, base_url=None, api_key="k", model="m") == 0


@pytest.mark.asyncio
async def test_close_honors_switch(monkeypatch, wired):
    monkeypatch.setattr(
        scene_close, "get_settings", lambda: SimpleNamespace(scene_extract_enabled=False)
    )
    wired["pending"] = _card()
    assert await close_pending_scene(9, 7, 3, base_url="u", api_key="k", model="m") == 0


@pytest.mark.asyncio
async def test_close_degrades_when_message_query_fails(wired, monkeypatch):
    wired["pending"] = _card()

    async def boom(*args, **kwargs):
        raise RuntimeError("db down")

    monkeypatch.setattr(scene_close, "load_scene_messages", boom)
    assert await close_pending_scene(9, 7, 3, base_url="u", api_key="k", model="m") == 0


@pytest.mark.asyncio
async def test_load_messages_uses_window(monkeypatch):
    captured = {}

    async def fake_fetch_all(sql, params=()):
        captured["sql"], captured["params"] = sql, params
        return [{"id": 1, "role": "USER", "content": "hi"}]

    monkeypatch.setattr(scene_close, "fetch_all", fake_fetch_all)
    rows = await load_scene_messages(9, NOW - timedelta(hours=1), NOW)
    assert len(rows) == 1
    assert "created_at >= %s" in captured["sql"]
    assert captured["params"][0] == 9


@pytest.mark.asyncio
async def test_load_messages_falls_back_to_recent(monkeypatch):
    captured = {}

    async def fake_fetch_all(sql, params=()):
        captured["sql"] = sql
        return [{"id": 2, "role": "USER", "content": "later"}, {"id": 1, "role": "USER", "content": "earlier"}]

    monkeypatch.setattr(scene_close, "fetch_all", fake_fetch_all)
    rows = await load_scene_messages(9, None, None)
    assert "ORDER BY seq DESC" in captured["sql"]
    # 倒序取最近，返回前要翻回时间正序（否则 transcript 是倒着读的）。
    assert [row["id"] for row in rows] == [1, 2]


@pytest.mark.asyncio
async def test_close_writes_one_reflection_per_scene(wired):
    wired["pending"] = _card(updatedAt=NOW.isoformat())
    wired["messages"] = _messages()
    wired["episodes"] = [SceneEpisode(summary="她加班到很晚", quote="消息1")]
    wired["impression"] = "她嘴上说没事，其实很累"

    created = await close_pending_scene(
        9, 7, 3, base_url="https://x", api_key="k", model="m", gateway=object(),
    )

    assert created == 2                     # 1 条 episode + 1 条 impression
    assert wired["reflections"][0]["impression"] == "她嘴上说没事，其实很累"
    assert wired["reflections"][0]["source_msg_ids"] == [1, 2, 3, 4, 5, 6]


@pytest.mark.asyncio
async def test_close_skips_reflection_without_impression(wired):
    wired["pending"] = _card(updatedAt=NOW.isoformat())
    wired["messages"] = _messages()
    wired["episodes"] = [SceneEpisode(summary="她加班到很晚", quote="消息1")]

    created = await close_pending_scene(
        9, 7, 3, base_url="https://x", api_key="k", model="m", gateway=object(),
    )

    assert created == 1
    assert not wired.get("reflections")
