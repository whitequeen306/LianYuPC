"""流式工具回退：模型想调工具时不能让这一轮变成空回复。

背景（真实故障）：单聊问「现在几点了」，`gateway._stream_once` 只 yield
`delta.content`，模型选择调工具时流里没有任何文本 → 收尾落到
`对方没有回复内容，请重试`，用户拿不到答案，而且这一轮也不落库。

修法：流完仍无文本且本轮挂了工具 → 退化成有界工具循环 (`run_tool_loop`)，
执行工具后把最终文本作为单帧下发。下面锁住四条边界。
"""

from __future__ import annotations

import pytest

from app.stream_chat import DONE_FRAME, sse_content, stream_chat_events
from app.tool_loop import ToolLoopResult

BASE = dict(
    base_url="https://api.example.com/v1",
    api_key="sk-test",
    model="deepseek-test",
    messages=[{"role": "user", "content": "现在几点了？"}],
    temperature=0.8,
    max_tokens=128,
)

TOOL_CONTEXT = {"_user_id": 7, "_character_id": 9, "_language": "zh"}


class FakeGateway:
    """只实现流式：`deltas` 为空即模拟「模型只调工具、没有文本」。"""

    def __init__(self, deltas: list[str] | None = None):
        self.deltas = list(deltas or [])
        self.calls: list[dict] = []

    async def stream_text(self, **kwargs):
        self.calls.append(kwargs)
        for delta in self.deltas:
            yield delta


async def _collect(**overrides) -> list[str]:
    frames = []
    async for frame in stream_chat_events(**{**BASE, **overrides}):
        frames.append(frame)
    return frames


def _patch_loop(monkeypatch, *, content: str, capture: dict | None = None):
    async def fake_run_tool_loop(_gateway, **kwargs):
        if capture is not None:
            capture.update(kwargs)
        return ToolLoopResult(content=content, rounds=1, tool_names_called=["get_current_time"])

    monkeypatch.setattr("app.tool_loop.run_tool_loop", fake_run_tool_loop)


@pytest.mark.asyncio
async def test_empty_stream_falls_back_to_tool_loop(monkeypatch):
    captured: dict = {}
    _patch_loop(monkeypatch, content="现在是晚上八点二十。", capture=captured)

    frames = await _collect(gateway=FakeGateway([]), tool_names=["get_current_time"],
                            tool_context=TOOL_CONTEXT,
                            tools=[{"type": "function", "function": {"name": "get_current_time"}}])

    assert sse_content("现在是晚上八点二十。") in frames
    assert frames[-1] == DONE_FRAME
    assert not any('"error"' in f for f in frames), "回退成功就不该再报空回复"
    assert captured["tool_names"] == ["get_current_time"]
    assert captured["tool_context"] == TOOL_CONTEXT


@pytest.mark.asyncio
async def test_no_tools_keeps_original_empty_error(monkeypatch):
    called = False

    async def fail_if_called(*args, **kwargs):  # pragma: no cover - 不该被调用
        nonlocal called
        called = True
        raise AssertionError("没挂工具时不应触发工具回退")

    monkeypatch.setattr("app.tool_loop.run_tool_loop", fail_if_called)

    frames = await _collect(gateway=FakeGateway([]))

    assert any("对方没有回复内容" in f for f in frames)
    assert frames[-1] == DONE_FRAME
    assert called is False


@pytest.mark.asyncio
async def test_streamed_text_skips_tool_fallback(monkeypatch):
    async def fail_if_called(*args, **kwargs):  # pragma: no cover - 不该被调用
        raise AssertionError("已经流到文本时不应再跑工具循环")

    monkeypatch.setattr("app.tool_loop.run_tool_loop", fail_if_called)

    frames = await _collect(gateway=FakeGateway(["在", "呢"]), tool_names=["get_current_time"],
                            tool_context=TOOL_CONTEXT)

    assert sse_content("在") in frames
    assert sse_content("呢") in frames
    assert frames[-1] == DONE_FRAME


@pytest.mark.asyncio
async def test_fallback_with_empty_result_still_errors(monkeypatch):
    _patch_loop(monkeypatch, content="")

    frames = await _collect(gateway=FakeGateway([]), tool_names=["get_current_time"],
                            tool_context=TOOL_CONTEXT)

    assert any("对方没有回复内容" in f for f in frames)
    assert frames[-1] == DONE_FRAME


# ─────────────────────────── 阻塞节点也必须挂工具 ───────────────────────────


@pytest.mark.asyncio
async def test_invoke_model_uses_tool_loop_when_scene_enables_tools(monkeypatch):
    """只给提示词、不给 schema 时模型会把 DSML 调用标记当正文吐出来（实测落库）。"""
    from app.chat_turn import invoke_model

    captured: dict = {}

    async def fake_run_tool_loop(_gateway, **kwargs):
        captured.update(kwargs)
        return ToolLoopResult(content="现在是晚上八点。", rounds=1,
                              tool_names_called=["get_current_time"],
                              prompt_tokens=120, completion_tokens=18)

    monkeypatch.setattr("app.tool_loop.run_tool_loop", fake_run_tool_loop)

    out = await invoke_model({
        "user_id": 7, "character_id": 9, "scene": "SINGLE",
        "messages": [{"role": "user", "content": "现在几点"}],
        "base_url": "https://api.example.com/v1", "api_key": "sk", "model": "m",
    })

    assert out["content"] == "现在是晚上八点。"
    assert out["prompt_tokens"] == 120
    assert out["completion_tokens"] == 18
    assert "get_current_time" in captured["tool_names"]
    assert captured["tool_context"]["_user_id"] == 7
    assert captured["tool_context"]["_character_id"] == 9


