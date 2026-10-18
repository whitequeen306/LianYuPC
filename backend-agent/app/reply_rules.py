"""回复规则（ReplyBehaviorRuleHook）—— 由 Java 渲染的模板，Python 负责选择与占位替换。

为什么不逐块移植：该 hook 的 `renderZh/renderEn/...` 里大量是**内联拼接**
（`"4. 可拆成1~" + maxPieces + "条短消息…"`、`innerThoughtRuleZh(...)`、
`humanizeBlockZh()`、`realLifeGroundingBlockZh()`），文本块只占一部分。
逐块迁移必须手工拷贝这些内联片段 —— 手抄正是本项目反复要避免的风险。

因此改用「Java 渲染 → 模板化」：
- `reply_rule_templates.json` 由 `ReplyRuleTemplateGoldenTest` 生成，
  persona 位置用 `{{PERSONA}}` 占位
- 组合空间极小：4 语种 × 2（内心独白）× 2（单/多气泡）= 16 条
- Python 只做**选择 + 替换**，文本由构造保证与 Java 一致

未覆盖的组合（如 humanize 关闭）会显式报错，而不是静默返回空串 ——
静默返回空串会让 system prompt 少一大段规则，模型行为变差且无日志。
"""

from __future__ import annotations

import json
from functools import lru_cache
from pathlib import Path

TEMPLATES_PATH = Path(__file__).resolve().parent / "prompts" / "reply_rule_templates.json"


class ReplyRuleTemplateMissing(KeyError):
    """请求了 Java 未生成的组合（通常是新增语言或新增开关）。"""


@lru_cache(maxsize=1)
def _load() -> tuple[str, dict[str, str]]:
    if not TEMPLATES_PATH.exists():
        raise FileNotFoundError(
            f"{TEMPLATES_PATH} missing; run "
            "mvn -pl lianyu-service -am test -Dtest=ReplyRuleTemplateGoldenTest"
        )
    payload = json.loads(TEMPLATES_PATH.read_text(encoding="utf-8"))
    placeholder = payload.get("personaPlaceholder") or "{{PERSONA}}"
    return placeholder, dict(payload.get("templates") or {})


def _lang_key(lang: str | None) -> str:
    code = (lang or "zh").strip()
    lowered = code.lower().replace("_", "-")
    if lowered in ("zh-tw", "zhtw", "zh-hant", "zh-hk"):
        return "zh-TW"
    if lowered.startswith("ja"):
        return "ja"
    if lowered.startswith("en"):
        return "en"
    return "zh"


def render_reply_rules(lang: str | None,
                       persona: str,
                       show_inner_thoughts: bool = True,
                       max_pieces: int = 2) -> str:
    """对齐 ReplyBehaviorRuleHook.render。"""
    placeholder, templates = _load()
    pieces = 2 if int(max_pieces or 1) > 1 else 1
    # 键里的布尔必须是小写：Java 用 String.valueOf(boolean) → "true"/"false"
    key = f"{_lang_key(lang)}|{str(bool(show_inner_thoughts)).lower()}|{pieces}"
    template = templates.get(key)
    if template is None:
        raise ReplyRuleTemplateMissing(
            f"no template for {key}; available: {sorted(templates)}; "
            "regenerate via ReplyRuleTemplateGoldenTest"
        )
    return template.replace(placeholder, persona or "")


def template_keys() -> list[str]:
    _placeholder, templates = _load()
    return sorted(templates)


def reload_templates() -> None:
    _load.cache_clear()


__all__ = [
    "ReplyRuleTemplateMissing",
    "TEMPLATES_PATH",
    "reload_templates",
    "render_reply_rules",
    "template_keys",
]
