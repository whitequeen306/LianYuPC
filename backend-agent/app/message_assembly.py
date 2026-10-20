"""聊天消息组装：对齐 Java `ChatTurnMessageAssembler` 的三处细节。

迁移背景：LangGraph 版 `chat_turn.py` 第一步只做了最基础的 system + history 映射，
会漏掉下面三类行为，且其中一项是**安全措施**（提示注入防护），不能凭印象重写：

1. 当前用户消息的 XML 包裹（`UserInputSanitizer.wrapStoredTextForModel`）
2. 图片消息的历史占位归一（`ImageMessageHistoryText.forHistory`）
3. 语音通话 turn 消息过滤（`audioUrl == "system/voice-call-turn"`）

所有常量与分支都照抄 Java 源码，改动需同步对照。
"""

from __future__ import annotations

import re
from typing import Any

MAX_USER_MESSAGE_CHARS = 8_000

# ImageMessageHistoryText 的两个占位词（顺序与文案都不能改）
GENERIC_STORED = "（用户发送了一张图片）"
GENERIC_SHORT = "（用户发了一张图片）"

VOICE_CALL_TURN_MARKER = "system/voice-call-turn"

_CONTROL_CHARS = re.compile(r"[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]")
_MULTI_SPACE = re.compile(r"[ \t]{2,}")
_USER_MESSAGE_TAG = re.compile(r"(?s)<user_message[^>]*>|</user_message>")
_PLACEHOLDER_WITH_DESC = re.compile(r"（用户发了一张图片（[^）]+））")
_IMAGE_DESC = re.compile(r"图片内容[：:]\s*(.+?)(?:\n|$)")


def clean_plain_text(raw: str | None, max_chars: int = MAX_USER_MESSAGE_CHARS) -> str:
    """对齐 Java `UserInputSanitizer.cleanPlainText`：去控制字符、折叠空白、截断。"""
    text = _CONTROL_CHARS.sub("", raw or "")
    text = _MULTI_SPACE.sub(" ", text)
    text = text.strip()
    return text[:max_chars] if len(text) > max_chars else text


def escape_xml(text: str) -> str:
    """对齐 Java `escapeXml`（顺序不可换：& 必须最先替换）。"""
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def wrap_stored_text_for_model(stored: str | None) -> str:
    """对齐 Java `wrapStoredTextForModel`：用户输入按不可信数据包裹。

    这是**提示注入防护**：用户文本进模型前必须落在 `<user_message trusted="false">`
    里，否则用户可以让模型把内容当成系统指令。
    """
    cleaned = clean_plain_text(stored)
    if not cleaned:
        return '<user_message trusted="false"></user_message>'
    return f'<user_message trusted="false">\n{escape_xml(cleaned)}\n</user_message>'


def image_placeholder(image_description: str | None) -> str:
    """对齐 Java `placeholder`：有描述则带上，否则用短占位。"""
    desc = (image_description or "").strip()
    if not desc:
        return GENERIC_SHORT
    return f"（用户发了一张图片（{desc}））"


def _extract_embedded_placeholder(content: str) -> str | None:
    if "（用户发了一张图片" not in content:
        return None
    found = _PLACEHOLDER_WITH_DESC.search(content)
    if found:
        return found.group(0)
    if GENERIC_SHORT in content:
        return GENERIC_SHORT
    return None


def _extract_description_from_model_text(content: str | None) -> str | None:
    if not content or not content.strip():
        return None
    stripped = _USER_MESSAGE_TAG.sub("", content).strip()
    found = _IMAGE_DESC.search(stripped)
    if found:
        desc = found.group(1).strip()
        return desc or None
    return None


def image_history_text(stored_content: str | None, has_image: bool) -> str:
    """对齐 Java `ImageMessageHistoryText.forHistory`。"""
    if not has_image:
        return stored_content or ""
    existing = (stored_content or "").strip()
    embedded = _extract_embedded_placeholder(existing)
    if embedded is not None:
        return embedded
    placeholder = image_placeholder(_extract_description_from_model_text(existing))
    if not existing or existing == GENERIC_STORED or existing == GENERIC_SHORT:
        return placeholder
    if placeholder in existing:
        return existing
    return f"{existing}\n{placeholder}"


def is_voice_call_turn(item: dict[str, Any]) -> bool:
    """对齐 Java `isVoiceCallTurnMessage`：通话逐轮消息不喂给文字聊模型。"""
    audio_url = item.get("audioUrl") or item.get("audio_url")
    return bool(audio_url) and str(audio_url).strip() == VOICE_CALL_TURN_MARKER


def is_empty_user_message_xml(content: str | None) -> bool:
    """对齐 Java `isEmptyUserMessageXml`：只剩空壳标签时视为空。"""
    return not _USER_MESSAGE_TAG.sub("", content or "").strip()


__all__ = [
    "GENERIC_SHORT",
    "GENERIC_STORED",
    "MAX_USER_MESSAGE_CHARS",
    "VOICE_CALL_TURN_MARKER",
    "clean_plain_text",
    "escape_xml",
    "image_history_text",
    "image_placeholder",
    "is_empty_user_message_xml",
    "is_voice_call_turn",
    "wrap_stored_text_for_model",
]
