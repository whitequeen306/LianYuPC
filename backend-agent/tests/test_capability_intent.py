"""动作意图识别 + 「空口承诺」重问 的回归测试。

真实事故：用户说「帮我打开网易云音乐播放大海」，模型只回文字不发 tool_call。
"""

from __future__ import annotations

import pytest

from app.capability.intent import (
    DESKTOP_ACTION_NUDGE,
    last_user_text,
    looks_like_desktop_action,
)


@pytest.mark.parametrize(
    "text",
    [
        "帮我打开网易云音乐播放大海",
        "把音量调到 30",
        "点一下那个蓝色的按钮",
        "在记事本里输入 hello",
        "截个图我看看",
        "看一下当前打开了哪些窗口",
        "帮我搜一下今天的天气",
        "下一首",
        "静音一下吧",
    ],
)
def test_detects_desktop_actions(text: str) -> None:
    assert looks_like_desktop_action(text) is True


@pytest.mark.parametrize(
    "text",
    [
        "",
        None,
        "我今天有点累",
        "你在干嘛呀",
        "给我讲个故事",
        "你喜欢什么颜色",
        "网易云音乐是什么软件",
        "陪我说说话",
    ],
)
def test_ignores_chitchat(text) -> None:
    assert looks_like_desktop_action(text) is False


def test_last_user_text_skips_tool_and_assistant() -> None:
    messages = [
        {"role": "user", "content": "帮我打开网易云"},
        {"role": "assistant", "content": "", "tool_calls": [{"id": "1"}]},
        {"role": "tool", "tool_call_id": "1", "content": "ok"},
    ]
    assert last_user_text(messages) == "帮我打开网易云"


def test_last_user_text_handles_content_parts() -> None:
    messages = [
        {"role": "user", "content": [{"type": "text", "text": "把音量调到 20"}, {"type": "image_url", "image_url": {}}]},
    ]
    assert last_user_text(messages) == "把音量调到 20"


def test_nudge_text_forbids_fake_completion() -> None:
    # 提示词必须明确禁止「没调工具就说已完成」这类话
    for word in ("禁止", "desk_app_launch", "做不到"):
        assert word in DESKTOP_ACTION_NUDGE
