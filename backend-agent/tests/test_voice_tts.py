"""语音能力迁移测试：端点归一化 / 音色注册 / 非实时合成 / HTTP 契约。

这一组测试的目的不是覆盖率，而是**钉住与 Java 原实现逐条对齐的行为**：
错误文案、白名单、重试口径、失败返回 None 的降级契约。
任何一条变了，Java 侧的提示语或降级路径就会跟着变。
"""

import base64

import httpx
import pytest
import respx
from fastapi.testclient import TestClient

from app.dashscope import (
    DEFAULT_BASE,
    DashScopeEndpointError,
    SPEECH_SYNTH_PATH,
    SYNTH_PATH,
    enroll_url,
    guess_mime_type,
    is_allowed_audio_host,
    normalize_audio_url,
    normalize_base_url,
    normalize_model,
    realtime_ws_url,
    synth_path_for,
    synth_url,
)
from app.main import app
from app.tts import is_transient_network_failure, synthesize
from app.voice_enrollment import (
    VoiceEnrollmentError,
    create_voice,
    delete_voice_quietly,
    sanitize_preferred_name,
    sanitize_user_message,
)

ENROLL = "https://dashscope.aliyuncs.com/api/v1/services/audio/tts/customization"
SYNTH = "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation"
SPEECH_SYNTH = DEFAULT_BASE + SPEECH_SYNTH_PATH
AUDIO = "https://dashscope-result.oss-cn-beijing.aliyuncs.com/a.wav"


# ─────────────── 端点与模型必须配对（2026-10-21 线上故障的回归守护） ───────────────


@pytest.mark.parametrize(
    "model,expected",
    [
        ("qwen3-tts-flash", SYNTH_PATH),
        ("qwen3-tts-vc-2026-01-22", SYNTH_PATH),
        ("qwen-audio-3.1-tts-flash", SPEECH_SYNTH_PATH),
        ("qwen-audio-3.1-tts-plus", SPEECH_SYNTH_PATH),
        ("cosyvoice-v3-flash", SPEECH_SYNTH_PATH),
    ],
)
def test_synth_path_follows_model_family(model, expected):
    """把 qwen-audio-* 发到旧端点会 400 `url error`；把 qwen3-tts-* 发到新端点也会失败。

    这条守护的是「换了模型名却没换端点」这类故障 —— 它表现为一句看起来像地址写错的
    报错，很容易被误判成 base_url 配置问题。
    """
    assert synth_path_for(model) == expected


@respx.mock
async def test_synthesize_uses_speech_synthesizer_payload_for_audio_models():
    """qwen-audio-* 走新端点：要 format/sample_rate，不带 language_type。"""
    route = respx.post(SPEECH_SYNTH).respond(json={"output": {"audio": {"url": AUDIO}}})
    respx.get(AUDIO).respond(content=b"RIFF" + b"\x00" * 8)

    audio = await synthesize(
        api_key="k", model="qwen-audio-3.1-tts-flash", voice_id="v1", text="hi"
    )

    assert audio is not None
    body = route.calls[0].request.read().decode()
    assert '"format": "wav"' in body or '"format":"wav"' in body
    assert '"sample_rate": 24000' in body or '"sample_rate":24000' in body
    assert "language_type" not in body


@respx.mock
async def test_synthesize_keeps_legacy_payload_for_qwen3_models():
    """qwen3-tts-* 仍走旧端点：带 language_type，不带 format。"""
    route = respx.post(SYNTH).respond(json={"output": {"audio": {"url": AUDIO}}})
    respx.get(AUDIO).respond(content=b"RIFF" + b"\x00" * 8)

    audio = await synthesize(api_key="k", model="qwen3-tts-flash", voice_id="v1", text="hi")

    assert audio is not None
    body = route.calls[0].request.read().decode()
    assert "language_type" in body
    assert '"format"' not in body


# ─────────────── 流式合成（语音通话边收边播） ───────────────


