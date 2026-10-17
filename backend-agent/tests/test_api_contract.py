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
    """能力端点必须诚实报告缺口，供 Java 侧做路由校验。"""
    response = client.get("/agent/capabilities")
    assert response.status_code == 200
    body = response.json()
    assert set(body["supportedTools"]) == {
        "get_current_time", "get_weather", "memory_search", "get_my_recent_life"
    }
    assert "memory_search" not in body["missingTools"]
    assert "write_diary" in body["missingTools"]
    assert "computer_task" in body["missingTools"]
    assert body["languageGate"] is True
    assert body["circuitBreaker"] is True
    assert body["toolLoop"] is True
    assert body["maxToolRounds"] >= 1
