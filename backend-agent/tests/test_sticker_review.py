"""表情包复核与「用户点名要图」的单元测试。

两类断言分开：
1. `pick_sticker` 的 explicit 通路是纯函数，必须能独立成立（不依赖模型）；
2. `choose_sticker` 的编排要能容忍复核失败/拒绝，且「用户点名」时不能被模型否决掉。
"""

from __future__ import annotations

import pytest

from app.gateway import GatewayResult
from app.sticker_review import choose_sticker, review_sticker
from app.stickers import pick_sticker, wants_sticker

STICKERS = [
    {"url": "/api/public/files/a.png", "emotion": "happy", "tags": "开心,微笑"},
    {"url": "/api/public/files/b.png", "emotion": "angry", "tags": "生气,横眉"},
]


class FakeGateway:
    """只实现 chat()：把「模型说了什么」和「看到了几张图」都记下来。"""

    def __init__(self, content: str = "", error: Exception | None = None) -> None:
        self.content = content
        self.error = error
        self.calls: list[dict] = []

    async def chat(self, **kwargs):
        self.calls.append(kwargs)
        if self.error is not None:
            raise self.error
        return GatewayResult(content=self.content)


def with_images(stickers):
    return [dict(item, imageBase64="aGVsbG8=") for item in stickers]


# ── 明确要图 ──


def test_wants_sticker_matches_explicit_phrases():
    assert wants_sticker("发个表情包")
    assert wants_sticker("来张图嘛")
    assert wants_sticker("给我甩个表情")
    assert not wants_sticker("今天加班到十点")
    assert not wants_sticker("")


def test_explicit_request_bypasses_threshold_and_short_text():
    # 「嗯？」既短又没情绪词：正常不发，点名要就发
    assert pick_sticker("嗯？", STICKERS) is None
    assert pick_sticker("嗯？", STICKERS, explicit=True) is not None


def test_explicit_request_uses_emotion_when_available():
    assert (
        pick_sticker("气死了，发个表情包", STICKERS, explicit=True)
        == "/api/public/files/b.png"
    )


def test_explicit_request_avoids_repeating_last_sticker():
    assert (
        pick_sticker(
            "再来一张", STICKERS, explicit=True, last_url="/api/public/files/a.png"
        )
        == "/api/public/files/b.png"
    )


def test_explicit_request_sends_only_available_sticker_even_if_repeat():
    only = [{"url": "/api/public/files/a.png", "emotion": "happy"}]
    assert (
        pick_sticker("再来一张", only, explicit=True, last_url="/api/public/files/a.png")
        == "/api/public/files/a.png"
    )


# ── 视觉复核 ──


@pytest.mark.asyncio
async def test_review_picks_index_and_skips_sticker_without_bytes():
    # 只有带字节的图会进模型视野，编号按「实际送了哪些」从 0 起算
    gateway = FakeGateway('{"send": true, "index": 0, "reason": "他在生气"}')
    candidates = [
        {"url": "/api/public/files/a.png", "emotion": "happy"},
        {"url": "/api/public/files/b.png", "emotion": "angry", "imageBase64": "aGVsbG8="},
    ]
    decision = await review_sticker(
        gateway, base_url="https://v", api_key="k", model="m",
        user_text="气死了", reply="（皱眉）", candidates=candidates,
    )
    assert decision is not None and decision.url == "/api/public/files/b.png"
    parts = gateway.calls[0]["parts"]
    # 1 段文字说明 + 1 张真正带字节的图
    assert len(parts) == 2


@pytest.mark.asyncio
async def test_review_out_of_range_index_declines():
    gateway = FakeGateway('{"send": true, "index": 5}')
    decision = await review_sticker(
        gateway, base_url="https://v", api_key="k", model="m",
        user_text="开心", reply="真好", candidates=with_images(STICKERS),
    )
    assert decision is not None and decision.url is None


@pytest.mark.asyncio
async def test_review_declines_when_model_says_no():
    gateway = FakeGateway('{"send": false, "reason": "都不贴题"}')
    decision = await review_sticker(
        gateway, base_url="https://v", api_key="k", model="m",
        user_text="今天天气不错", reply="嗯", candidates=with_images(STICKERS),
    )
    assert decision is not None and decision.url is None


@pytest.mark.asyncio
async def test_review_returns_none_when_no_bytes_available():
    gateway = FakeGateway('{"send": true, "index": 0}')
    decision = await review_sticker(
        gateway, base_url="https://v", api_key="k", model="m",
        user_text="开心", reply="真好", candidates=STICKERS,
    )
    assert decision is None
    assert gateway.calls == []


@pytest.mark.asyncio
async def test_review_returns_none_on_gateway_failure():
    gateway = FakeGateway(error=RuntimeError("boom"))
    decision = await review_sticker(
        gateway, base_url="https://v", api_key="k", model="m",
        user_text="开心", reply="真好", candidates=with_images(STICKERS),
    )
    assert decision is None


@pytest.mark.asyncio
async def test_review_avoids_repeating_last_url():
    gateway = FakeGateway('{"send": true, "index": 0}')
    decision = await review_sticker(
        gateway, base_url="https://v", api_key="k", model="m",
        user_text="开心", reply="真好", candidates=with_images(STICKERS),
        last_url="/api/public/files/a.png",
    )
    assert decision is not None and decision.url == "/api/public/files/b.png"


# ── 编排 ──


@pytest.mark.asyncio
async def test_choose_prefers_review_decision():
    gateway = FakeGateway('{"send": true, "index": 1, "reason": "生气"}')
    url = await choose_sticker(
        gateway, base_url="https://v", api_key="k", model="m",
        user_text="气死了", reply="（皱眉）", candidates=with_images(STICKERS),
    )
    assert url == "/api/public/files/b.png"


@pytest.mark.asyncio
async def test_choose_falls_back_to_lexicon_when_review_fails():
    gateway = FakeGateway(error=RuntimeError("timeout"))
    url = await choose_sticker(
        gateway, base_url="https://v", api_key="k", model="m",
        user_text="我今天特别开心", reply="真好。", candidates=STICKERS,
    )
    assert url == "/api/public/files/a.png"


@pytest.mark.asyncio
async def test_choose_skips_sticker_when_model_declines_and_not_explicit():
    gateway = FakeGateway('{"send": false, "reason": "不贴题"}')
    url = await choose_sticker(
        gateway, base_url="https://v", api_key="k", model="m",
        user_text="今天天气不错", reply="嗯", candidates=with_images(STICKERS),
    )
    assert url is None


@pytest.mark.asyncio
async def test_choose_overrides_decline_when_user_asked_explicitly():
    gateway = FakeGateway('{"send": false, "reason": "都不贴题"}')
    url = await choose_sticker(
        gateway, base_url="https://v", api_key="k", model="m",
        user_text="发个表情包", reply="好。", candidates=with_images(STICKERS),
    )
    assert url is not None


@pytest.mark.asyncio
async def test_choose_without_candidates_returns_none():
    gateway = FakeGateway('{"send": true, "index": 0}')
    assert (
        await choose_sticker(
            gateway, base_url="https://v", api_key="k", model="m",
            user_text="开心", reply="真好", candidates=[],
        )
        is None
    )