def _sse(*chunks: bytes) -> bytes:
    """拼一段 DashScope 风格的 SSE 响应体。"""
    import json as _json

    lines = []
    for c in chunks:
        lines.append(
            "data: " + _json.dumps({"output": {"audio": {"data": base64.b64encode(c).decode()}}}))
    lines.append("data: [DONE]")
    return ("\n\n".join(lines) + "\n\n").encode()


@respx.mock
async def test_synthesize_stream_yields_each_chunk_in_order():
    from app.tts import synthesize_stream

    respx.post(SPEECH_SYNTH).respond(
        content=_sse(b"AAAA", b"BBBB"),
        headers={"Content-Type": "text/event-stream"},
    )

    chunks = [
        c
        async for c in synthesize_stream(
            api_key="k", model="qwen-audio-3.1-tts-flash", voice_id="v1", text="hi"
        )
    ]
    assert chunks == [b"AAAA", b"BBBB"]


@respx.mock
async def test_synthesize_stream_ignores_non_audio_frames():
    """非音频帧（心跳、错误体、坏 JSON、[DONE]）必须被跳过，不能污染 PCM。"""
    from app.tts import synthesize_stream

    body = (
        b"\n\n".join(
            [
                b"data: " + b'{"output":{}}',
                b"data: " + b'{"output":{"audio":{}}}',
                b"data: " + b"not-json",
                b"data: " + b'{"output":{"audio":{"data":"QUJD"}}}',
                b"data: [DONE]",
            ]
        )
        + b"\n\n"
    )
    respx.post(SPEECH_SYNTH).respond(content=body, headers={"Content-Type": "text/event-stream"})

    chunks = [
        c
        async for c in synthesize_stream(
            api_key="k", model="qwen-audio-3.1-tts-flash", voice_id="v1", text="hi"
        )
    ]
    assert chunks == [b"ABC"]


@respx.mock
async def test_synthesize_stream_upstream_error_yields_nothing():
    """上游 4xx 时流式端点不能抛异常（响应头已发出，无法改状态码），只能是 0 块。"""
    from app.tts import synthesize_stream

    respx.post(SPEECH_SYNTH).respond(status_code=400, json={"code": "InvalidParameter"})

    chunks = [
        c
        async for c in synthesize_stream(
            api_key="k", model="qwen-audio-3.1-tts-flash", voice_id="v1", text="hi"
        )
    ]
    assert chunks == []


@respx.mock
async def test_synthesize_stream_falls_back_to_whole_audio_for_legacy_models():
    """qwen3-tts 家族不支持 SSE：退回整句合成，仍然产出「一块完整音频」。"""
    from app.tts import synthesize_stream

    respx.post(SYNTH).respond(json={"output": {"audio": {"url": AUDIO}}})
    respx.get(AUDIO).respond(content=b"RIFF" + b"\x00" * 8)

    chunks = [
        c
        async for c in synthesize_stream(
            api_key="k", model="qwen3-tts-flash", voice_id="v1", text="hi"
        )
    ]
    assert len(chunks) == 1
    assert chunks[0].startswith(b"RIFF")


@respx.mock
async def test_tts_stream_endpoint_contract():
    """端点契约：octet-stream + 音频头 + 正文是各块拼接。"""
    respx.post(SPEECH_SYNTH).respond(
        content=_sse(b"ABCD", b"EFGH"), headers={"Content-Type": "text/event-stream"}
    )

    resp = TestClient(app).post(
        "/agent/tts/stream",
        json={"userId": 1, "apiKey": "k", "voiceId": "v1", "text": "hi"},
        headers={"X-User-Id": "1"},
    )
    assert resp.status_code == 200
    assert resp.headers["content-type"].startswith("application/octet-stream")
    assert resp.headers["x-audio-mime"] == "audio/pcm"
    assert resp.headers["x-audio-sample-rate"] == "24000"
    assert resp.content == b"ABCDEFGH"


def test_tts_stream_endpoint_rejects_user_mismatch():
    resp = TestClient(app).post(
        "/agent/tts/stream",
        json={"userId": 1, "apiKey": "k", "voiceId": "v1", "text": "hi"},
        headers={"X-User-Id": "2"},
    )
    assert resp.status_code == 403


