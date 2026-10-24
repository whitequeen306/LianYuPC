"""新工具（desk_find / desk_wait / 进度文案）的行为测试。

这些工具的价值就在「让模型少跑几轮」，所以要锁住：
- 按文字能命中元素并给出 element_id；
- 等窗口能等到 / 超时会说话；
- 进度文案是「启动网易云音乐」这种带宾语的中文，而不是「我点一下」。
"""

from __future__ import annotations

import asyncio

import pytest

from app.capability.desktop_channel import DesktopExecResult
from app.capability.handlers import _do_find, _do_wait, _match_element, progress_text


def _elem(idx: int, label: str, role: str = "Button") -> dict:
    return {"id": idx, "label": label, "role": role, "x": 1, "y": 2, "w": 3, "h": 4}


def _ok(data: dict | None = None, text: str = "") -> DesktopExecResult:
    return DesktopExecResult(ok=True, text=text, data=data or {}, images=[], error_code="")


@pytest.mark.parametrize(
    ("name", "args", "expected"),
    [
        ("desk_app_launch", {"app": "网易云音乐"}, "启动网易云音乐"),
        ("desk_find", {"text": "大海"}, "寻找「大海」"),
        ("desk_type", {"text": "大海", "submit": True}, "输入「大海」"),
        ("desk_type", {"text": "旧歌词", "mode": "replace"}, "改写「旧歌词」"),
        ("desk_click", {"label": "播放"}, "点「播放」"),
        ("desk_system_control", {"action": "media_play"}, "播放"),
        ("desk_system_control", {"action": "volume_mute"}, "切换静音"),
        ("desk_wait", {"title": "网易云音乐"}, "等网易云音乐出来"),
        ("desk_scroll", {"dy": -400}, "往上翻"),
    ],
)
def test_progress_text_is_specific_chinese(name: str, args: dict, expected: str) -> None:
    assert progress_text(name, args) == expected


def test_match_element_filters_by_text_and_role() -> None:
    elements = [_elem(1, "搜索"), _elem(2, "大海 - 某某", "ListItem"), _elem(3, "大海", "Button")]
    assert [e["id"] for e in _match_element(elements, "大海")] == [2, 3]
    assert [e["id"] for e in _match_element(elements, "大海", "Button")] == [3]
    assert _match_element(elements, "不存在的歌") == []


def test_find_returns_ids_for_click() -> None:
    elements = [_elem(1, "搜索框", "Edit"), _elem(7, "大海", "ListItem")]
    calls: list[str] = []

    async def op(name: str, _extra: dict, _step: str) -> DesktopExecResult:
        calls.append(name)
        return _ok({"elements": elements})

    ok, text = asyncio.run(_do_find(op, {"text": "大海"}, "s1"))
    assert ok is True
    assert "#7" in text
    assert "desk_click" in text
    assert calls == ["desk_read_elements"]


def test_find_without_hit_suggests_alternatives() -> None:
    async def op(_name: str, _extra: dict, _step: str) -> DesktopExecResult:
        return _ok({"elements": [_elem(1, "搜索框", "Edit")]})

    ok, text = asyncio.run(_do_find(op, {"text": "大海"}, "s1"))
    assert ok is True
    assert "没找到" in text and "搜索框" in text


def test_wait_returns_as_soon_as_window_appears() -> None:
    windows = [{"title": "微信"}, {"title": "网易云音乐"}]

    async def op(_name: str, _extra: dict, _step: str) -> DesktopExecResult:
        return _ok({"windows": windows})

    ok, text = asyncio.run(_do_wait(op, {"title": "网易云", "timeout_ms": 300}, "s1"))
    assert ok is True
    assert "网易云音乐" in text


def test_wait_timeout_speaks_instead_of_hanging() -> None:
    async def op(_name: str, _extra: dict, _step: str) -> DesktopExecResult:
        return _ok({"windows": [{"title": "微信"}]})

    ok, text = asyncio.run(_do_wait(op, {"title": "网易云", "timeout_ms": 100}, "s1"))
    assert ok is True
    assert "没等到" in text or "还没" in text
