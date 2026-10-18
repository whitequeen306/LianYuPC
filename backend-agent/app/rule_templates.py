"""输出语言规则 / 群聊规则 —— Java 渲染的模板，Python 只做选择与占位替换。

与 `reply_rules.py` 同一策略：这两个 hook 同样是「文本块 + 内联拼接」混写
（群聊规则里 name/other/mention/maxPieces 都是拼接进去的），
逐块迁移必须手抄内联片段。改为 Java 渲染出带占位符的模板，由构造保证一致。

`rule_templates.json` 由 Java `RuleTemplatesGoldenTest` 生成：
- outputLanguage: 4 语种
- groupChat: 4 语种 × 2（单/多气泡）
"""

from __future__ import annotations

import json
from functools import lru_cache
from pathlib import Path

TEMPLATES_PATH = Path(__file__).resolve().parent / "prompts" / "rule_templates.json"


class RuleTemplateMissing(KeyError):
    """请求了 Java 未生成的组合。"""


@lru_cache(maxsize=1)
def _load() -> dict:
    if not TEMPLATES_PATH.exists():
        raise FileNotFoundError(
            f"{TEMPLATES_PATH} missing; run "
            "mvn -pl lianyu-service -am test -Dtest=RuleTemplatesGoldenTest"
        )
    return json.loads(TEMPLATES_PATH.read_text(encoding="utf-8"))


def _lang_key(lang: str | None) -> str:
    code = (lang or "zh").strip().lower().replace("_", "-")
    if code in ("zh-tw", "zhtw", "zh-hant", "zh-hk"):
        return "zh-TW"
    if code.startswith("ja"):
        return "ja"
    if code.startswith("en"):
        return "en"
    return "zh"


def render_output_language_rules(lang: str | None) -> str:
    """对齐 OutputLanguageRuleHook.render。"""
    templates = _load().get("outputLanguage") or {}
    key = _lang_key(lang)
    if key not in templates:
        raise RuleTemplateMissing(f"no outputLanguage template for {key}; available: {sorted(templates)}")
    return templates[key]


def render_group_chat_rules(lang: str | None,
                            character_name: str,
                            other_characters_line: str = "",
                            mention_context: str = "",
                            max_pieces: int = 2,
                            show_inner_thoughts: bool = True) -> str:
    """对齐 GroupChatRuleHook.render。"""
    data = _load()
    templates = data.get("groupChat") or {}
    pieces = 2 if int(max_pieces or 1) > 1 else 1
    key = f"{_lang_key(lang)}|{pieces}|{str(bool(show_inner_thoughts)).lower()}"
    template = templates.get(key)
    if template is None:
        raise RuleTemplateMissing(f"no groupChat template for {key}; available: {sorted(templates)}")
    placeholders = data.get("placeholders") or {}
    return (
        template
        .replace(placeholders.get("name", "{{NAME}}"), character_name or "")
        .replace(placeholders.get("other", "{{OTHER}}"), other_characters_line or "")
        .replace(placeholders.get("mention", "{{MENTION}}"), mention_context or "")
    )


def template_keys() -> dict[str, list[str]]:
    data = _load()
    return {
        "outputLanguage": sorted((data.get("outputLanguage") or {}).keys()),
        "groupChat": sorted((data.get("groupChat") or {}).keys()),
    }


def reload_templates() -> None:
    _load.cache_clear()


__all__ = [
    "RuleTemplateMissing",
    "TEMPLATES_PATH",
    "reload_templates",
    "render_group_chat_rules",
    "render_output_language_rules",
    "template_keys",
]