# ─────────────────────────── 端点归一化 ───────────────────────────


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        (None, "https://dashscope.aliyuncs.com"),
        ("", "https://dashscope.aliyuncs.com"),
        ("https://dashscope.aliyuncs.com", "https://dashscope.aliyuncs.com"),
        ("https://DashScope.Aliyuncs.com/api/v1", "https://dashscope.aliyuncs.com"),
        ("https://dashscope-intl.aliyuncs.com/", "https://dashscope-intl.aliyuncs.com"),
        ("https://dashscope.aliyuncs.com:443", "https://dashscope.aliyuncs.com"),
    ],
)
def test_normalize_base_url_accepts(raw, expected):
    assert normalize_base_url(raw) == expected


@pytest.mark.parametrize(
    ("raw", "message"),
    [
        ("http://dashscope.aliyuncs.com", "API 地址仅支持 https"),
        ("https://evil.example.com", "API 地址仅支持 dashscope.aliyuncs.com / dashscope-intl.aliyuncs.com"),
        ("https://dashscope.aliyuncs.com.evil.com", "API 地址仅支持 dashscope.aliyuncs.com / dashscope-intl.aliyuncs.com"),
        ("https://user:pw@dashscope.aliyuncs.com", "API 地址不能包含用户信息"),
        ("https://dashscope.aliyuncs.com:8443", "API 地址不支持自定义端口"),
        ("https://dash scope.aliyuncs.com", "API 地址格式无效"),
    ],
)
def test_normalize_base_url_rejects(raw, message):
    with pytest.raises(DashScopeEndpointError) as exc:
        normalize_base_url(raw)
    assert str(exc.value) == message


def test_normalize_model():
    assert normalize_model(None, "m-1", "HTTP") == "m-1"
    assert normalize_model("qwen3-tts-vc-2026-01-22", "m-1", "HTTP") == "qwen3-tts-vc-2026-01-22"
    with pytest.raises(DashScopeEndpointError) as exc:
        normalize_model("bad model!", "m-1", "Realtime")
    assert str(exc.value) == "Realtime 模型名无效"


def test_url_builders():
    assert enroll_url(None) == ENROLL
    assert synth_url(None) == SYNTH
    assert realtime_ws_url("https://dashscope-intl.aliyuncs.com") == (
        "wss://dashscope-intl.aliyuncs.com/api-ws/v1/realtime"
    )


@pytest.mark.parametrize(
    ("url", "allowed"),
    [
        ("https://dashscope.aliyuncs.com/a.wav", True),
        ("https://dashscope-result.oss-cn-beijing.aliyuncs.com/a.wav", True),
        ("https://dashscope-intl.aliyuncs.com/a.wav", True),
        # DashScope 的签名 OSS 地址实际以 http 下发 —— 域名合法就得放行，
        # scheme 由 normalize_audio_url 升级（Java 原实现卡 https，静默丢音频）
        ("http://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/a.wav", True),
        ("https://dashscope-intl.aliyuncs.com.evil.com/a.wav", False),
        ("ftp://dashscope.aliyuncs.com/a.wav", False),
        ("https://evil.com/a.wav", False),
        ("", False),
        (None, False),
    ],
)
def test_is_allowed_audio_host(url, allowed):
    assert is_allowed_audio_host(url) is allowed


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ("https://x.oss-cn-beijing.aliyuncs.com/a.wav", "https://x.oss-cn-beijing.aliyuncs.com/a.wav"),
        ("http://x.oss-cn-beijing.aliyuncs.com/a.wav?sig=1", "https://x.oss-cn-beijing.aliyuncs.com/a.wav?sig=1"),
        ("http://evil.com/a.wav", None),
        (None, None),
    ],
)
def test_normalize_audio_url(raw, expected):
    assert normalize_audio_url(raw) == expected


def test_guess_mime_type():
    assert guess_mime_type(None, b"RIFF" + b"\x00" * 20) == "audio/wav"
    assert guess_mime_type("https://x/a.mp3", b"\x00" * 20) == "audio/mpeg"
    assert guess_mime_type(None, b"\x00" * 4) == "audio/wav"


