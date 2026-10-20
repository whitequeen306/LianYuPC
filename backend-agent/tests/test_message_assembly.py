"""消息组装：对齐 Java `ChatTurnMessageAssembler` 的三处细节。

重点覆盖 XML 包裹（提示注入防护）与图片占位，因为它们会改变喂给模型的内容。
"""

import pytest

from app.message_assembly import (
    GENERIC_SHORT,
    GENERIC_STORED,
    clean_plain_text,
    escape_xml,
    image_history_text,
    image_placeholder,
    is_empty_user_message_xml,
    is_voice_call_turn,
    wrap_stored_text_for_model,
)


# ── XML 包裹（注入防护）──


def test_wrap_uses_untrusted_marker_and_newlines():
    out = wrap_stored_text_for_model("你好")
    assert out == '<user_message trusted="false">\n你好\n</user_message>'


def test_wrap_escapes_xml_specials():
    out = wrap_stored_text_for_model("<script>a & b</script>")
    assert "<script>" not in out
    assert "&lt;script&gt;" in out
    assert "a &amp; b" in out


def test_escape_order_ampersand_first():
    # 若先替换 < 再替换 &，会得到 &amp;lt; —— 顺序错了就重复转义
    assert escape_xml("<&>") == "&lt;&amp;&gt;"


def test_wrap_blank_returns_empty_tag():
    assert wrap_stored_text_for_model("   ") == '<user_message trusted="false"></user_message>'
    assert wrap_stored_text_for_model(None) == '<user_message trusted="false"></user_message>'


def test_clean_strips_control_chars_and_truncates():
    assert clean_plain_text("a\x00b\x07c") == "abc"
    assert len(clean_plain_text("x" * 9000)) == 8000


def test_empty_user_message_xml_detected():
    assert is_empty_user_message_xml('<user_message trusted="false"></user_message>')
    assert is_empty_user_message_xml('<user_message trusted="false">\n\n</user_message>')
    assert not is_empty_user_message_xml('<user_message trusted="false">\nhi\n</user_message>')


# ── 图片历史占位 ──


def test_image_placeholder_with_and_without_description():
    assert image_placeholder(None) == GENERIC_SHORT
    assert image_placeholder("") == GENERIC_SHORT
    assert image_placeholder("一只猫") == f"（用户发了一张图片（一只猫））"


def test_non_image_content_passes_through():
    assert image_history_text("普通文本", False) == "普通文本"


def test_generic_stored_becomes_placeholder():
    assert image_history_text(GENERIC_STORED, True) == GENERIC_SHORT


def test_embedded_placeholder_is_extracted_not_duplicated():
    content = "看这个（用户发了一张图片（一只猫））好看吗"
    out = image_history_text(content, True)
    assert out == "（用户发了一张图片（一只猫））"
    assert out.count("用户发了一张图片") == 1


def test_description_extracted_from_model_text():
    content = "图片内容：一只橘猫躺在沙发上\n用户：可爱吗"
    out = image_history_text(content, True)
    assert "一只橘猫躺在沙发上" in out


def test_caption_kept_and_placeholder_appended():
    out = image_history_text("这是我家的猫", True)
    assert out.startswith("这是我家的猫")
    assert "用户发了一张图片" in out
    assert "\n" in out


# ── 语音通话 turn 过滤 ──


def test_voice_call_turn_detected_by_audio_marker():
    assert is_voice_call_turn({"audioUrl": "system/voice-call-turn"})
    assert is_voice_call_turn({"audio_url": " system/voice-call-turn "})
    assert not is_voice_call_turn({"audioUrl": None})
    assert not is_voice_call_turn({})
