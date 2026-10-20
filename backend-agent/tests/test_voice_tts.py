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
    enroll_url,
    guess_mime_type,
    is_allowed_audio_host,
    normalize_audio_url,
    normalize_base_url,
    normalize_model,
    realtime_ws_url,
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
AUDIO = "https://dashscope-result.oss-cn-beijing.aliyuncs.com/a.wav"


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
    assert sanitize_preferred_name("U23_C52") == "u23_c52"
    # 中文/空格/连字符都不是 [a-z0-9_]，逐个变下划线（对齐 Java replaceAll）
    assert sanitize_preferred_name("音色 一号") == "_____"
    assert sanitize_preferred_name("") == "custom"
    assert len(sanitize_preferred_name("a" * 50)) == 32
    assert sanitize_user_message("key sk-abcdefgh123456 leaked") == "key [redacted] leaked"
    assert sanitize_user_message("") == "语音报名失败"
    assert len(sanitize_user_message("x" * 300)) == 160


# ─────────────────────────── 音色注册 ───────────────────────────


@respx.mock
async def test_create_voice_success_payload():
    route = respx.post(ENROLL).respond(json={"output": {"voice": "voice-http-1"}})
    voice = await create_voice(
        api_key=" sk-test ",
        base_url=None,
        target_model="qwen3-tts-vc-2026-01-22",
        preferred_name="U23_C52",
        audio_bytes=b"RIFFdata",
        mime_type="audio/wav",
        language="zh",
    )
    assert voice == "voice-http-1"
    request = route.calls[0].request
    assert request.headers["Authorization"] == "Bearer sk-test"
    body = respx.calls[0].request.content.decode()
    assert '"action": "create"' in body or '"action":"create"' in body
    assert '"target_model": "qwen3-tts-vc-2026-01-22"' in body or '"target_model":"qwen3-tts-vc-2026-01-22"' in body
    assert '"preferred_name": "u23_c52"' in body or '"preferred_name":"u23_c52"' in body
    assert "data:audio/wav;base64," in body
    assert '"model": "qwen-voice-enrollment"' in body or '"model":"qwen-voice-enrollment"' in body


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
async def test_create_voice_without_output_voice():
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
    assert await synthesize(api_key="k", model="m", voice_id="v1", text="hi") is None
    assert route.call_count == 2


@respx.mock
async def test_synthesize_business_error_does_not_retry():
    route = respx.post(SYNTH).respond(status_code=400, json={"code": "BadRequest"})
    assert await synthesize(api_key="k", model="m", voice_id="v1", text="hi") is None
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
    audio = await synthesize(api_key="k", model="m", voice_id="v1", text="hi")
    assert audio is not None


@respx.mock
async def test_synthesize_rejects_foreign_audio_host():
    respx.post(SYNTH).respond(json={"output": {"audio": {"url": "https://evil.com/a.wav"}}})
    download = respx.get("https://evil.com/a.wav").respond(content=b"RIFF")
    assert await synthesize(api_key="k", model="m", voice_id="v1", text="hi") is None
    assert download.call_count == 0


@respx.mock
async def test_synthesize_download_5xx_retries():
    respx.post(SYNTH).respond(json={"output": {"audio": {"url": AUDIO}}})
    route = respx.get(AUDIO).respond(status_code=502)
    assert await synthesize(api_key="k", model="m", voice_id="v1", text="hi") is None
    assert route.call_count == 2


@respx.mock
async def test_synthesize_invalid_base_url_returns_none():
    route = respx.post(SYNTH).respond(json={})
    assert (
        await synthesize(
            api_key="k", model="m", voice_id="v1", text="hi", base_url="http://evil.com"
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
        model="m",
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
    audio = await synthesize(api_key="k", model="m", voice_id="v1", text="hi")
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
def test_endpoint_voice_enroll_ok():
    respx.post(ENROLL).respond(json={"output": {"voice": "v-http"}})
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