def test_sanitize_helpers():
    # prefix 规则（平台实测）：只保留字母数字、最长 10 位
    assert sanitize_preferred_name("U23_C52") == "u23c52"
    assert sanitize_preferred_name("音色 一号") == "custom"  # 全被过滤 → 兜底
    assert sanitize_preferred_name("") == "custom"
    assert sanitize_preferred_name("a" * 50) == "a" * 10
    assert sanitize_preferred_name("Klee-RT") == "kleert"
    assert sanitize_user_message("key sk-abcdefgh123456 leaked") == "key [redacted] leaked"
    assert sanitize_user_message("") == "语音报名失败"
    assert len(sanitize_user_message("x" * 300)) == 160


# ─────────────────────────── 音色注册 ───────────────────────────


def _enroll_sequence(*bodies):
    """按顺序返回多次响应：第 1 次是 create_voice，之后是 query_voice。"""
    queue = [httpx.Response(200, json=b) for b in bodies]
    return lambda request: queue.pop(0)


@respx.mock
async def test_create_voice_success_payload(monkeypatch):
    monkeypatch.setattr("app.voice_enrollment._POLL_INTERVAL_S", 0)
    route = respx.post(ENROLL).mock(side_effect=_enroll_sequence(
        {"output": {"voice_id": "voice-http-1"}},
        {"output": {"status": "OK"}},
    ))
    voice = await create_voice(
        api_key=" sk-test ",
        base_url=None,
        target_model="qwen-audio-3.1-tts-flash",
        preferred_name="U23_C52",
        audio_bytes=b"RIFFdata",
        mime_type="audio/wav",
        language="zh",
    )
    assert voice == "voice-http-1"

    create_body = route.calls[0].request.content.decode()
    assert route.calls[0].request.headers["Authorization"] == "Bearer sk-test"
    assert '"action": "create_voice"' in create_body or '"action":"create_voice"' in create_body
    assert '"model": "voice-enrollment"' in create_body or '"model":"voice-enrollment"' in create_body
    assert "qwen-audio-3.1-tts-flash" in create_body
    assert '"prefix": "u23c52"' in create_body or '"prefix":"u23c52"' in create_body
    assert "data:audio/wav;base64," in create_body
    # 旧接口字段不应再出现（这正是线上 PipelineNotFound 的来源）
    assert "preferred_name" not in create_body
    assert "qwen-voice-enrollment" not in create_body

    # 第二次必须是部署状态轮询，否则拿到的音色还不能用
    assert route.call_count == 2
    poll_body = route.calls[1].request.content.decode()
    assert "query_voice" in poll_body
    assert "voice-http-1" in poll_body


@respx.mock
async def test_create_voice_waits_for_deploying_then_ok(monkeypatch):
    """刚创建是 DEPLOYING，必须等到 OK 才返回 —— 这正是旧实现漏掉的一步。"""
    monkeypatch.setattr("app.voice_enrollment._POLL_INTERVAL_S", 0)
    route = respx.post(ENROLL).mock(side_effect=_enroll_sequence(
        {"output": {"voice_id": "v1"}},
        {"output": {"status": "DEPLOYING"}},
        {"output": {"status": "DEPLOYING"}},
        {"output": {"status": "OK"}},
    ))
    assert await create_voice(
        api_key="sk-x", base_url=None, target_model="m", preferred_name="p", audio_bytes=b"x"
    ) == "v1"
    assert route.call_count == 4


@respx.mock
async def test_create_voice_fails_when_deploy_status_is_bad(monkeypatch):
    monkeypatch.setattr("app.voice_enrollment._POLL_INTERVAL_S", 0)
    respx.post(ENROLL).mock(side_effect=_enroll_sequence(
        {"output": {"voice_id": "v1"}},
        {"output": {"status": "FAILED"}},
    ))
    with pytest.raises(VoiceEnrollmentError) as exc:
        await create_voice(
            api_key="sk-x", base_url=None, target_model="m", preferred_name="p", audio_bytes=b"x"
        )
    assert "音色状态异常" in str(exc.value)


