"""统一执行器：上下文剥离、预算拦截、策略拒绝、错误翻译、屏幕报告。

这些断言覆盖的是「模型最容易翻车」的几条路：
把内部字段当参数发下去、预算用完还继续点、失败后无限重试、把图片塞进文本上下文。
"""

from __future__ import annotations

import pytest

import app.tools  # noqa: F401 - 导入即注册
from app.capability import handlers as H
from app.capability.budget import STOP_STEPS, TaskBudget
from app.capability.desktop_channel import DesktopExecResult


class FakeChannel:
    def __init__(self, result: DesktopExecResult | None = None) -> None:
        self.result = result or DesktopExecResult(ok=True, text="done")
        self.exec_calls: list[dict] = []
        self.progress_calls: list[dict] = []

    async def exec(self, **kwargs):  # noqa: A003 - 与生产通道同名
        self.exec_calls.append(kwargs)
        return self.result

    async def progress(self, **kwargs):
        self.progress_calls.append(kwargs)

    async def abort(self, **kwargs):
        return None


@pytest.fixture()
def channel(monkeypatch):
    fake = FakeChannel()
    monkeypatch.setattr(H, "get_channel", lambda: fake)
    return fake


def ctx(user_id: int = 7, **extra) -> dict:
    base = {
        "_user_id": user_id,
        "_character_id": 42,
        "_character_name": "小雨",
        "_session_id": "desk-7-deadbeef",
        "_budget": TaskBudget(session_id="desk-7-deadbeef"),
    }
    base.update(extra)
    return base


def test_split_internal_arguments():
    public, internal = H.split_internal_arguments(
        {"action": "media_play", "evidence": "user_request", "_user_id": 1, "_budget": "b"})
    assert public == {"action": "media_play", "evidence": "user_request"}
    assert internal == {"_user_id": 1, "_budget": "b"}


def test_guidance_known_unknown_and_empty():
    assert "切到别的窗口" in H.guidance_for("sensitive_window")
    assert "不要重复同一动作" in H.guidance_for("something_new")
    assert "没有返回结果" in H.guidance_for("", "desk_click")


@pytest.mark.asyncio
async def test_success_forwards_only_model_arguments(channel):
    text = await H.call_desktop_tool(
        "desk_system_control",
        {"action": "media_play", "evidence": "user_request", **ctx()},
    )
    assert text == "done"
    call = channel.exec_calls[0]
    assert call["arguments"] == {"action": "media_play", "evidence": "user_request"}
    assert not any(k.startswith("_") for k in call["arguments"])
    assert call["actor"] == {"characterId": 42, "name": "小雨"}
    assert call["step_id"] == "desk-7-deadbeef#1"


@pytest.mark.asyncio
async def test_progress_emitted_before_exec(channel):
    await H.call_desktop_tool(
        "desk_click", {"label": "播放", "element_id": 3, "evidence": "user_request", **ctx()})
    assert channel.progress_calls, "桌面动作必须发进度（角色要有『在做事』的现场感）"
    assert channel.progress_calls[0]["code"] == "desk_click"


@pytest.mark.asyncio
async def test_budget_exhausted_blocks_before_dispatch(channel):
    budget = TaskBudget(session_id="desk-7-deadbeef", max_steps=0)
    text = await H.call_desktop_tool(
        "desk_click", {"label": "播放", "evidence": "user_request", **ctx(_budget=budget)})
    assert channel.exec_calls == [], "预算用完不能再下发"
    assert H.guidance_for(STOP_STEPS) in text


@pytest.mark.asyncio
async def test_screen_evidence_high_risk_is_denied(channel):
    text = await H.call_desktop_tool(
        "desk_click",
        {"label": "删除全部", "evidence": "screen_content", **ctx()},
    )
    assert channel.exec_calls == [], "屏幕内容触发的高危动作在地面就该拦住"
    assert "不能当作指令" in text


@pytest.mark.asyncio
async def test_failure_returns_guidance_not_exception(monkeypatch):
    fake = FakeChannel(DesktopExecResult(ok=False, error_code="window_not_found"))
    monkeypatch.setattr(H, "get_channel", lambda: fake)
    text = await H.call_desktop_tool(
        "desk_window_focus", {"title": "网易云", "evidence": "user_request", **ctx()})
    assert "没找到目标窗口" in text


@pytest.mark.asyncio
async def test_failure_counted_in_budget(monkeypatch):
    fake = FakeChannel(DesktopExecResult(ok=False, error_code="target_obscured"))
    monkeypatch.setattr(H, "get_channel", lambda: fake)
    budget = TaskBudget(session_id="desk-7-deadbeef")
    for _ in range(2):
        await H.call_desktop_tool(
            "desk_click", {"label": "播放", "evidence": "user_request", **ctx(_budget=budget)})
    assert budget.consecutive_failures["desk_click"] == 2
    text = await H.call_desktop_tool(
        "desk_click", {"label": "播放", "evidence": "user_request", **ctx(_budget=budget)})
    assert "连续失败" in text
    assert len(fake.exec_calls) == 2


@pytest.mark.asyncio
async def test_screen_read_builds_report_from_elements(monkeypatch):
    fake = FakeChannel(DesktopExecResult(
        ok=True,
        data={
            "windowTitle": "网易云音乐",
            "app": "cloudmusic.exe",
            "elements": [
                {"id": 1, "label": "播放", "role": "Button", "x": 100, "y": 200},
                {"id": 2, "label": "下一首", "role": "Button", "x": 140, "y": 200},
            ],
        },
    ))
    monkeypatch.setattr(H, "get_channel", lambda: fake)
    text = await H.call_desktop_tool(
        "desk_screen_read", {"evidence": "user_request", **ctx()})
    assert "网易云音乐" in text
    assert "[1] 播放" in text
    assert "[2] 下一首" in text
    assert "点击时用编号" in text


@pytest.mark.asyncio
async def test_screen_read_without_elements_refuses_to_guess(monkeypatch):
    fake = FakeChannel(DesktopExecResult(ok=True, data={"windowTitle": "桌面"}))
    monkeypatch.setattr(H, "get_channel", lambda: fake)
    text = await H.call_desktop_tool(
        "desk_screen_read", {"evidence": "user_request", **ctx()})
    assert "不要凭空点击" in text


@pytest.mark.asyncio
async def test_missing_user_context_is_refused(channel):
    text = await H.call_desktop_tool("desk_window_list", {"evidence": "user_request"})
    assert "缺少用户上下文" in text
    assert channel.exec_calls == []
