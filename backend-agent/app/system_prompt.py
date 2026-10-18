"""system prompt 编排（数据块由调用方注入）。

与 Java `CharacterPromptBuilder.buildSystemPrompt` 一一对应：

    base(模板或默认骨架)
    → [记忆块 + 记忆优先级 + 称呼规则]
    → [工具提示]
    → 回复规则 → 输出语言规则 → [情绪块] → 安全边界

**顺序语义**：情绪块故意在回复规则之后（权重更高，避免被「别每句安慰」
这类通用规则盖掉）；安全边界永远最后。

记忆 / 关系 / 情绪的数据来源不在本模块 —— 调用方传入字符串，
本模块只负责编排。这样「逻辑正确」与「数据正确」可以分开验证。
"""

from __future__ import annotations

import json
import re
from functools import lru_cache
from pathlib import Path
from typing import Any, Mapping

from app.character_prompt import build_default_prompt, extract_persona
from app.prompt_assembly import natural_style_block
from app.prompt_fragments import fragment
from app.reply_rules import render_reply_rules
from app.rule_templates import render_output_language_rules

MEMORY_HEADER = "\n\n=== 关于用户的记忆（最新权威信息） ===\n"
_PROFILE_NAME = re.compile(r"【长期记忆/姓名】\s*([^\n]+)")
ADDRESSING_TEMPLATES = Path(__file__).resolve().parent / "prompts" / "addressing_templates.json"


@lru_cache(maxsize=1)
def _addressing_templates() -> tuple[str, dict[str, str]]:
    """称呼规则措辞（由 Java `SystemPromptOrchestrationGoldenTest` 生成，非手抄）。"""
    payload = json.loads(ADDRESSING_TEMPLATES.read_text(encoding="utf-8"))
    return payload.get("namePlaceholder") or "{{NAME}}", dict(payload.get("blocks") or {})


def _lang_key(lang: str | None) -> str:
    code = (lang or "zh").strip().lower().replace("_", "-")
    if code in ("zh-tw", "zhtw", "zh-hant", "zh-hk"):
        return "zh-TW"
    if code.startswith("ja"):
        return "ja"
    if code.startswith("en"):
        return "en"
    return "zh"


def addressing_hint_block(name: str, output_language: str | None) -> str:
    """对齐 `CharacterAddressing.hintBlock` 的完整措辞（占位符替换）。"""
    placeholder, blocks = _addressing_templates()
    template = blocks.get(_lang_key(output_language))
    if not template:
        raise KeyError(f"no addressing template for {output_language}; available: {sorted(blocks)}")
    return template.replace(placeholder, name or "")


def build_user_addressing_rule(memory_context: str, output_language: str | None) -> str:
    """对齐 Java `buildUserAddressingRule`（记忆里有姓名时追加称呼提示）。

    注：Java 还会调用 `CharacterAddressing.hintBlock` 拼具体措辞；
    本函数只负责「是否触发」，措辞模板在下一轮随 addressing 一起迁。
    """
    match = _PROFILE_NAME.search(memory_context or "")
    if not match:
        return ""
    name = match.group(1).strip()
    if not name:
        return ""
    return addressing_hint_block(name, output_language)


def build_system_prompt(
    *,
    character_name: str,
    settings: Mapping[str, Any] | None,
    prompt_template: str | None,
    memory_context: str = "",
    tool_hint: str = "",
    emotion_block: str = "",
    output_language: str | None = "zh",
    enable_chat_tools: bool = False,
    show_inner_thoughts: bool = True,
    max_pieces: int = 2,
) -> str:
    """对齐 `buildSystemPrompt`：纯编排，数据块由调用方给出。"""
    persona = extract_persona(character_name, settings)

    if prompt_template and prompt_template.strip():
        prompt = prompt_template.replace("{{name}}", character_name or "").replace(
            "{{persona}}", persona)
    else:
        prompt = build_default_prompt(character_name, persona)

    memory = (memory_context or "").strip()
    if memory:
        prompt += MEMORY_HEADER + memory_context
        prompt += fragment("CharacterPromptBuilder.java", 71)
        prompt += build_user_addressing_rule(memory_context, output_language)

    if enable_chat_tools and tool_hint:
        prompt += tool_hint

    reply_rules = render_reply_rules(output_language, persona, show_inner_thoughts, max_pieces)
    output_rules = render_output_language_rules(output_language)
    if reply_rules.strip():
        prompt += "\n\n" + reply_rules
    if output_rules.strip():
        prompt += "\n\n" + output_rules
    if emotion_block:
        prompt += emotion_block

    prompt += fragment("UserInputSanitizer.java", 92)
    return prompt


def natural_style_suffix(output_language: str | None, show_inner_thoughts: bool = True) -> str:
    """场景层追加的自然风格块（ChatTurnPromptAssembler 负责，不在 buildSystemPrompt 内）。"""
    return natural_style_block(output_language, show_inner_thoughts)


__all__ = [
    "MEMORY_HEADER",
    "build_system_prompt",
    "addressing_hint_block",
    "build_user_addressing_rule",
    "natural_style_suffix",
]