"""LangGraph 主动发言子图行为测试：条件边、装配语义、错误折叠。"""

import httpx
import pytest
import respx

from app.config import get_settings
from app.gateway import ModelGateway
from app.graphs.proactive_turn import build_proactive_graph
from app.http_client import outbound_timeout


def make_state(**overrides):
    base = {
        "user_id": 1,
        "character_id": 2,
        "conversation_id": 10,
        "scene": "PROACTIVE",
        "persona": "你是一个温柔的角色。",
        "task_block": "\n\n=== 主动发言任务 ===",
        "history": [
            {"role": "user", "content": "今天好累"},
            {"role": "assistant", "content": "辛苦啦"},
        ],
        "user_trigger": "（系统触发主动发言，用户未发送新消息。）",
        "text_base_url": "https://fake/v1",
        "text_api_key": "k",
        "text_model": "m",
        "temperature": 0.8,
    }
    base.update(overrides)
    return base


@pytest.mark.asyncio
@respx.mock
async def test_graph_happy_path():
    respx.post("https://fake/v1/chat/completions").respond(
        json={"choices": [{"message": {"content": "在忙吗？"}}], "usage": {"prompt_tokens": 9}}
    )
    client = httpx.AsyncClient(timeout=outbound_timeout())
    try:
        graph = build_proactive_graph(ModelGateway(client=client))
        final = await graph.ainvoke(make_state(), config={"recursion_limit": 10})
    finally:
        await client.aclose()
    assert final["assistant_raw"] == "在忙吗？"
    assert final["prompt_tokens"] == 9
    assert "error" not in final


@pytest.mark.asyncio
async def test_graph_skips_without_credentials():
    """无文本凭据 → 条件边走 skip（对齐 Java：no user text model, skipped）。"""
    graph = build_proactive_graph(ModelGateway())
    final = await graph.ainvoke(make_state(text_base_url="", text_api_key=""), config={"recursion_limit": 10})
    assert final["error"] == "no_text_credentials"
    assert "assistant_raw" not in final


@pytest.mark.asyncio
@respx.mock
async def test_graph_assembles_system_with_task_block():
    """system 消息 = persona + task_block；trailing user = trigger；history 折叠进 user 文本。"""
    captured = {}

    def capture(request):
        captured["payload"] = request.read()
        return httpx.Response(200, json={"choices": [{"message": {"content": "ok"}}]})

    respx.post("https://fake/v1/chat/completions").mock(side_effect=capture)
    client = httpx.AsyncClient(timeout=outbound_timeout())
    try:
        graph = build_proactive_graph(ModelGateway(client=client))
        await graph.ainvoke(make_state(), config={"recursion_limit": 10})
    finally:
        await client.aclose()

    import json

    payload = json.loads(captured["payload"])
    messages = payload["messages"]
    assert messages[0]["role"] == "system"
    assert messages[0]["content"].startswith("你是一个温柔的角色。")
    assert messages[0]["content"].endswith("=== 主动发言任务 ===")
    assert messages[-1]["role"] == "user"
    assert "今天好累" in messages[-1]["content"]
    assert payload["temperature"] == 0.8


@pytest.mark.asyncio
@respx.mock
async def test_graph_gateway_error_folded_into_state():
    respx.post("https://fake/v1/chat/completions").respond(status_code=502)
    client = httpx.AsyncClient(timeout=outbound_timeout())
    try:
        graph = build_proactive_graph(ModelGateway(client=client))
        final = await graph.ainvoke(make_state(), config={"recursion_limit": 10})
    finally:
        await client.aclose()
    assert "model gateway http 502" in final["error"]
    assert "assistant_raw" not in final


def test_graph_compiles():
    """图可编译（节点/边/条件边结构正确）。"""
    graph = build_proactive_graph(ModelGateway())
    assert graph is not None
    assert get_settings().proactive_max_tokens > 0
