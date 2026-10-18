"""会话摘要块：与 Java 逐字节比对。"""

import json
from pathlib import Path

import pytest

from app.session_summary_block import format_session_summary

GOLDEN = Path(__file__).resolve().parent.parent / "app" / "prompts" / "session_summary_golden.json"


@pytest.fixture(scope="module")
def golden() -> dict:
    assert GOLDEN.exists(), (
        "缺少 session_summary_golden.json：运行 "
        "mvn -pl lianyu-service -am test -Dtest=SessionSummaryGoldenTest"
    )
    return json.loads(GOLDEN.read_text(encoding="utf-8"))


def test_normal_matches_java(golden):
    actual = format_session_summary("用户聊到最近工作压力大，角色安慰了他")
    assert actual == golden["normal"]


def test_padded_text_is_trimmed_like_java(golden):
    """Java 会 trim 摘要，前后空白不应进入 prompt。"""
    assert format_session_summary("  前后有空格  ") == golden["padded"]
    assert not format_session_summary("  前后有空格  ").startswith("\n\n\n")


@pytest.mark.parametrize("text", ["", "   \n  ", None])
def test_blank_returns_none_like_java(golden, text):
    assert format_session_summary(text) is None
    assert golden["empty"] is None and golden["blank"] is None


def test_block_contains_header_and_footer(golden):
    text = format_session_summary("摘要内容")
    assert "本次对话 Earlier 摘要" in text
    assert "以最近消息为准" in text
    assert text.strip().startswith("=== 本次对话")