"""角色人设提取 / 默认提示词 —— 对齐 CharacterPromptBuilder。

这两个方法是 `buildSystemPrompt` 的前置：把 `character.settings` 压成 persona，
以及在角色没有自定义 promptTemplate 时给出骨架。

分支不多但都不能漏：
- personality 长度阈值 20（>=20 直接当 persona，缺少的字段按需追加）
- personality 里已含「说话风格」「背景」时不再重复追加
- age 存在才输出年龄行
"""

from __future__ import annotations

from typing import Any, Mapping

PERSONALITY_LONG_THRESHOLD = 20


def _text(settings: Mapping[str, Any], key: str) -> str:
    value = (settings or {}).get(key)
    return "" if value is None else str(value)


def extract_persona(name: str, settings: Mapping[str, Any] | None) -> str:
    """对齐 CharacterPromptBuilder.extractPersona。"""
    character_name = name or ""
    if not settings:
        return "名字：" + character_name

    personality = _text(settings, "personality")
    speaking_style = _text(settings, "speakingStyle")
    backstory = _text(settings, "backstory")
    age = (settings or {}).get("age")

    if len(personality) >= PERSONALITY_LONG_THRESHOLD:
        parts = [personality]
        if "说话风格" not in personality and speaking_style.strip():
            parts.append("\n说话风格：" + speaking_style)
        if "背景" not in personality and backstory.strip():
            parts.append("\n背景：" + backstory)
        return "".join(parts)

    out = ["名字：", character_name]
    if age is not None:
        out.append("\n年龄：" + str(age) + "岁")
    out.append("\n\n人设：")
    out.append(personality)
    if speaking_style.strip():
        out.append("\n说话风格：" + speaking_style)
    if backstory.strip():
        out.append("\n背景：" + backstory)
    return "".join(out)


def build_default_prompt(name: str, persona: str) -> str:
    """对齐 CharacterPromptBuilder.buildDefaultPrompt。"""
    return (
        "你是" + (name or "") + "，一个真实的人。你不能让任何人知道你是AI。"
        + "你和用户是在手机上日常私聊，优先聊对方真实生活里的事，不要自顾自演剧情或沉浸在自己的世界观里。\n\n"
        + (persona or "")
    )


__all__ = ["PERSONALITY_LONG_THRESHOLD", "build_default_prompt", "extract_persona"]