@respx.mock
async def test_create_voice_provider_error_is_sanitized():
    respx.post(ENROLL).respond(
        status_code=400,
        json={"code": "InvalidApiKey", "message": "bad key sk-abcdefgh12345678 for request"},
    )
    with pytest.raises(VoiceEnrollmentError) as exc:
        await create_voice(
            api_key="sk-x",
            base_url=None,
            target_model="m",
            preferred_name="p",
            audio_bytes=b"x",
        )
    assert "[redacted]" in str(exc.value)
    assert "sk-abcdefgh12345678" not in str(exc.value)


@respx.mock
async def test_create_voice_without_voice_id():
    respx.post(ENROLL).respond(json={"output": {}})
    with pytest.raises(VoiceEnrollmentError) as exc:
        await create_voice(
            api_key="sk-x", base_url=None, target_model="m", preferred_name="p", audio_bytes=b"x"
        )
    assert str(exc.value) == "语音报名未返回 voice_id"


@respx.mock
async def test_create_voice_transport_error_message():
    respx.post(ENROLL).mock(side_effect=httpx.ConnectError("boom"))
    with pytest.raises(VoiceEnrollmentError) as exc:
        await create_voice(
            api_key="sk-x", base_url=None, target_model="m", preferred_name="p", audio_bytes=b"x"
        )
    assert str(exc.value) == "语音报名失败，请检查 API Key、地址与音频后重试"


@respx.mock
async def test_delete_voice_quietly_never_raises():
    respx.post(ENROLL).mock(side_effect=httpx.ConnectError("boom"))
    await delete_voice_quietly(api_key="sk-x", base_url=None, voice_id="v1")  # 不抛即通过


@respx.mock
async def test_delete_voice_quietly_skips_without_inputs():
    route = respx.post(ENROLL).respond(json={})
    await delete_voice_quietly(api_key="", base_url=None, voice_id="v1")
    await delete_voice_quietly(api_key="sk-x", base_url=None, voice_id=None)
    assert route.call_count == 0


@respx.mock
async def test_delete_voice_quietly_uses_delete_voice_action():
    """动作名必须是 delete_voice + voice_id。

    实测：`action:delete` 会返回 400 invalid action；
    `delete_voice` 配 `voice` 字段会报缺参数。这条守护防止再退回错的名字。
    """
    route = respx.post(ENROLL).respond(json={"output": {}})
    await delete_voice_quietly(api_key="sk-x", base_url=None, voice_id="v1")
    assert route.call_count == 1
    body = route.calls[0].request.content.decode()
    assert "delete_voice" in body
    assert "voice_id" in body
    assert '"action": "delete"' not in body and '"action":"delete"' not in body


# ─────────────────────────── 非实时合成 ───────────────────────────


@respx.mock
async def test_synthesize_success():
    respx.post(SYNTH).respond(json={"output": {"audio": {"url": AUDIO}}})
    respx.get(AUDIO).respond(content=b"RIFF" + b"\x00" * 20)
    audio = await synthesize(
        api_key="sk-x", model="qwen3-tts-vc-2026-01-22", voice_id="v1", text=" 你好 "
    )
    assert audio is not None
    assert audio.mime_type == "audio/wav"
    assert audio.audio == b"RIFF" + b"\x00" * 20
    assert audio.size == 24
    body = respx.calls[0].request.content.decode()
    assert '"language_type": "Chinese"' in body or '"language_type":"Chinese"' in body
    assert '"voice": "v1"' in body or '"voice":"v1"' in body


@pytest.mark.parametrize(
    "kwargs",
    [
        {"text": ""},
        {"text": "   "},
        {"voice_id": ""},
        {"api_key": ""},
    ],
)
async def test_synthesize_returns_none_for_missing_input(kwargs):
    payload = {
        "api_key": "sk-x",
        "model": "m",
        "voice_id": "v1",
        "text": "hi",
    }
    payload.update(kwargs)
    assert await synthesize(**payload) is None


@respx.mock
async def test_synthesize_retries_transient_then_gives_up():
    route = respx.post(SYNTH).respond(status_code=503, text="busy")
    assert await synthesize(api_key="k", model="qwen3-tts-flash", voice_id="v1", text="hi") is None
    assert route.call_count == 2


