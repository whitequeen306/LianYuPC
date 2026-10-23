"""只读数据层 + 两个新工具（memory_search / get_my_recent_life）测试。

数据层用 monkeypatch 打桩（不依赖真实 MySQL/Redis），重点验证：
- SELECT 守卫拒绝写语句
- memory_search 的缓存命中 / LIKE 提升合并 / 降级
- recent_life 的条数裁剪、语种句式、缓存
"""

import hashlib
import json
from datetime import datetime, timedelta

import pytest

from app import db
from app.language_gate import Language
from app.tools import supported_tool_names
from app.tools.memory_search_tool import hash_query, pick_like_keyword, search_semantic
from app.tools.recent_activity_tool import (
    ACTIVITY_KEY_PREFIX,
    build_recent_activity,
    format_for_prompt,
)


# ── 只读守卫 ──


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "sql",
    [
        "UPDATE memory_meta SET summary='x'",
        "DELETE FROM memory_meta",
        "INSERT INTO memory_meta (summary) VALUES ('x')",
        "DROP TABLE memory_meta",
        "  update memory_meta set x=1",
    ],
)
async def test_read_only_guard_rejects_writes(sql):
    with pytest.raises(db.ReadOnlyViolation):
        await db.fetch_all(sql)


def test_hash_query_normalizes_and_matches_java_contract():
    """对齐 Java hashQuery：trim + 压缩空白 + 小写 + SHA-256 hex。"""
    expected = hashlib.sha256("用户喜欢的食物".encode()).hexdigest()
    assert hash_query("  用户喜欢的食物  ") == expected
    assert hash_query("用户   喜欢的食物") == hashlib.sha256("用户 喜欢的食物".encode()).hexdigest()


def test_pick_like_keyword_matches_java():
    """Java：长度 <4 → null；否则取首个 2–12 长度 token；无合适 token 则取前 12 字符。"""
    assert pick_like_keyword("ab") is None                       # 太短
    assert pick_like_keyword(None) is None
    assert pick_like_keyword("今天 工作 很累") == "今天"           # 首个 2-12 字 token
    twelve = "a" * 12                                            # 恰好 12 长度
    assert pick_like_keyword(twelve) == twelve
    longer = "一二三四五六七八九十十一十二十三十四"                    # 无空白且 >12 → 取前 12
    assert len(longer) > 12
    assert pick_like_keyword(longer) == longer[:12]


# ── memory_search ──


@pytest.fixture
def fake_memory(monkeypatch):
    """桩：Redis 缓存 + MySQL 查询。"""
    store: dict = {}

    async def fake_cache_get(key, field=None):
        return store.get((key, field))

    async def fake_cache_put(key, value, ttl, field=None):
        store[(key, field)] = value

    reminder = {"rows": [], "like_rows": []}

    async def fake_fetch_all(sql, params=()):
        if "LIKE" in sql:
            return reminder["like_rows"]
        return reminder["rows"]

    monkeypatch.setattr("app.tools.memory_search_tool.cache_get_json", fake_cache_get)
    monkeypatch.setattr("app.tools.memory_search_tool.cache_put_json", fake_cache_put)
    monkeypatch.setattr("app.tools.memory_search_tool.fetch_all", fake_fetch_all)
    return store, reminder


@pytest.mark.asyncio
async def test_memory_search_returns_dash_lines(fake_memory):
    store, rows = fake_memory
    rows["rows"] = [
        {"id": 1, "summary": "用户喜欢喝冰美式"},
        {"id": 2, "summary": "用户最近在换工作"},
    ]
    lines = await search_semantic(7, 3, "喜欢喝什么")
    assert lines == ["- 用户喜欢喝冰美式", "- 用户最近在换工作"]
    # 结果写回语义缓存
    assert any(k[0] == "memory:semantic:7:3" for k in store)


@pytest.mark.asyncio
async def test_memory_search_second_call_hits_cache(fake_memory):
    store, rows = fake_memory
    rows["rows"] = [{"id": 1, "summary": "用户喜欢猫"}]
    first = await search_semantic(7, 3, "猫")
    rows["rows"] = []  # 第二次即使 DB 为空也应命中缓存
    second = await search_semantic(7, 3, "猫")
    assert first == second == ["- 用户喜欢猫"]


@pytest.mark.asyncio
async def test_memory_search_merges_like_boost_without_duplicates(fake_memory):
    store, rows = fake_memory
    rows["rows"] = [{"id": 1, "summary": "用户喜欢猫"}, {"id": 2, "summary": "用户在学吉他"}]
    rows["like_rows"] = [{"id": 1, "summary": "用户喜欢猫"}, {"id": 3, "summary": "猫叫咪咪"}]
    # query 需 >= 4 字才会做 LIKE 提升（对齐 Java pickLikeKeyword 的 text.length() < 4 门槛）
    lines = await search_semantic(7, 3, "我记得那只猫")
    assert lines == ["- 用户喜欢猫", "- 用户在学吉他", "- 猫叫咪咪"]


