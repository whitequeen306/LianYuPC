"""记忆抽取测试。

两条通道分别验证：
- 规则：槽位归一、最后出现生效、仪式类
- LLM：JSON 解析健壮性（markdown/残缺/类型越界）
- 合并：去重、阈值、每轮上限（对齐 Java applyGates）
"""

import json

import httpx
import pytest
import respx

from app.gateway import ModelGateway
from app.http_client import outbound_timeout
from app.memory import extract as ex
from app.resilience import reset_breakers, reset_lanes

BASE = "https://fake/v1"


@pytest.fixture(autouse=True)
def _reset():
    reset_breakers()
    reset_lanes()
    yield
    reset_breakers()
    reset_lanes()


def _msg(role, content, mid=None):
    return {"id": mid, "role": role, "content": content}


# ── 规则 ──


def test_extracts_name_slot():
    memories = ex.extract_heuristics([_msg("USER", "我叫小林，以后请多指教", 11)])
    summaries = [m.summary for m in memories]
    assert any("小林" in s and "姓名" in s for s in summaries)


def test_last_occurrence_wins_per_slot():
    memories = ex.extract_heuristics([
        _msg("USER", "我叫小林", 1),
        _msg("USER", "我叫阿澈", 2),
    ])
    name_memories = [m for m in memories if "姓名" in m.summary]
    assert len(name_memories) == 1
    assert "阿澈" in name_memories[0].summary
    assert name_memories[0].source_msg_id == 2


def test_extracts_preference_and_dislike():
    memories = ex.extract_heuristics([
        _msg("USER", "我喜欢喝冰美式", 3),
        _msg("USER", "我不喜欢香菜", 4),
    ])
    joined = " ".join(m.summary for m in memories)
    assert "冰美式" in joined
    assert "香菜" in joined


def test_extracts_ritual():
    memories = ex.extract_heuristics([_msg("USER", "以后只给你叫老板", 5)])
    assert any(m.memory_type == "RITUAL" for m in memories)


def test_ignores_assistant_messages():
    memories = ex.extract_heuristics([_msg("ASSISTANT", "我叫小助手", 6)])
    assert memories == []


def test_ignores_blank_content():
    assert ex.extract_heuristics([_msg("USER", "   ", 7)]) == []


# ── 上下文 ──


def test_context_only_user_messages_with_ids():
    ctx = ex.build_context([
        _msg("USER", "你好", 1),
        _msg("ASSISTANT", "你好呀", 2),
        _msg("USER", "我喜欢猫", 3),
    ])
    assert "msgId=1 用户: 你好" in ctx
    assert "msgId=3 用户: 我喜欢猫" in ctx
    assert "你好呀" not in ctx


def test_context_truncates_long_message():
    ctx = ex.build_context([_msg("USER", "长" * 300, 1)])
    assert ctx.endswith("...")
    assert len(ctx) < 300


def test_empty_context_marker():
    assert ex.build_context([_msg("ASSISTANT", "x", 1)]) == "(空)"


# ── LLM 解析 ──


def test_parse_plain_json():
    raw = json.dumps({"memories": [
        {"summary": "【长期记忆/爱好】猫", "memoryType": "FACT", "importance": 0.9, "sourceMsgId": 12}
    ]})
    memories = ex.parse_memories(raw)
    assert len(memories) == 1
    assert memories[0].importance == 0.9
    assert memories[0].source_msg_id == 12


def test_parse_json_wrapped_in_markdown():
    raw = '这是结果：\n```json\n{"memories":[{"summary":"喜欢猫","importance":0.6}]}\n```'
    assert len(ex.parse_memories(raw)) == 1


def test_parse_handles_bad_values():
    raw = json.dumps({"memories": [
        {"summary": "x", "importance": "not-a-number", "sourceMsgId": "abc"},
        {"summary": "", "importance": 0.5},
        "not-an-object",
    ]})
    memories = ex.parse_memories(raw)
    assert len(memories) == 1
    assert memories[0].importance == 0.5
    assert memories[0].source_msg_id is None


def test_parse_invalid_json_returns_empty():
    assert ex.parse_memories("not json at all") == []
    assert ex.parse_memories("") == []


def test_parse_clamps_importance():
    raw = json.dumps({"memories": [{"summary": "x", "importance": 5}]})
    assert ex.parse_memories(raw)[0].importance == 1.0


def test_parse_rejects_unknown_type():
    raw = json.dumps({"memories": [{"summary": "x", "memoryType": "PREFERENCE"}]})
    assert ex.parse_memories(raw)[0].memory_type == "FACT"


# ── 合并与闸门 ──


def test_merge_dedupes_and_sorts_by_importance():
    heuristic = [ex.ExtractedMemory("【长期记忆/姓名】小林", importance=0.8)]
    llm = [
        ex.ExtractedMemory("【长期记忆/姓名】小林", importance=0.8),   # 重复
        ex.ExtractedMemory("用户最近在换工作", memory_type="EVENT", importance=0.9),
    ]
    merged = ex.merge_and_gate(heuristic, llm, importance_threshold=0.5, max_per_turn=3)
    assert len(merged) == 2
    assert merged[0].importance == 0.9   # 降序


def test_gate_drops_low_importance():
    merged = ex.merge_and_gate(
        [], [ex.ExtractedMemory("低价值", importance=0.2)],
        importance_threshold=0.5, max_per_turn=3)
    assert merged == []


def test_gate_caps_per_turn():
    llm = [ex.ExtractedMemory(f"事实{i}", importance=0.9 - i * 0.01) for i in range(6)]
    merged = ex.merge_and_gate([], llm, importance_threshold=0.5, max_per_turn=3)
    assert len(merged) == 3


# ── LLM 端点 ──


def _client():
    return httpx.AsyncClient(timeout=outbound_timeout())


@pytest.mark.asyncio
@respx.mock
async def test_extract_with_llm_calls_model():
    respx.post(f"{BASE}/chat/completions").respond(json={
        "choices": [{"message": {"content": json.dumps(
            {"memories": [{"summary": "喜欢猫", "importance": 0.8}]})}}]
    })
    client = _client()
    try:
        memories = await ex.extract_with_llm(
            ModelGateway(client=client), base_url=BASE, api_key="k", model="m",
            messages=[_msg("USER", "我喜欢猫", 1)])
    finally:
        await client.aclose()
    assert len(memories) == 1


@pytest.mark.asyncio
@respx.mock
async def test_llm_failure_degrades_to_empty():
    respx.post(f"{BASE}/chat/completions").respond(status_code=500)
    client = _client()
    try:
        memories = await ex.extract_with_llm(
            ModelGateway(client=client), base_url=BASE, api_key="k", model="m",
            messages=[_msg("USER", "x", 1)])
    finally:
        await client.aclose()
    assert memories == []


@pytest.mark.asyncio
async def test_extract_all_without_credentials_uses_heuristics_only():
    client = _client()
    try:
        memories = await ex.extract_all(
            ModelGateway(client=client),
            messages=[_msg("USER", "我叫小林，我喜欢喝冰美式", 1)])
    finally:
        await client.aclose()
    assert any("小林" in m.summary for m in memories)
    assert any("冰美式" in m.summary for m in memories)
