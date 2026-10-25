"""锁定产品口径：模型侧**不再提供**桌面能力（desk_*）。

2026-10-25 决定：先把「角色操控电脑」整个下掉，模型只保留聊天类工具
（取时间 / 天气 / 记忆检索 / 最近生活）。能力层代码保留，随时可一键恢复。

这里的用例就是这条决定的守门人 —— 谁把开关默认值改回去、或绕开
`resolve_tools` 直接塞 desk_* 给模型，都会在这里红。
"""

from __future__ import annotations

import pytest

from app.tool_registry import (
    desktop_tools_enabled,
    resolve_tools,
    supported_tool_names,
    supported_tool_schemas,
)

CHAT_TOOLS = {"get_current_time", "get_weather", "memory_search", "get_my_recent_life"}


def test_desktop_tools_off_by_default(monkeypatch):
    monkeypatch.delenv("LIANYU_DESKTOP_TOOLS", raising=False)
    assert desktop_tools_enabled() is False


def test_only_chat_tools_are_offered(monkeypatch):
    monkeypatch.delenv("LIANYU_DESKTOP_TOOLS", raising=False)
    offered = supported_tool_names()
    assert CHAT_TOOLS <= offered
    assert not [n for n in offered if n.startswith("desk_")], "desk_* 不该出现在模型可用清单里"


def test_resolve_tools_drops_desktop_even_if_caller_asks(monkeypatch):
    """Java 传 body.tools 时可能还带着 desk_*（老客户端），Python 这层必须拦掉。"""
    monkeypatch.delenv("LIANYU_DESKTOP_TOOLS", raising=False)
    names = [spec.name for spec in resolve_tools(["desk_click", "desk_screen_read", "memory_search"])]
    assert names == ["memory_search"]


def test_schemas_never_contain_desktop(monkeypatch):
    monkeypatch.delenv("LIANYU_DESKTOP_TOOLS", raising=False)
    schemas = supported_tool_schemas(["desk_click", "get_current_time"])
    assert [s["function"]["name"] for s in schemas] == ["get_current_time"]


def test_env_flag_can_restore_desktop_tools(monkeypatch):
    """恢复路径要留着：设 LIANYU_DESKTOP_TOOLS=1 就回到旧行为（不改代码）。"""
    monkeypatch.setenv("LIANYU_DESKTOP_TOOLS", "1")
    assert desktop_tools_enabled() is True
    assert [s.name for s in resolve_tools(["desk_click"])] == ["desk_click"]


@pytest.mark.parametrize("value", ["0", "", "false", "no", "off"])
def test_falsy_values_keep_it_off(monkeypatch, value):
    monkeypatch.setenv("LIANYU_DESKTOP_TOOLS", value)
    assert desktop_tools_enabled() is False
