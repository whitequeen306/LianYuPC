"""工具循环的「空口承诺重问」行为测试（不需要真模型：假 gateway 逐轮返回）。"""

from __future__ import annotations

from typing import Any

import pytest

from app.capability.intent import DESKTOP_ACTION_NUDGE
from app.gateway import GatewayResult
from app.tool_loop import run_tool_loop


class FakeGateway:
    """按顺序吐出预设响应；同时记录每次收到的 messages 快照。"""

    def __init__(self, responses: list[GatewayResult]) -> None:
        self._responses = list(responses)
        self.calls: list[list[dict[str, Any]]] = []

    async def chat_payload(self, *, base_url, api_key, payload, provider, lane_name):  # noqa: ANN001
        self.calls.append([dict(m) for m in payload.get("messages", [])])
        if not self._responses:
            raise AssertionError("unexpected extra model call")
        return self._responses.pop(0)


def _ok(content: str, tool_calls: list[dict[str, Any]] | None = None) -> GatewayResult:
    return GatewayResult(content=content, tool_calls=tool_calls or [], prompt_tokens=10, completion_tokens=5)


async def _run(gateway: FakeGateway, *, tool_names: list[str], user_text: str):
    return await run_tool_loop(
        gateway,
        base_url="http://fake",
        api_key="k",
        model="m",
        messages=[{"role": "user", "content": user_text}],
        tool_names=tool_names,
        temperature=0.7,
        max_tokens=256,
        provider=None,
        lane_name="interactive",
        tool_context={},
    )


@pytest.mark.asyncio
async def test_empty_promise_is_nudged_once_then_returns():
    # 第一轮只说话（真实事故的复现），追问后仍只说话 → 不再追，直接返回
    gateway = FakeGateway([_ok("好的，这就为您打开。正在为您启动网易云音乐。"), _ok("抱歉，我没能动手。")])

    result = await _run(gateway, tool_names=["desk_app_launch"], user_text="帮我打开网易云音乐播放大海")

    assert result.nudged is True
    assert len(gateway.calls) == 2, "应该只追问一次"
    # 第二次调用必须带上那条系统提示
    assert any(DESKTOP_ACTION_NUDGE in str(m.get("content")) for m in gateway.calls[1])


@pytest.mark.asyncio
async def test_chitchat_is_not_nudged():
    gateway = FakeGateway([_ok("我在呢，今天想说点什么？")])

    result = await _run(gateway, tool_names=["desk_app_launch"], user_text="你在干嘛呀")

    assert result.nudged is False
    assert len(gateway.calls) == 1


@pytest.mark.asyncio
async def test_no_desk_tools_means_no_nudge():
    # 桌面能力没开（没有 desk_*）：用户要动作也只回一次，不去追
    gateway = FakeGateway([_ok("这个我做不了呢。")])

    result = await _run(gateway, tool_names=["get_current_time"], user_text="帮我打开网易云音乐")

    assert result.nudged is False
    assert len(gateway.calls) == 1


@pytest.mark.asyncio
async def test_nudge_then_model_calls_tool(monkeypatch):
    # 追问后模型真的动手了：工具被调用一次，最终返回的是工具执行后的文本
    calls: list[str] = []

    async def fake_execute(specs, tool_calls, ctx):  # noqa: ANN001
        for call in tool_calls:
            calls.append(call.get("name"))
        return [(call.get("name"), '{"ok": true}') for call in tool_calls]

    monkeypatch.setattr("app.tool_loop.execute_tool_calls", fake_execute)

    tool_call = {"id": "c1", "name": "desk_app_launch", "arguments": '{"app":"网易云音乐"}'}
    gateway = FakeGateway([
        _ok("这就为您打开。"),
        _ok("", [tool_call]),
        _ok("已经帮你打开啦。"),
    ])

    result = await _run(gateway, tool_names=["desk_app_launch"], user_text="帮我打开网易云音乐")

    assert result.nudged is True
    assert calls == ["desk_app_launch"]
    assert result.rounds == 1
    assert "已经帮你打开啦" in result.content

# ── 能力层单测：显式打开桌面工具 ──
# 2026-10-25 产品决定：模型侧不再提供 desk_*（见 app/tool_registry.desktop_tools_enabled）。
# 本文件测的是「能力层本身对不对」，所以要显式打开；产品口径由
# tests/test_desktop_tools_off.py 单独锁定。
@pytest.fixture(autouse=True)
def _enable_desktop_tools(monkeypatch):
    monkeypatch.setenv("LIANYU_DESKTOP_TOOLS", "1")
