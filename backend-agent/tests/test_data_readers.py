"""数据块读取测试：只读 SQL/缓存 + 与格式化模块的衔接。"""

import pytest

from app import data_readers as dr


@pytest.fixture
def fake_db(monkeypatch):
    state = {"rows": {}, "redis": {}}

    async def fake_fetch_all(sql, params=()):
        for key, rows in state["rows"].items():
            if key in sql:
                return rows
        return []

    async def fake_cache_get(key, field=None):
        return state["redis"].get(key)

    monkeypatch.setattr(dr, "fetch_all", fake_fetch_all)
    monkeypatch.setattr("app.db.cache_get_json", fake_cache_get)
    return state


# ── 关系 ──


@pytest.mark.asyncio
async def test_load_relationship_returns_raw_fields(fake_db):
    fake_db["rows"]["FROM relationship_state"] = [{
        "phase": "CLOSE_FRIEND", "trust_score": 80, "intimacy_score": 70,
        "security_score": 60, "anticipation_score": 50,
    }]
    fake_db["rows"]["FROM relationship_event"] = [
        {"summary": "一起看流星雨"}, {"summary": "吵过一次架"},
    ]
    data = await dr.load_relationship(7, 3)
    assert data["phase"] == "CLOSE_FRIEND"
    assert data["trust"] == 80
    assert data["events"] == ["一起看流星雨", "吵过一次架"]


@pytest.mark.asyncio
async def test_load_relationship_missing_returns_none(fake_db):
    assert await dr.load_relationship(7, 3) is None


@pytest.mark.asyncio
async def test_load_relationship_guards_null_ids(fake_db):
    assert await dr.load_relationship(0, 3) is None
    assert await dr.load_relationship(7, 0) is None


@pytest.mark.asyncio
async def test_load_relationship_skips_blank_events(fake_db):
    fake_db["rows"]["FROM relationship_state"] = [{
        "phase": "STRANGER", "trust_score": 0, "intimacy_score": 0,
        "security_score": 0, "anticipation_score": 0,
    }]
    fake_db["rows"]["FROM relationship_event"] = [{"summary": ""}, {"summary": "有效事件"}]
    data = await dr.load_relationship(7, 3)
    assert data["events"] == ["有效事件"]


# ── 情绪 ──


@pytest.mark.asyncio
async def test_load_emotion_returns_raw_fields(fake_db):
    fake_db["rows"]["FROM character_state"] = [{
        "current_emotion": "难过", "emotion_intensity": 70,
        "status_text": "今天有点累", "previous_emotion": "开心",
    }]
    data = await dr.load_emotion(3, 7)
    assert data["current_emotion"] == "难过"
    assert data["emotion_intensity"] == 70


@pytest.mark.asyncio
async def test_load_emotion_missing_returns_none(fake_db):
    assert await dr.load_emotion(3, 7) is None


# ── 会话摘要（Redis）──


@pytest.mark.asyncio
async def test_load_session_summary_from_json_object(fake_db):
    fake_db["redis"]["session:summary:42"] = {"text": "用户聊到工作压力"}
    assert await dr.load_session_summary(42) == "用户聊到工作压力"


@pytest.mark.asyncio
async def test_load_session_summary_from_plain_string(fake_db):
    fake_db["redis"]["session:summary:42"] = "纯文本摘要"
    assert await dr.load_session_summary(42) == "纯文本摘要"


@pytest.mark.asyncio
async def test_load_session_summary_missing_or_bad(fake_db):
    assert await dr.load_session_summary(None) is None
    assert await dr.load_session_summary(99) is None
    fake_db["redis"]["session:summary:7"] = {"other": 1}
    assert await dr.load_session_summary(7) is None


# ── 组合：记忆 + 关系 + 摘要 ──


@pytest.mark.asyncio
async def test_load_memory_context_joins_three_blocks(fake_db):
    fake_db["rows"]["FROM memory_meta"] = [
        {"summary": "【长期记忆/姓名】小林", "memory_type": "FACT", "importance": 0.9},
    ]
    fake_db["rows"]["FROM relationship_state"] = [{
        "phase": "CLOSE_FRIEND", "trust_score": 80, "intimacy_score": 70,
        "security_score": 60, "anticipation_score": 50,
    }]
    fake_db["rows"]["FROM relationship_event"] = [{"summary": "一起看流星雨"}]
    fake_db["redis"]["session:summary:42"] = {"text": "之前聊过工作"}

    block = await dr.load_memory_context(7, 3, conversation_id=42)

    assert "[用户画像]" in block
    assert "关系阶段: close-friend" in block
    assert "本次对话 Earlier 摘要" in block
    # 顺序：记忆 → 关系 → 摘要
    assert block.index("[用户画像]") < block.index("关系阶段") < block.index("Earlier 摘要")


@pytest.mark.asyncio
async def test_load_memory_context_returns_none_when_empty(fake_db):
    assert await dr.load_memory_context(7, 3) is None
