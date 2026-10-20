"""LangGraph 主聊天回合：图结构 + 对照端点契约。

这一层是「把 Java 的 3 节点图搬到 LangGraph」的第一阶段：
图能跑、端点能对照，但**尚未切换流量**（Java 仍走自己的图）。
"""

import pytest
from fastapi.testclient import TestClient

from app.chat_turn import (
    ChatTurnState,
    build_chat_turn_graph,
    route_after_messages,
)
from app.main import app

client = TestClient(app)


def test_graph_compiles_with_three_nodes():
    graph = build_chat_turn_graph()
    assert graph is not None
    nodes = set(graph.get_graph().nodes)
    assert {"assemble_prompt", "assemble_messages", "invoke_model"} <= nodes


def test_route_skips_model_when_invoke_false():
    state: ChatTurnState = {"invoke": False}
    assert route_after_messages(state) != "invoke_model"
    assert route_after_messages(state) == "__end__"


def test_route_enters_model_when_invoke_true():
    assert route_after_messages({"invoke": True}) == "invoke_model"


def test_scene_flags_align_with_java():
    """场景 → 记忆/关系/摘要开关，必须与 Java ChatTurnScene 一致。"""
    from app.chat_turn import _after_base, _include_flags

    assert _after_base("MOMENTS") == "style_only"
    assert _after_base("DIARY") == "style_only"
    assert _after_base("VOICE_CALL") == "time_only"
    assert _after_base("SINGLE") == "full"

    assert _include_flags("VOICE_CALL") == {
        "include_memory": False,
        "include_relationship": False,
        "include_session": False,
    }
    assert _include_flags("SINGLE") == {
        "include_memory": True,
        "include_relationship": True,
        "include_session": True,
    }
    assert _include_flags("GROUP")["include_relationship"] is False


def test_inspect_endpoint_rejects_user_mismatch():
    resp = client.post(
        "/agent/chat-turn/inspect",
        json={"userId": 7, "scene": "SINGLE", "userText": "hi"},
        headers={"X-User-Id": "8"},
    )
    assert resp.status_code == 403


def test_inspect_endpoint_returns_error_shape_on_failure(monkeypatch):
    """组装失败时返回 error 而不是 500（对照端点不应炸调用方）。"""

    async def boom(_payload):
        raise RuntimeError("db down")

    monkeypatch.setattr("app.chat_turn.run_chat_turn", boom)
    resp = client.post(
        "/agent/chat-turn/inspect",
        json={"userId": 7, "scene": "SINGLE", "userText": "hi"},
        headers={"X-User-Id": "7"},
    )
    assert resp.status_code == 200
    assert resp.json() == {"error": "chat_turn_failed"}

# ── 流式图 ──


def test_stream_graph_has_stream_node():
    from app.chat_turn import build_chat_turn_stream_graph

    graph = build_chat_turn_stream_graph()
    nodes = set(graph.get_graph().nodes)
    assert {"assemble_prompt", "assemble_messages", "invoke_model_stream"} <= nodes


@pytest.mark.asyncio
async def test_stream_graph_forwards_frames(monkeypatch):
    """节点内的 SSE 帧必须经 astream(custom) 原样透出。"""
    from app import chat_turn

    frames = [
        'data:{"content":"你"}\n\n',
        'data:{"content":"好"}\n\n',
        "data:[DONE]\n\n",
    ]

    async def fake_events(*_args, **_kwargs):
        for frame in frames:
            yield frame

    monkeypatch.setattr("app.stream_chat.stream_chat_events", fake_events)
    monkeypatch.setattr(chat_turn, "assemble_prompt", _fake_assemble)

    out = []
    async for chunk in chat_turn.run_chat_turn_stream(
        {"user_id": 1, "scene": "SINGLE", "user_text": "hi",
         "history": [], "invoke": True, "expected_language": "zh"}
    ):
        out.append(chunk)
    assert out == frames


async def _fake_assemble(_state):
    return {"system_prompt": "SYS"}


@pytest.mark.asyncio
async def test_stream_endpoint_rejects_user_mismatch():
    resp = client.post(
        "/agent/chat-turn/stream",
        json={"userId": 7, "scene": "SINGLE", "userText": "hi"},
        headers={"X-User-Id": "8"},
    )
    assert resp.status_code == 403