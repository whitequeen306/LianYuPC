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
