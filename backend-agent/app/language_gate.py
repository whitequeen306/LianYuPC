"""语言门 —— Java `OutputLanguageService` + `AiChatService.enforceExpectedLanguage` 的移植。

移植对照：

| Java | 本模块 |
|---|---|
| `OutputLanguageService.detectFromText` | `detect_language()` |
| `OutputLanguageService.matchesExpected` | `matches_expected()` |
| `OutputLanguageService.shouldEnforceLanguageGate` | `should_enforce_language_gate()` |
| `AiChatService.buildLanguageCorrectionInstruction` | `correction_instruction()` |
| `AiChatService.enforceExpectedLanguage`（while 循环，最多 2 次） | LangGraph 图内有界环（见 `graphs/proactive_turn.py`） |

红线（资产清单第 9 条）：**绝不强制改成英文**——期望语言为 EN 时语言门直接放行。
"""

from __future__ import annotations

import re
from enum import Enum

# 对齐 Java OutputLanguage
SUPPORTED = ("zh", "zh-TW", "ja", "en")

_MARKUP_TAG = re.compile(r"<[^>]*>")
_ENTITY_NAMED = re.compile(r"&[a-z]+;")
_ENTITY_NUM = re.compile(r"&#\d+;")


class Language(str, Enum):
    ZH = "zh"
    ZH_TW = "zh-TW"
    JA = "ja"
    EN = "en"


def from_code(code: str | None) -> Language:
    """对齐 Java `OutputLanguage.fromCode`：非法/空 → ZH。"""
    if not code:
        return Language.ZH
    normalized = str(code).strip().lower()
    if normalized in ("zh-tw", "zh_tw", "zhtw", "zh-hant"):
        return Language.ZH_TW
    if normalized in ("ja", "jp", "ja-jp"):
        return Language.JA
    if normalized in ("en", "en-us", "en-gb"):
        return Language.EN
    return Language.ZH


def _strip_markup(text: str) -> str:
    """对齐 Java `stripMarkupForDetection`：去标签与 HTML 实体后再判定。"""
    if not text:
        return ""
    stripped = _MARKUP_TAG.sub(" ", text)
    stripped = _ENTITY_NAMED.sub(" ", stripped)
    stripped = _ENTITY_NUM.sub(" ", stripped)
    return stripped.strip()


def _is_ideographic(ch: str) -> bool:
    """CJK 统一表意文字（Java Character.isIdeographic 的常用区间子集）。"""
    code = ord(ch)
    return (
        0x4E00 <= code <= 0x9FFF        # CJK Unified Ideographs
        or 0x3400 <= code <= 0x4DBF     # Extension A
        or 0x20000 <= code <= 0x2A6DF   # Extension B
        or 0xF900 <= code <= 0xFAFF     # Compatibility Ideographs
        or 0x3005 <= code <= 0x3007     # 々〆〇
        or 0x2F00 <= code <= 0x2FDF     # Kangxi Radicals
    )


def _is_kana(ch: str) -> bool:
    """平假名 / 片假名（Java 判定里的 0x3040-0x30FF、0x31F0-0x31FF）。"""
    code = ord(ch)
    return 0x3040 <= code <= 0x30FF or 0x31F0 <= code <= 0x31FF


def _is_ascii_letter(ch: str) -> bool:
    code = ord(ch)
    return code < 128 and (("a" <= ch <= "z") or ("A" <= ch <= "Z"))


def count_ideographic(text: str) -> int:
    return sum(1 for ch in (text or "") if _is_ideographic(ch))


def detect_language(text: str) -> Language:
    """逐条对齐 Java `detectFromText`。

    判定顺序（含阈值，不可随意改）：
    1. `ja > en && ja >= zh && ja >= 3` → JA
    2. `en > zh*2 && en > ja && en >= 5` → EN（中英混杂优先中文，避免少量英文词带走整轮）
    3. `zh > 0` → ZH
    4. `ja >= en && ja > 0` → JA
    5. `en > 0` → EN
    6. 其余 → ZH
    """
    sample = _strip_markup(text or "")
    ja = en = zh = 0
    for ch in sample:
        if _is_kana(ch):
            ja += 1
        elif _is_ascii_letter(ch):
            en += 1
        elif _is_ideographic(ch):
            zh += 1

    if ja > en and ja >= zh and ja >= 3:
        return Language.JA
    if en > zh * 2 and en > ja and en >= 5:
        return Language.EN
    if zh > 0:
        return Language.ZH
    if ja >= en and ja > 0:
        return Language.JA
    if en > 0:
        return Language.EN
    return Language.ZH


def should_enforce_language_gate(expected: str | None) -> bool:
    """仅拦截非期望语言；**永不强制英文**（Java 同义）。"""
    if not expected or not str(expected).strip():
        return False
    return from_code(expected) is not Language.EN


def matches_expected(content: str, expected: str | None) -> bool:
    """对齐 Java `matchesExpected`：中文期望下，只要含表意字符即通过（简体/繁体互认）。"""
    if not content or not content.strip():
        return True
    if not expected or not str(expected).strip():
        return True
    lang = from_code(expected)
    if lang in (Language.ZH, Language.ZH_TW):
        if count_ideographic(_strip_markup(content)) > 0:
            return True
    detected = detect_language(content)
    if lang in (Language.ZH, Language.ZH_TW):
        return detected in (Language.ZH, Language.ZH_TW)
    return detected is lang


def correction_instruction(expected: str) -> str:
    """逐字移植 Java `buildLanguageCorrectionInstruction`。"""
    lang = from_code(expected)
    if lang is Language.ZH_TW:
        return "上一條回覆用了英文，請用繁體中文完整重寫，意思不變，只改語言，不要解釋。"
    if lang is Language.JA:
        return "前の返信が英語になっていました。日本語で書き直してください。意味は変えず、言語だけ直してください。"
    if lang is Language.EN:
        return "Your last reply was not in English. Please rewrite entirely in English without explanation."
    return "上一条回复用了英文，请用简体中文完整重写，意思不变，只改语言，不要解释。"


__all__ = [
    "Language",
    "SUPPORTED",
    "correction_instruction",
    "count_ideographic",
    "detect_language",
    "from_code",
    "matches_expected",
    "should_enforce_language_gate",
]