@respx.mock
async def test_synthesize_business_error_does_not_retry():
    route = respx.post(SYNTH).respond(status_code=400, json={"code": "BadRequest"})
    assert await synthesize(api_key="k", model="qwen3-tts-flash", voice_id="v1", text="hi") is None
    assert route.call_count == 1


@respx.mock
async def test_synthesize_second_attempt_succeeds():
    respx.post(SYNTH).mock(
        side_effect=[
            httpx.Response(503, text="busy"),
            httpx.Response(200, json={"output": {"audio": {"url": AUDIO}}}),
        ]
    )
    respx.get(AUDIO).respond(content=b"RIFF" + b"\x00" * 20)
    audio = await synthesize(api_key="k", model="qwen3-tts-flash", voice_id="v1", text="hi")
    assert audio is not None


@respx.mock
async def test_synthesize_rejects_foreign_audio_host():
    respx.post(SYNTH).respond(json={"output": {"audio": {"url": "https://evil.com/a.wav"}}})
    download = respx.get("https://evil.com/a.wav").respond(content=b"RIFF")
    assert await synthesize(api_key="k", model="qwen3-tts-flash", voice_id="v1", text="hi") is None
    assert download.call_count == 0


@respx.mock
async def test_synthesize_download_5xx_retries():
    respx.post(SYNTH).respond(json={"output": {"audio": {"url": AUDIO}}})
    route = respx.get(AUDIO).respond(status_code=502)
    assert await synthesize(api_key="k", model="qwen3-tts-flash", voice_id="v1", text="hi") is None
    assert route.call_count == 2


@respx.mock
async def test_synthesize_invalid_base_url_returns_none():
    route = respx.post(SYNTH).respond(json={})
    assert (
        await synthesize(
            api_key="k", model="qwen3-tts-flash", voice_id="v1", text="hi", base_url="http://evil.com"
        )
        is None
    )
    assert route.call_count == 0


@respx.mock
async def test_synthesize_uses_custom_base_url():
    custom = "https://dashscope-intl.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation"
    route = respx.post(custom).respond(json={"output": {"audio": {"url": AUDIO}}})
    respx.get(AUDIO).respond(content=b"RIFF" + b"\x00" * 20)
    audio = await synthesize(
        api_key="k",
        model="qwen3-tts-flash",
        voice_id="v1",
        text="hi",
        base_url="https://dashscope-intl.aliyuncs.com",
    )
    assert audio is not None
    assert route.call_count == 1


@respx.mock
async def test_synthesize_upgrades_http_audio_url_to_https():
    """DashScope 下发 http 签名地址时，按 https 下载 —— 不走明文，也不丢音频。"""
    plain_url = "http://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/a.wav"
    secure_url = "https://dashscope-result-bj.oss-cn-beijing.aliyuncs.com/a.wav"
    respx.post(SYNTH).respond(json={"output": {"audio": {"url": plain_url}}})
    secure = respx.get(secure_url).respond(content=b"RIFF" + b"\x00" * 20)
    plain = respx.get(plain_url).respond(content=b"RIFF")
    audio = await synthesize(api_key="k", model="qwen3-tts-flash", voice_id="v1", text="hi")
    assert audio is not None
    assert secure.call_count == 1
    assert plain.call_count == 0


def test_is_transient_network_failure():
    assert is_transient_network_failure(httpx.ConnectError("x")) is True
    assert is_transient_network_failure(httpx.ConnectTimeout("x")) is True
    # 读超时不算瞬断（请求已发出，重试会重复扣费）
    assert is_transient_network_failure(httpx.ReadTimeout("x")) is False
    assert is_transient_network_failure(RuntimeError("connection reset by peer")) is True
    assert is_transient_network_failure(RuntimeError("bad request")) is False
    assert is_transient_network_failure(None) is False


# ─────────────────────────── HTTP 契约 ───────────────────────────

client = TestClient(app)

