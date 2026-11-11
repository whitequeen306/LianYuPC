"""`/agent/voice/cue` 端点：开场台词的组装与降级。

这里不碰真实数据库/模型：只验证「接线 + 话术拼装 + 失败语义」。
"""

from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)

PAYLOAD = {
    "userId": 7,
    "characterId": 51,
    "conversationId": 53,
    "characterName": "甘雨",
    "settings": {"personality": "温柔"},
    "kind": "opening",
    "textBaseUrl": "https://example.invalid/v1",
    "textApiKey": "sk-test",
    "textModel": "test-model",
}


class FakeGateway:
    def __init__(self, content="我在呢，今天想聊点什么？"):
        self.content = content
        self.calls = []

    async def chat_messages(self, **kwargs):
        self.calls.append(kwargs)

        class Result:
            pass

        result = Result()
        result.content = self.content
        return result


def _patch_prompt(monkeypatch, captured):
    async def fake_assemble(**kwargs):
        captured.update(kwargs)
        return "[[VOICE_CALL_SYSTEM]]"

    monkeypatch.setattr("app.prompt_pipeline.assemble_turn_prompt", fake_assemble)


def test_opening_uses_voice_call_scene_and_task_prompt(monkeypatch):
    fake = FakeGateway()
    captured = {}
    _patch_prompt(monkeypatch, captured)
    monkeypatch.setattr("app.main.get_gateway", lambda: fake)

    response = client.post("/agent/voice/cue", json=PAYLOAD)

    assert response.status_code == 200
    assert response.json()["text"] == "我在呢，今天想聊点什么？"
    assert captured["scene"] == "VOICE_CALL"
    assert captured["character_name"] == "甘雨"
    system = fake.calls[0]["messages"][0]["content"]
    assert system.startswith("[[VOICE_CALL_SYSTEM]]")
    assert "通话开场（强制）" in system
    assert "最多 32 个字" in system
    assert len(fake.calls[0]["messages"]) == 2


def test_idle_cue_uses_idle_prompt(monkeypatch):
    fake = FakeGateway(content="你刚才说到哪了来着？")
    captured = {}
    _patch_prompt(monkeypatch, captured)
    monkeypatch.setattr("app.main.get_gateway", lambda: fake)

    response = client.post("/agent/voice/cue", json=dict(PAYLOAD, kind="idle"))

    assert response.status_code == 200
    assert response.json()["text"] == "你刚才说到哪了来着？"
    assert captured["scene"] == "VOICE_CALL"
    system = fake.calls[0]["messages"][0]["content"]
    assert "通话冷场" in system
    assert "最多 24 个字" in system
    assert fake.calls[0]["lane_name"] == "voice-cue-idle"


def test_invite_cue_uses_invite_prompt(monkeypatch):
    fake = FakeGateway(content="现在方便接个电话吗？")
    captured = {}
    _patch_prompt(monkeypatch, captured)
    monkeypatch.setattr("app.main.get_gateway", lambda: fake)

    response = client.post("/agent/voice/cue", json=dict(PAYLOAD, kind="invite"))

    assert response.status_code == 200
    assert response.json()["text"] == "现在方便接个电话吗？"
    system = fake.calls[0]["messages"][0]["content"]
    assert "来电邀请" in system
    assert "最多 28 个字" in system
    assert fake.calls[0]["lane_name"] == "voice-cue-invite"


def test_voicemail_cue_uses_voicemail_prompt(monkeypatch):
    fake = FakeGateway(content="早点睡，明天再聊。")
    captured = {}
    _patch_prompt(monkeypatch, captured)
    monkeypatch.setattr("app.main.get_gateway", lambda: fake)

    response = client.post("/agent/voice/cue", json=dict(PAYLOAD, kind="voicemail"))

    assert response.status_code == 200
    assert response.json()["text"] == "早点睡，明天再聊。"
    system = fake.calls[0]["messages"][0]["content"]
    assert "挂断留言" in system
    assert fake.calls[0]["lane_name"] == "voice-cue-voicemail"


def test_unknown_kind_returns_empty_without_model_call(monkeypatch):
    fake = FakeGateway()
    captured = {}
    _patch_prompt(monkeypatch, captured)
    monkeypatch.setattr("app.main.get_gateway", lambda: fake)

    body = dict(PAYLOAD, kind="weird")
    response = client.post("/agent/voice/cue", json=body)

    assert response.status_code == 200
    assert response.json()["text"] == ""
    assert fake.calls == []


def test_gateway_failure_returns_error_code(monkeypatch):
    captured = {}
    _patch_prompt(monkeypatch, captured)

    class BrokenGateway:
        async def chat_messages(self, **_kwargs):
            raise RuntimeError("upstream down")

    monkeypatch.setattr("app.main.get_gateway", lambda: BrokenGateway())

    response = client.post("/agent/voice/cue", json=PAYLOAD)

    assert response.status_code == 200
    assert response.json() == {"error": "cue_failed"}


def test_user_mismatch_is_rejected():
    response = client.post("/agent/voice/cue", json=PAYLOAD, headers={"X-User-Id": "999"})
    assert response.status_code == 403