@pytest.mark.asyncio
async def test_invoke_model_skips_tools_for_scene_without_tools(monkeypatch):
    """MOMENTS 不挂工具 → 保持单次调用，不引入工具循环开销。"""
    from types import SimpleNamespace

    from app import chat_turn
    from app.gateway import GatewayResult

    calls: list[dict] = []

    class FakeModelGateway:
        async def chat_messages(self, **kwargs):
            calls.append(kwargs)
            return GatewayResult(content="文案", prompt_tokens=10, completion_tokens=5)

    monkeypatch.setattr(chat_turn, "get_gateway", lambda: FakeModelGateway())

    async def fail_if_called(*args, **kwargs):  # pragma: no cover
        raise AssertionError("该场景不该跑工具循环")

    monkeypatch.setattr("app.tool_loop.run_tool_loop", fail_if_called)

    out = await chat_turn.invoke_model({
        "user_id": 7, "character_id": 9, "scene": "MOMENTS",
        "messages": [{"role": "user", "content": "写条朋友圈"}],
        "base_url": "u", "api_key": "k", "model": "m",
    })

    assert out["content"] == "文案"
    assert len(calls) == 1


# ─────────────────────────── 流式工具轮次（逐 token 下发） ───────────────────────────


class ScriptedGateway:
    """按轮次脚本产出：每轮回放 deltas，并把 tool_calls 塞进 tool_sink。"""

    def __init__(self, rounds: list[dict]):
        self.script = list(rounds)
        self.calls: list[dict] = []

    async def stream_text(self, **kwargs):
        index = len(self.calls)
        self.calls.append(kwargs)
        step = self.script[index] if index < len(self.script) else {"deltas": []}
        sink = kwargs.get("tool_sink")
        if sink is not None:
            sink.extend(step.get("tool_calls") or [])
        for delta in step.get("deltas") or []:
            yield delta


def _patch_specs(monkeypatch, handler):
    from types import SimpleNamespace

    spec = SimpleNamespace(name="get_current_time", handler=handler)
    monkeypatch.setattr("app.tool_loop.resolve_tools", lambda names: [spec])
    return spec


@pytest.mark.asyncio
async def test_streaming_tool_round_executes_then_streams_answer(monkeypatch):
    """第一轮只有 tool_calls、第二轮才出正文 —— 正文仍要逐 token 下发。"""
    seen: list[dict] = []

    async def handler(arguments):
        seen.append(arguments)
        return "现在是晚上八点四十。"

    _patch_specs(monkeypatch, handler)
    gateway = ScriptedGateway([
        {"deltas": [], "tool_calls": [{
            "id": "call_1", "type": "function",
            "function": {"name": "get_current_time", "arguments": '{"city": "郑州"}'},
        }]},
        {"deltas": ["现在是", "晚上八点四十。"]},
    ])

    frames = await _collect(gateway=gateway, tool_names=["get_current_time"],
                            tool_context=TOOL_CONTEXT)

    assert any(sse_content("现在是") in f for f in frames), "第二轮正文要增量下发"
    assert frames[-1] == DONE_FRAME
    assert not any('"error"' in f for f in frames)
    # 工具真的被执行，且带上调用方注入的上下文
    assert seen[0]["city"] == "郑州"
    assert seen[0]["_user_id"] == 7
    # 第二轮请求把 assistant(tool_calls) + tool 结果回灌了
    second = gateway.calls[1]["messages"]
    assert second[-2]["role"] == "assistant" and second[-2]["tool_calls"]
    assert second[-1]["role"] == "tool" and second[-1]["content"] == "现在是晚上八点四十。"
    assert second[-1]["tool_call_id"] == "call_1"


@pytest.mark.asyncio
async def test_streaming_tool_round_is_bounded(monkeypatch):
    """模型每次都想调工具时，循环必须被 max_tool_rounds 截断，不能无限跑。"""

    async def handler(arguments):
        return "ok"

    _patch_specs(monkeypatch, handler)

    async def empty_fallback(*args, **kwargs):
        return ToolLoopResult(content="", rounds=0)

    monkeypatch.setattr("app.tool_loop.run_tool_loop", empty_fallback)

    endless = {"deltas": [], "tool_calls": [{
        "id": "call_x", "type": "function",
        "function": {"name": "get_current_time", "arguments": "{}"},
    }]}
    gateway = ScriptedGateway([endless] * 10)

    frames = await _collect(gateway=gateway, tool_names=["get_current_time"],
                            tool_context=TOOL_CONTEXT)

    from app.config import get_settings

    limit = get_settings().max_tool_rounds
    # 每轮一次请求，最后再留一次「放弃工具」的机会
    assert len(gateway.calls) <= limit + 1
    assert frames[-1] == DONE_FRAME