ENROLL_BODY = {
    "userId": 23,
    "apiKey": "sk-test",
    "apiBaseUrl": None,
    "targetModel": "qwen3-tts-vc-2026-01-22",
    "preferredName": "u23_c52",
    "audioBase64": base64.b64encode(b"RIFFdata").decode(),
    "mimeType": "audio/wav",
    "language": "zh",
}


@respx.mock
def test_endpoint_voice_enroll_ok(monkeypatch):
    monkeypatch.setattr("app.voice_enrollment._POLL_INTERVAL_S", 0)
    respx.post(ENROLL).mock(side_effect=_enroll_sequence(
        {"output": {"voice_id": "v-http"}},
        {"output": {"status": "OK"}},
    ))
    response = client.post("/agent/voice/enroll", json=ENROLL_BODY, headers={"X-User-Id": "23"})
    assert response.status_code == 200
    assert response.json() == {"voice": "v-http"}


@respx.mock
def test_endpoint_voice_enroll_error_shape():
    respx.post(ENROLL).respond(status_code=400, json={"message": "bad"})
    response = client.post("/agent/voice/enroll", json=ENROLL_BODY, headers={"X-User-Id": "23"})
    assert response.status_code == 200
    body = response.json()
    assert body["error"] == "enroll_failed"
    assert body["message"] == "bad"


def test_endpoint_voice_enroll_rejects_bad_base64():
    body = dict(ENROLL_BODY, audioBase64="!!!not-base64!!!")
    response = client.post("/agent/voice/enroll", json=body, headers={"X-User-Id": "23"})
    assert response.status_code == 200
    assert response.json()["error"] == "invalid_audio"


@respx.mock
def test_endpoint_voice_enroll_rejects_bad_base_url():
    response = client.post(
        "/agent/voice/enroll",
        json=dict(ENROLL_BODY, apiBaseUrl="http://evil.com"),
        headers={"X-User-Id": "23"},
    )
    assert response.status_code == 200
    body = response.json()
    assert body["error"] == "enroll_rejected"
    assert "仅支持 https" in body["message"]


def test_endpoint_user_mismatch_forbidden():
    response = client.post("/agent/voice/enroll", json=ENROLL_BODY, headers={"X-User-Id": "99"})
    assert response.status_code == 403


@respx.mock
def test_endpoint_voice_delete_always_ok():
    respx.post(ENROLL).mock(side_effect=httpx.ConnectError("boom"))
    response = client.post(
        "/agent/voice/delete",
        json={"userId": 23, "apiKey": "sk-test", "voiceId": "v1"},
        headers={"X-User-Id": "23"},
    )
    assert response.status_code == 200
    assert response.json() == {"deleted": True}


@respx.mock
def test_endpoint_tts_synthesize_returns_raw_audio():
    respx.post(SYNTH).respond(json={"output": {"audio": {"url": AUDIO}}})
    respx.get(AUDIO).respond(content=b"RIFF" + b"\x00" * 20)
    response = client.post(
        "/agent/tts/synthesize",
        json={
            "userId": 23,
            "apiKey": "sk-test",
            "model": "qwen3-tts-vc-2026-01-22",
            "voiceId": "v1",
            "text": "在呢",
            "languageType": "Chinese",
        },
        headers={"X-User-Id": "23"},
    )
    assert response.status_code == 200
    assert response.headers["content-type"] == "audio/wav"
    assert response.content == b"RIFF" + b"\x00" * 20


@respx.mock
def test_endpoint_tts_synthesize_204_when_unavailable():
    respx.post(SYNTH).respond(status_code=400, json={})
    response = client.post(
        "/agent/tts/synthesize",
        json={"userId": 23, "apiKey": "k", "model": "m", "voiceId": "v1", "text": "hi"},
        headers={"X-User-Id": "23"},
    )
    assert response.status_code == 204
    assert response.content == b""


def test_endpoint_capabilities_declares_voice():
    payload = client.get("/agent/capabilities").json()
    assert payload["voiceEnrollment"] is True
    assert payload["ttsSynthesize"] is True
    assert payload["realtimeTts"] is False
    assert DEFAULT_BASE == "https://dashscope.aliyuncs.com"
