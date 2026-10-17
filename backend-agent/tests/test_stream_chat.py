"""单聊流式 SSE 契约测试：帧格式、顺序、语言门替换、错误收尾。

契约来源：Java `SseChatStreamHelper`（客户端依赖，资产清单第 4 条）。
"""

import json

import httpx
import pytest
import respx

from app.gateway import ModelGateway
from app.http_client import outbound_timeout
from app.resilience import reset_breakers, reset_lanes
from app.stream_chat import DONE_FRAME, sse_content, sse_error, sse_replace, stream_chat_events

BASE = "https://fake/v1"


@pytest.fixture(autouse=True)
def _reset():
    reset_breakers()
    reset_lanes()
    yield
    reset_breakers()
    reset_lanes()


def _client():
    return httpx.AsyncClient(timeout=outbound_timeout())


def _upstream_sse(*deltas: str) -> httpx.Response:
    body = "".join(
        f'data: {json.dumps({"choices": [{"delta": {"content": d}}]})}\n\n' for d in deltas
    ) + "data: [DONE]\n\n"
    return httpx.Response(200, text=body, headers={"content-type": "text/event-stream"})


async def _collect(**kwargs) -> list[str]:
    defaults = dict(
        base_url=BASE,
        api_key="k",
        model="m",
        messages=[{"role": "user", "content": "在吗"}],
        temperature=0.8,
        max_tokens=100,
    )
    defaults.update(kwargs)
    frames: list[str] = []
    client = _client()
    try:
        async for frame in stream_chat_events(ModelGateway(client=client), **defaults):
            frames.append(frame)
    finally:
        await client.aclose()
    return frames


# ── 帧格式 ──


def test_frame_builders_match_java_contract():
    assert sse_content("你好") == 'data: {"content": "你好"}\n\n'
    assert sse_replace("全文") == 'data: {"replace": "全文"}\n\n'
    assert sse_error("失败") == 'data: {"error": "失败"}\n\n'
    assert DONE_FRAME == "data: [DONE]\n\n"


@pytest.mark.asyncio
@respx.mock
async def test_stream_emits_content_frames_then_done():
    respx.post(f"{BASE}/chat/completions").mock(return_value=_upstream_sse("你", "好", "呀"))
    frames = await _collect()
    assert frames == [
        'data: {"content": "你"}\n\n',
        'data: {"content": "好"}\n\n',
        'data: {"content": "呀"}\n\n',
        DONE_FRAME,
    ]


@pytest.mark.asyncio
@respx.mock
async def test_stream_without_expected_language_skips_gate():
    """未指定期望语言 → 不纠错、不发 replace。"""
    respx.post(f"{BASE}/chat/completions").mock(return_value=_upstream_sse("I miss you"))
    frames = await _collect()
    assert not any('"replace"' in f for f in frames)
    assert frames[-1] == DONE_FRAME


@pytest.mark.asyncio
@respx.mock
async def test_stream_error_frame_then_done_on_http_failure():
    """上游 5xx → error 帧 + [DONE]（客户端据 error 展示提示并结束）。"""
    respx.post(f"{BASE}/chat/completions").respond(status_code=500)
    frames = await _collect()
    assert any('"error"' in f for f in frames)
    assert frames[-1] == DONE_FRAME


@pytest.mark.asyncio
@respx.mock
async def test_stream_empty_content_reports_error():
    """上游返回空内容 → 对齐 Java「对方没有回复内容」。"""
    respx.post(f"{BASE}/chat/completions").mock(return_value=_upstream_sse())
    frames = await _collect()
    assert any("对方没有回复内容" in f for f in frames)
    assert frames[-1] == DONE_FRAME


@pytest.mark.asyncio
@respx.mock
async def test_stream_language_gate_emits_replace():
    """期望中文但先回英文 → 阻塞重生成后发 replace 帧（对齐 Java 语言门）。"""
    calls = {"n": 0}

    def handler(request):
        calls["n"] += 1
        if calls["n"] == 1:
            return _upstream_sse("I miss you tonight")
        return httpx.Response(
            200, json={"choices": [{"message": {"content": "今晚有点想你"}}]}
        )

    respx.post(f"{BASE}/chat/completions").mock(side_effect=handler)
    frames = await _collect(expected_language="zh")
    replace_frames = [f for f in frames if '"replace"' in f]
    assert len(replace_frames) == 1
    assert "今晚有点想你" in replace_frames[0]
    assert frames[-1] == DONE_FRAME
    assert calls["n"] == 2  # 1 次流式 + 1 次纠错


@pytest.mark.asyncio
@respx.mock
async def test_stream_language_gate_skipped_when_already_chinese():
    respx.post(f"{BASE}/chat/completions").mock(return_value=_upstream_sse("今晚早点睡"))
    frames = await _collect(expected_language="zh")
    assert not any('"replace"' in f for f in frames)


@pytest.mark.asyncio
@respx.mock
async def test_stream_does_not_emit_pieces():
    """pieces 由 Java 的 AssistantReplySplitter 产出，本层不许发（契约边界）。"""
    respx.post(f"{BASE}/chat/completions").mock(return_value=_upstream_sse("a", "b"))
    frames = await _collect()
    assert not any('"pieces"' in f for f in frames)