@pytest.mark.asyncio
async def test_memory_search_short_query_skips_like_boost(fake_memory):
    """短查询（<4 字）不做 LIKE 提升——与 Java pickLikeKeyword 一致。"""
    store, rows = fake_memory
    rows["rows"] = [{"id": 1, "summary": "用户喜欢猫"}]
    rows["like_rows"] = [{"id": 3, "summary": "猫叫咪咪"}]
    lines = await search_semantic(7, 3, "猫")
    assert lines == ["- 用户喜欢猫"]


@pytest.mark.asyncio
async def test_memory_search_degrades_on_db_error(monkeypatch):
    async def boom(sql, params=()):
        raise RuntimeError("db down")

    async def no_cache(key, field=None):
        return None

    monkeypatch.setattr("app.tools.memory_search_tool.cache_get_json", no_cache)
    monkeypatch.setattr("app.tools.memory_search_tool.fetch_all", boom)
    assert await search_semantic(7, 3, "随便") == []


@pytest.mark.asyncio
async def test_memory_search_blank_query_returns_empty(fake_memory):
    assert await search_semantic(7, 3, "   ") == []


# ── get_my_recent_life ──


def _now() -> datetime:
    return datetime.now()


@pytest.fixture
def fake_activity(monkeypatch):
    dataset = {"diary": [], "post": [], "comment": [], "posts_by_id": [], "chars": []}

    async def fake_fetch_all(sql, params=()):
        if "FROM character_diary" in sql:
            return dataset["diary"]
        if "FROM moments_comment" in sql:
            return dataset["comment"]
        if "FROM moments_post WHERE id IN" in sql:
            return dataset["posts_by_id"]
        if "FROM `character`" in sql:
            return dataset["chars"]
        if "FROM moments_post" in sql:
            return dataset["post"]
        return []

    store: dict = {}

    async def fake_cache_get(key, field=None):
        return store.get(key)

    async def fake_cache_put(key, value, ttl, field=None):
        store[key] = value

    monkeypatch.setattr("app.tools.recent_activity_tool.fetch_all", fake_fetch_all)
    monkeypatch.setattr("app.tools.recent_activity_tool.cache_get_json", fake_cache_get)
    monkeypatch.setattr("app.tools.recent_activity_tool.cache_put_json", fake_cache_put)
    return dataset, store


@pytest.mark.asyncio
async def test_recent_life_empty_returns_empty_string(fake_activity):
    assert await build_recent_activity(7, 3, "zh") == ""


@pytest.mark.asyncio
async def test_recent_life_formats_and_caps_lines(fake_activity):
    data, _ = fake_activity
    base = _now() - timedelta(hours=1)
    data["diary"] = [{"title": "雨天", "content": "今天下雨了，很想你", "created_at": base}]
    data["post"] = [{"content": "今天心情不错", "created_at": base - timedelta(hours=1)}]
    data["comment"] = []
    block = await build_recent_activity(7, 3, "zh")
    assert "=== 你最近的生活动态" in block
    assert "写了日记《雨天》——今天下雨了，很想你" in block
    assert "发了朋友圈「今天心情不错」" in block
    assert block.endswith("请勿逐条复述以上动态，仅作背景感知。")


@pytest.mark.asyncio
async def test_recent_life_supports_four_languages(fake_activity):
    data, _ = fake_activity
    data["post"] = [{"content": "coffee", "created_at": _now()}]
    assert "Posted on moments" in await build_recent_activity(7, 3, "en")
    assert "モーメンツに投稿" in await build_recent_activity(7, 3, "ja")
    assert "发了朋友圈" in await build_recent_activity(7, 3, "zh-TW")


@pytest.mark.asyncio
async def test_recent_life_uses_cache(fake_activity):
    data, store = fake_activity
    data["post"] = [{"content": "hi", "created_at": _now()}]
    first = await format_for_prompt(7, 3, "zh")
    data["post"] = []  # 缓存命中后不再查库
    second = await format_for_prompt(7, 3, "zh")
    assert first == second
    assert store.get(f"{ACTIVITY_KEY_PREFIX}7:3") == first


@pytest.mark.asyncio
async def test_recent_life_resolves_peer_name_for_comment(fake_activity):
    data, _ = fake_activity
    data["comment"] = [{"post_id": 99, "content": "哈哈是啊", "created_at": _now()}]
    data["posts_by_id"] = [{"id": 99, "author_type": "CHARACTER", "character_id": 5}]
    data["chars"] = [{"id": 5, "name": "小夜"}]
    block = await build_recent_activity(7, 3, "zh")
    assert "在 小夜 的动态下评论了「哈哈是啊」" in block


# ── 能力清单已扩容 ──


def test_supported_tools_now_include_memory_and_recent_life():
    supported = supported_tool_names()
    assert {"get_current_time", "get_weather", "memory_search", "get_my_recent_life"} <= supported
    assert any(name.startswith("desk_") for name in supported)
