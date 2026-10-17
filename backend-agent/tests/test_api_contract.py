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
