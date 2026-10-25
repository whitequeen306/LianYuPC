"""桌面能力清单（specs）契约：名字、schema、注册、按需下发。"""

from __future__ import annotations

import pytest

import json
import re

import app.tools  # noqa: F401 - 导入即注册 local + desktop
from app.capability.specs import (
    DESKTOP_SPECS,
    DESKTOP_TOOL_NAMES,
    capability_names,
    register_desktop_capabilities,
)
from app.tools.registry import get_tool, names_of_kind, supported_tool_names

# Java `AgentBridgeService.TOOL_NAME_PATTERN`：点号会让整批注册被拒
TOOL_NAME_RE = re.compile(r"^[A-Za-z0-9_-]{1,64}$")


def test_all_desktop_tools_registered_and_named_legally():
    registered = set(supported_tool_names())
    assert set(DESKTOP_TOOL_NAMES) <= registered
    for spec in DESKTOP_SPECS:
        assert TOOL_NAME_RE.match(spec.name), f"非法工具名：{spec.name}"
        assert spec.name.startswith("desk_")
        assert get_tool(spec.name) is spec


def test_desktop_kind_is_separate_from_local():
    desktop = set(names_of_kind("desktop"))
    local = set(names_of_kind("local"))
    assert desktop == set(DESKTOP_TOOL_NAMES)
    assert desktop.isdisjoint(local)
    # 既有只读工具必须仍在 local 里（不能被能力层挤掉）
    assert {"get_current_time", "get_weather", "memory_search", "get_my_recent_life"} <= local


def test_every_desktop_tool_declares_evidence_and_meta():
    for spec in DESKTOP_SPECS:
        schema = spec.to_openai_schema()
        params = schema["function"]["parameters"]
        assert "evidence" in params["properties"], spec.name
        assert "evidence" in params["required"], spec.name
        assert params["properties"]["evidence"]["enum"] == ["user_request", "screen_content"]
        assert spec.requires == ("desktop",)
        assert spec.timeout_ms >= 2000
        assert spec.danger in {"R0", "R1", "R2", "R3"}
        # 危险等级不能泄漏给模型
        assert "danger" not in params["properties"]


def test_schema_is_json_serializable():
    for spec in DESKTOP_SPECS:
        json.dumps(spec.to_openai_schema(), ensure_ascii=False)


def test_capability_names_gate_on_desktop_online():
    assert capability_names(desktop_online=False) == ()
    online = capability_names(desktop_online=True)
    assert online == DESKTOP_TOOL_NAMES
    assert capability_names(desktop_online=True, enabled=False) == ()


def test_register_is_idempotent():
    register_desktop_capabilities()
    register_desktop_capabilities()
    names = [spec.name for spec in DESKTOP_SPECS]
    assert len(names) == len(set(names))
    assert set(names) <= set(supported_tool_names())


def test_capability_can_be_imported_first_without_cycle():
    """回归：**必须能在干净解释器里先 import app.capability**。

    真实踩过：`app/capability/*` 曾从 `app.tools.registry` 导入 ToolSpec，
    而导入子模块会先执行 `app/tools/__init__.py`，后者又 import capability 做注册 ——
    形成循环。只在「capability 先被导入」时炸（服务启动是 tools 先，所以一直没暴露）。
    修法是把注册表提成叶子模块 `app/tool_registry`。这里用子进程保证是干净解释器。
    """
    import subprocess
    import sys

    code = "import app.capability as c; print(len(c.DESKTOP_TOOL_NAMES))"
    result = subprocess.run([sys.executable, "-c", code], capture_output=True, text=True, timeout=120)
    assert result.returncode == 0, result.stderr
    assert int(result.stdout.strip()) >= 14

# ── 能力层单测：显式打开桌面工具 ──
# 2026-10-25 产品决定：模型侧不再提供 desk_*（见 app/tool_registry.desktop_tools_enabled）。
# 本文件测的是「能力层本身对不对」，所以要显式打开；产品口径由
# tests/test_desktop_tools_off.py 单独锁定。
@pytest.fixture(autouse=True)
def _enable_desktop_tools(monkeypatch):
    monkeypatch.setenv("LIANYU_DESKTOP_TOOLS", "1")
