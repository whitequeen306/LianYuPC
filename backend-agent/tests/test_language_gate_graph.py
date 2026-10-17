"""图内语言门环测试：验证有界重生成、纠错消息拼接、超限放行。

对应 Java `AiChatService.enforceExpectedLanguage`（while 循环，最多 2 次）。
"""

import httpx
import pytest
import respx

from app.gateway import ModelGateway
from app.graphs.proactive_turn import build_proactive_graph
from app.http_client import outbound_timeout
from app.resilience import reset_breakers, reset_lanes


@pytest.fixture(autouse=True)
def _reset_resilience():
    reset_breakers()
    reset_lanes()
    yield
    reset_breakers()
    reset_lanes()


def make_state(**overrides):
    base = {
        "user_id": 1,
        "scene": "PROACTIVE",
        "persona": "你是一个温柔的角色。",
        "task_block": "",
        "history": [],
        "user_trigger": "（系统触发主动发言，用户未发送新消息。）",
        "text_base_url": "https://fake/v1",
        "text_api_key": "k",
        "text_model": "m",
        "temperature": 0.8,
    }
    base.update(overrides)
    return base


def _client():
    return httpx.AsyncClient(timeout=outbound_timeout())


@pytest.mark.asyncio
@respx.mock
async def test_no_gate_when_expected_language_absent():
    """未指定期望语言 → 不纠错，一次调用即结束。"""
    route = respx.post("https://fake/v1/chat/completions").respond(
        json={"choices": [{"message": {"content": "I miss you"}}]}
    )
    client = _client()
    try:
        graph = build_proactive_graph(ModelGateway(client=client))
        final = await graph.ainvoke(make_state(), config={"recursion_limit": 10})
    finally:
        await client.aclose()
    assert final["assistant_raw"] == "I miss you"
    assert route.call_count == 1


@pytest.mark.asyncio
@respx.mock
async def test_gate_regenerates_once_then_accepts():
    """期望中文但先返回英文 → 一次纠错后返回中文即通过。"""
    responses = [
        {"choices": [{"message": {"content": "I miss you so much tonight"}}]},
        {"choices": [{"message": {"content": "今晚有点想你，早点睡吧"}}]},
    ]
    respx.post("https://fake/v1/chat/completions").mock(
        side_effect=[httpx.Response(200, json=r) for r in responses]
    )
    client = _client()
    try:
        graph = build_proactive_graph(ModelGateway(client=client))
        final = await graph.ainvoke(
            make_state(expected_language="zh"), config={"recursion_limit": 10}
        )
    finally:
        await client.aclose()
    assert final["assistant_raw"] == "今晚有点想你，早点睡吧"
    assert final["language_retries"] == 1
    assert final.get("language_exhausted") is None


@pytest.mark.asyncio
@respx.mock
async def test_gate_gives_up_after_max_retries():
    """连续英文 → 最多 2 次重生成后放行（保留最后一次结果，不无限循环）。"""
    english = {"choices": [{"message": {"content": "I still miss you tonight"}}]}
    respx.post("https://fake/v1/chat/completions").mock(
        side_effect=[httpx.Response(200, json=english) for _ in range(5)]
    )
    client = _client()
    try:
        graph = build_proactive_graph(ModelGateway(client=client))
        final = await graph.ainvoke(
            make_state(expected_language="zh"), config={"recursion_limit": 12}
        )
    finally:
        await client.aclose()
    assert final["language_retries"] == 2
    assert final.get("language_exhausted") is True
    # 1 次初始 + 2 次重生成
    assert respx.calls.call_count == 3


@pytest.mark.asyncio
@respx.mock
async def test_gate_skipped_for_english_expected():
    """红线：永不强制改成英文（期望 en 时直接放行）。"""
    route = respx.post("https://fake/v1/chat/completions").respond(
        json={"choices": [{"message": {"content": "今晚有点想你"}}]}
    )
    client = _client()
    try:
        graph = build_proactive_graph(ModelGateway(client=client))
        final = await graph.ainvoke(
            make_state(expected_language="en"), config={"recursion_limit": 10}
        )
    finally:
        await client.aclose()
    assert route.call_count == 1
    assert final["assistant_raw"] == "今晚有点想你"


@pytest.mark.asyncio
@respx.mock
async def test_correction_messages_appended_on_retry():
    """纠错轮必须带「assistant 原文 + 纠错指令」，对齐 Java appendLanguageCorrectionMessages。"""
    captured: list[dict] = []

    def capture(request):
        import json as _json

        captured.append(_json.loads(request.read()))
        if len(captured) == 1:
            return httpx.Response(200, json={"choices": [{"message": {"content": "I miss you tonight"}}]})
        return httpx.Response(200, json={"choices": [{"message": {"content": "今晚想你"}}]})

    respx.post("https://fake/v1/chat/completions").mock(side_effect=capture)
    client = _client()
    try:
        graph = build_proactive_graph(ModelGateway(client=client))
        await graph.ainvoke(make_state(expected_language="zh"), config={"recursion_limit": 10})
    finally:
        await client.aclose()

    second = captured[1]["messages"]
    user_text = second[-1]["content"]
    assert "I miss you tonight" in user_text          # 原文被带上
    assert "请用简体中文完整重写" in user_text          # 纠错指令逐字存在
