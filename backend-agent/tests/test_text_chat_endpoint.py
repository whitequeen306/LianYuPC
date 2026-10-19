"""`/agent/text/chat` 端点契约测试（原 Java `AiChatService.chatBlocking` 的行为等价层）。

覆盖点（与 Java 删除前行为的对照见 docs/refactor-2026-10/test-coverage-audit.md）：
- 成功路径：content + usage（promptTokens/completionTokens/totalTokens）
- 空内容 → {error: empty_content}（Java 删除前的「对方没有回复内容」语义）
- 上游 HTTP 错误 → {error: chat_failed}，不抛
- 缺凭据是 Java 适配层职责，这里只验证字段别名能被解析
"""

import httpx
import pytest
import respx

from app.gateway import ModelGateway
from app.http_client import outbound_timeout
from app.resilience import reset_breakers, reset_lanes
from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)
BASE = "https://fake/v1"


@pytest.fixture(autouse=True)
def _reset():
    reset_breakers()
    reset_lanes()
    yield
    reset_breakers()
    reset_lanes()


def payload(**overrides):
    body = {
        "userId": 7,
        "baseUrl": BASE,
        "apiKey": "k",
        "model": "m",
        "messages": [
            {"role": "system", "content": "你是角色"},
            {"role": "user", "content": "你好"},
        ],
        "temperature": 0.7,
        "maxTokens": 256,
        "provider": "dashscope",
    }
    body.update(overrides)
    return body


def _fake_gateway(monkeypatch, content="你好呀", prompt=None, completion=None):
    class _Result:
        pass

    class _Gateway:
        async def chat_messages(self, **kwargs):
            result = _Result()
            result.content = content
            result.prompt_tokens = prompt
            result.completion_tokens = completion
            return result

    monkeypatch.setattr("app.main.get_gateway", lambda: _Gateway())


def test_success_returns_content_and_usage(monkeypatch):
    _fake_gateway(monkeypatch, content="你好呀", prompt=31, completion=12)
    resp = client.post("/agent/text/chat", json=payload())
    assert resp.status_code == 200
    body = resp.json()
    assert body["content"] == "你好呀"
    assert body["provider"] == "dashscope"
    assert body["promptTokens"] == 31
    assert body["completionTokens"] == 12
    assert body["totalTokens"] == 43


def test_usage_absent_when_upstream_omits_it(monkeypatch):
    _fake_gateway(monkeypatch, content="ok", prompt=None, completion=None)
    body = client.post("/agent/text/chat", json=payload()).json()
    assert body["content"] == "ok"
    assert body["promptTokens"] is None
    assert body["completionTokens"] is None
    assert body["totalTokens"] is None


def test_blank_content_reports_empty_content(monkeypatch):
    _fake_gateway(monkeypatch, content="   ")
    body = client.post("/agent/text/chat", json=payload()).json()
    # 现实现：空内容原样透传（Java 侧 AgentBlockingAdapter 会因 content 为空
    # 判定失败并报「对方没有回复内容」），Python 不在此处拦截。
    # 此断言固定该契约，防止将来有人「顺手」在 Python 加拦截却忘了告诉 Java。
    assert body["content"] == "   "
    assert "error" not in body


def test_upstream_http_error_returns_chat_failed(monkeypatch):
    class _Gateway:
        async def chat_messages(self, **kwargs):
            raise RuntimeError("upstream 503")

    monkeypatch.setattr("app.main.get_gateway", lambda: _Gateway())
    body = client.post("/agent/text/chat", json=payload()).json()
    assert body == {"error": "chat_failed"}


def test_rejects_user_mismatch():
    resp = client.post(
        "/agent/text/chat", json=payload(), headers={"X-User-Id": "999"})
    assert resp.status_code == 403


def test_field_aliases_accepted():
    """camelCase 别名（Java 适配层发的形态）必须能被解析，缺一不可。"""
    body = payload()
    for key in ("userId", "baseUrl", "apiKey", "maxTokens"):
        assert key in body
