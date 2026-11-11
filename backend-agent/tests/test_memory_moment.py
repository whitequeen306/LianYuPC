"""通话中轻量写记忆：判定、quote 证据、去重与失败语义。

不碰真库与真模型：`record_moment` 的写入被换成假函数，只验证
「判定 → 证据校验 → 只写 episode → 失败不影响通话」这条链。
"""

from __future__ import annotations

import pytest

from app.memory import moment

USER_TEXT = "今天加班到很晚，好累，你在吗？"

CREDS = dict(
    base_url="https://example.invalid/v1",
    api_key="sk-test",
    model="test-model",
)


class FakeGateway:
    def __init__(self, content='{"worth":true,"summary":"她加班到很晚很累","quote":"今天加班到很晚，好累","valence":-0.4,"arousal":0.6,"openLoop":""}'):
        self.content = content
        self.calls = []

    async def chat_messages(self, **kwargs):
        self.calls.append(kwargs)

        class Result:
            pass

        result = Result()
        result.content = self.content
        return result


def _capture_insert(monkeypatch, *, result=31):
    captured = {}

    async def fake_insert(**kwargs):
        captured.update(kwargs)
        return result

    monkeypatch.setattr("app.memory.episode_store.insert_episode", fake_insert)
    return captured


@pytest.mark.asyncio
async def test_records_episode_with_quote_and_voice_scene(monkeypatch):
    captured = _capture_insert(monkeypatch)
    gateway = FakeGateway()

    episode_id = await moment.record_moment(
        gateway, user_id=7, character_id=3, conversation_id=11,
        user_text=USER_TEXT, reply_text="我在，先坐下歇一会儿。", **CREDS,
    )

    assert episode_id == 31
    assert captured["quote"] == "今天加班到很晚，好累"
    assert captured["summary"] == "她加班到很晚很累"
    assert captured["scene"] == {"scene": "VOICE_CALL"}
    assert captured["conversation_id"] == 11
    assert captured["valence"] == -0.4 and captured["arousal"] == 0.6


@pytest.mark.asyncio
async def test_quote_must_be_verbatim_user_text(monkeypatch):
    """模型改写过的 quote 不能当证据：退回整句用户原话。"""
    captured = _capture_insert(monkeypatch)
    gateway = FakeGateway(
        '{"worth":true,"summary":"她加班很累","quote":"今天加班到很晚，真的太累了。","valence":-0.3}'
    )

    await moment.record_moment(
        gateway, user_id=7, character_id=3, user_text=USER_TEXT, **CREDS,
    )

    assert captured["quote"] == USER_TEXT


@pytest.mark.asyncio
async def test_not_worth_recording_writes_nothing(monkeypatch):
    captured = _capture_insert(monkeypatch)
    gateway = FakeGateway('{"worth":false}')

    assert await moment.record_moment(
        gateway, user_id=7, character_id=3, user_text=USER_TEXT, **CREDS,
    ) == 0
    assert captured == {}


@pytest.mark.asyncio
async def test_short_text_skips_model_call(monkeypatch):
    _capture_insert(monkeypatch)
    gateway = FakeGateway()

    assert await moment.record_moment(
        gateway, user_id=7, character_id=3, user_text="嗯嗯好的", **CREDS,
    ) == 0
    assert gateway.calls == []


@pytest.mark.asyncio
async def test_missing_credentials_skips_model_call(monkeypatch):
    _capture_insert(monkeypatch)
    gateway = FakeGateway()

    assert await moment.record_moment(
        gateway, user_id=7, character_id=3, user_text=USER_TEXT,
    ) == 0
    assert gateway.calls == []


@pytest.mark.asyncio
async def test_duplicate_episode_reports_zero(monkeypatch):
    """`insert_episode` 的 7 天同句去重返回 0，端点据此写 written=0。"""
    _capture_insert(monkeypatch, result=0)

    assert await moment.record_moment(
        FakeGateway(), user_id=7, character_id=3, user_text=USER_TEXT, **CREDS,
    ) == 0


@pytest.mark.asyncio
async def test_insert_failure_is_swallowed(monkeypatch):
    """写库失败只记日志：通话里少记一条，不能把这一通打挂。"""

    async def broken(**_kwargs):
        raise RuntimeError("mysql down")

    monkeypatch.setattr("app.memory.episode_store.insert_episode", broken)

    assert await moment.record_moment(
        FakeGateway(), user_id=7, character_id=3, user_text=USER_TEXT, **CREDS,
    ) == 0


@pytest.mark.asyncio
async def test_judge_failure_writes_nothing(monkeypatch):
    captured = _capture_insert(monkeypatch)

    class BrokenGateway:
        async def chat_messages(self, **_kwargs):
            raise RuntimeError("upstream down")

    assert await moment.record_moment(
        BrokenGateway(), user_id=7, character_id=3, user_text=USER_TEXT, **CREDS,
    ) == 0
    assert captured == {}


def test_parse_verdict_rejects_garbage():
    assert moment.parse_verdict("not json", user_text=USER_TEXT) is None
    assert moment.parse_verdict('{"worth":"yes"}', user_text=USER_TEXT) is None
    assert moment.parse_verdict("", user_text=USER_TEXT) is None


def test_parse_verdict_clamps_emotion_range():
    verdict = moment.parse_verdict(
        '{"worth":true,"summary":"s","quote":"今天加班到很晚","valence":-9,"arousal":9}',
        user_text=USER_TEXT,
    )
    assert verdict["valence"] == -1.0
    assert verdict["arousal"] == 1.0
