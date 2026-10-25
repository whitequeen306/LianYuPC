"""FastAPI 契约测试：desktop-observe 响应形状与降级语义对齐 Java ObserveController。"""

import base64
from unittest.mock import patch

import pytest
from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)

FAKE_PNG = base64.b64encode(b"\x89PNG fake image").decode()


def test_health():
    response = client.get("/agent/health")
    assert response.status_code == 200
    assert response.json() == {"status": "UP"}


def test_rejects_user_mismatch():
    response = client.post(
        "/agent/desktop-observe",
        json={"userId": 1, "imageBase64": FAKE_PNG, "windowTitle": None, "persona": None},
        headers={"X-User-Id": "2"},
    )
    assert response.status_code == 403


def test_invalid_base64_returns_null_greeting():
    with patch("app.main.observe_desktop", new=None):
        response = client.post(
            "/agent/desktop-observe",
            json={"userId": 1, "imageBase64": "!!!bad!!!", "windowTitle": None, "persona": None},
        )
    assert response.status_code == 200
    assert response.json() == {"greeting": None}


def test_shape_contract():
    """响应只含 greeting 字段——Java Result.ok(greeting) 的等价形状。"""
    response = client.get("/agent/health")
    assert set(response.json().keys()) == {"status"}
    body = client.post(
        "/agent/desktop-observe",
        json={"userId": 1, "imageBase64": FAKE_PNG, "windowTitle": None, "persona": None},
    )
    assert set(body.json().keys()) == {"greeting"}


def test_accepts_stage2_credentials_field_names():
    """Java 适配层转发体字段名（textBaseUrl/textApiKey/textModel）可被解析。"""
    response = client.post(
        "/agent/desktop-observe",
        json={
            "userId": 1,
            "imageBase64": FAKE_PNG,
            "windowTitle": "vscode",
            "persona": "p",
            "textBaseUrl": "https://fake/v1",
            "textApiKey": "k",
            "textModel": "m",
        },
    )
    # 无真实上游（fake 地址连不通）→ Stage 2 静默降级 None，但证明契约字段被接受
    assert response.status_code == 200
    assert response.json() == {"greeting": None}


def test_capabilities_declares_missing_tools():
    """能力端点必须诚实报告缺口，供 Java 侧做路由校验。

    电脑操控已由自研 `desk_*` 承接：粗粒度 `computer_task` 既不在 supported，
    也不再被声明成缺口（它已退役，而不是"还没做"）。
    """
    response = client.get("/agent/capabilities")
    assert response.status_code == 200
    body = response.json()
    supported = set(body["supportedTools"])
    assert {"get_current_time", "get_weather", "memory_search", "get_my_recent_life"} <= supported
    assert "memory_search" not in body["missingTools"]
    assert "write_diary" in body["missingTools"]
    assert "computer_task" not in supported
    assert "computer_task" not in body["missingTools"]

    desktop = body["desktopCapability"]
    assert desktop["transport"] == "bridge-stomp"
    assert desktop["tools"], "桌面能力清单不能为空"
    assert all(t.startswith("desk_") for t in desktop["tools"])
    assert set(desktop["tools"]) <= supported, "声明了却没注册 = 撒谎"
    assert desktop["maxSteps"] >= 1
    assert desktop["maxVisionFrames"] >= 1
    assert desktop["taskDeadlineSeconds"] > 0

    assert body["languageGate"] is True
    assert body["circuitBreaker"] is True
    assert body["toolLoop"] is True
    assert body["maxToolRounds"] >= 1


def test_proactive_response_carries_usage(monkeypatch):
    """成功路径必须带回 usage —— AGENT 模式丢 token 统计属数据质量回归。"""
    class FakeGraph:
        async def ainvoke(self, state, config=None):
            return {
                "assistant_raw": "今晚早点睡",
                "prompt_tokens": 120,
                "completion_tokens": 18,
                "language_retries": 1,
                "tool_rounds": 2,
                "tool_truncated": False,
            }

    import app.graphs.proactive_turn as graph_module

    monkeypatch.setattr(graph_module, "build_proactive_graph", lambda gateway: FakeGraph())

    response = client.post(
        "/agent/proactive-turn",
        json={
            "userId": 1,
            "scene": "PROACTIVE",
            "persona": "p",
            "taskBlock": "t",
            "history": [],
            "characterId": 3,
            "tools": ["get_current_time"],
        },
    )
    assert response.status_code == 200
    body = response.json()
    assert body["assistantRaw"] == "今晚早点睡"
    assert body["promptTokens"] == 120
    assert body["completionTokens"] == 18
    assert body["totalTokens"] == 138          # 两侧相加
    assert body["languageRetries"] == 1
    assert body["toolRounds"] == 2
    assert body["toolTruncated"] is False


def test_assistant_raw_uses_camel_case_wire_name():
    """回归：response 必须输出 assistantRaw（camelCase）。

    FastAPI 默认 response_model_by_alias=True；无 alias 的字段会变成 snake_case，
    而 Java 侧读的是 assistantRaw —— 曾因此让 AGENT 模式静默回退 Java（真实线上隐患）。
    """
    response = client.post(
        "/agent/proactive-turn",
        json={"userId": 1, "persona": "p", "history": []},
    )
    body = response.json()
    assert "assistantRaw" in body
    assert "assistant_raw" not in body

# ── 能力层单测：显式打开桌面工具 ──
# 2026-10-25 产品决定：模型侧不再提供 desk_*（见 app/tool_registry.desktop_tools_enabled）。
# 本文件测的是「能力层本身对不对」，所以要显式打开；产品口径由
# tests/test_desktop_tools_off.py 单独锁定。
@pytest.fixture(autouse=True)
def _enable_desktop_tools(monkeypatch):
    monkeypatch.setenv("LIANYU_DESKTOP_TOOLS", "1")
