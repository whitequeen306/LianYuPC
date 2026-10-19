"""`/agent/session-summary` 端点契约测试（merge / compress 两模式）。

原 Java `SessionSummaryService` 的「何时需要摘要」是产品规则仍在 Java；
「文案 + 模型调用」在 Python，对应被删除的 Java 摘要 prompt。
"""

import pytest
from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


class _Result:
    def __init__(self, content):
        self.content = content


class _Gateway:
    def __init__(self, content="合并后的摘要正文"):
        self.content = content
        self.calls = []

    async def chat_messages(self, **kwargs):
        self.calls.append(kwargs)
        return _Result(self.content)


def _payload(mode, **overrides):
    body = {
        "userId": 7,
        "baseUrl": "https://fake/v1",
        "apiKey": "k",
        "model": "m",
        "mode": mode,
        "existingSummary": "用户最近在准备考试",
        "messages": [
            {"role": "USER", "content": "我明天要考数学"},
            {"role": "ASSISTANT", "content": "加油，早点休息"},
        ],
        "targetChars": 600,
    }
    body.update(overrides)
    return body


def test_merge_returns_text(monkeypatch):
    gw = _Gateway("合并后的摘要正文")
    monkeypatch.setattr("app.main.get_gateway", lambda: gw)
    resp = client.post("/agent/session-summary", json=_payload("merge"))
    assert resp.status_code == 200
    assert resp.json() == {"text": "合并后的摘要正文"}
    sent = gw.calls[0]["messages"]
    assert sent[0]["role"] == "system"
    assert "摘要" in sent[0]["content"]
    assert "已有摘要" in sent[1]["content"]
    assert "新增对话" in sent[1]["content"]


def test_merge_maps_roles_to_user_labels(monkeypatch):
    gw = _Gateway()
    monkeypatch.setattr("app.main.get_gateway", lambda: gw)
    client.post("/agent/session-summary", json=_payload("merge"))
    user_text = gw.calls[0]["messages"][1]["content"]
    assert "用户：我明天要考数学" in user_text
    assert "角色：加油，早点休息" in user_text


def test_compress_uses_existing_summary_only(monkeypatch):
    gw = _Gateway("压缩后的摘要")
    monkeypatch.setattr("app.main.get_gateway", lambda: gw)
    resp = client.post("/agent/session-summary", json=_payload("compress"))
    assert resp.json() == {"text": "压缩后的摘要"}
    sent = gw.calls[0]["messages"]
    assert sent[0]["role"] == "system"
    assert "压缩" in sent[0]["content"]
    # compress 模式只发送已有摘要，不注入新增对话
    user_text = sent[1]["content"]
    assert "用户最近在准备考试" in user_text
    assert "新增对话" not in user_text
    assert "600" in user_text  # targetChars 进入指令


def test_upstream_failure_returns_error_code(monkeypatch):
    class _Broken:
        async def chat_messages(self, **kwargs):
            raise RuntimeError("upstream down")

    monkeypatch.setattr("app.main.get_gateway", lambda: _Broken())
    resp = client.post("/agent/session-summary", json=_payload("merge"))
    assert resp.status_code == 200
    assert resp.json() == {"error": "summary_failed"}


def test_rejects_user_mismatch():
    resp = client.post(
        "/agent/session-summary",
        json=_payload("merge"),
        headers={"X-User-Id": "999"},
    )
    assert resp.status_code == 403


def test_blank_messages_are_skipped_in_dialog_formatting(monkeypatch):
    gw = _Gateway()
    monkeypatch.setattr("app.main.get_gateway", lambda: gw)
    body = _payload("merge")
    body["messages"] = [
        {"role": "USER", "content": "  "},
        {"role": "USER", "content": "有效消息"},
    ]
    client.post("/agent/session-summary", json=body)
    user_text = gw.calls[0]["messages"][1]["content"]
    assert "有效消息" in user_text
    assert "用户：  " not in user_text
