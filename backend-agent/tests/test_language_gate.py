"""语言门测试：检测阈值 / 匹配规则 / 纠错指令逐字对照 Java。

对照源：OutputLanguageService.detectFromText / matchesExpected / shouldEnforceLanguageGate、
AiChatService.buildLanguageCorrectionInstruction。
"""

import pytest

from app.language_gate import (
    Language,
    correction_instruction,
    count_ideographic,
    detect_language,
    from_code,
    matches_expected,
    should_enforce_language_gate,
)


# ── from_code ──


@pytest.mark.parametrize(
    "code,expected",
    [
        ("zh", Language.ZH),
        ("zh-TW", Language.ZH_TW),
        ("zh_tw", Language.ZH_TW),
        ("zh-Hant", Language.ZH_TW),
        ("ja", Language.JA),
        ("jp", Language.JA),
        ("en", Language.EN),
        ("EN-us", Language.EN),
        (None, Language.ZH),
        ("", Language.ZH),
        ("klingon", Language.ZH),
    ],
)
def test_from_code(code, expected):
    assert from_code(code) is expected


# ── detect_language（阈值必须与 Java 一致）──


def test_detect_pure_chinese():
    assert detect_language("今天有点想你，早点睡吧") is Language.ZH


def test_detect_pure_english():
    assert detect_language("I miss you, sleep early tonight") is Language.EN


def test_detect_pure_japanese():
    assert detect_language("おやすみ、また明日ね") is Language.JA


def test_mixed_chinese_english_stays_chinese():
    """中英混杂优先中文：少量英文词不能把整轮判定成英文（Java 的 en > zh*2 门槛）。"""
    assert detect_language("今天看了个 video 挺有意思的，你呢") is Language.ZH


def test_english_only_when_dominant():
    # en=5 且 zh=0 → EN
    assert detect_language("hello there") is Language.EN


def test_japanese_wins_only_with_3plus_kana():
    # 只有 1 个假名（の）时不足以判定 JA，会被算作 ZH（因为含表意文字）
    assert detect_language("私の") is Language.ZH
    assert detect_language("わたしの") is Language.JA


def test_markup_is_stripped_before_detection():
    assert detect_language("<b>おやすみなさい</b>") is Language.JA
    assert detect_language("&amp; hello world") is Language.EN


def test_empty_defaults_to_chinese():
    assert detect_language("") is Language.ZH
    assert detect_language("   ") is Language.ZH


def test_count_ideographic():
    assert count_ideographic("abc中文") == 2
    assert count_ideographic("abc") == 0


# ── should_enforce_language_gate（永不强制英文）──


@pytest.mark.parametrize("code", ["zh", "zh-TW", "ja"])
def test_gate_enforced_for_non_english(code):
    assert should_enforce_language_gate(code) is True


@pytest.mark.parametrize("code", ["en", "EN", None, "", "  "])
def test_gate_skipped_for_english_or_blank(code):
    assert should_enforce_language_gate(code) is False


# ── matches_expected ──


def test_chinese_expected_accepts_traditional():
    """简体期望下繁体也算通过（Java 的 ZH/ZH_TW 互认）。"""
    assert matches_expected("今天有點想你", "zh") is True
    assert matches_expected("今天有点想你", "zh-TW") is True


def test_chinese_expected_rejects_english():
    assert matches_expected("I miss you so much tonight", "zh") is False


def test_japanese_expected():
    assert matches_expected("おやすみ、また明日ね", "ja") is True
    assert matches_expected("晚安，明天见", "ja") is False


def test_english_expected_accepts_english():
    assert matches_expected("Good night, sleep well", "en") is True


def test_blank_content_always_matches():
    assert matches_expected("", "zh") is True
    assert matches_expected("   ", "ja") is True
    assert matches_expected("随便", None) is True


# ── 纠错指令逐字对照 ──


def test_correction_instruction_zh():
    assert correction_instruction("zh") == "上一条回复用了英文，请用简体中文完整重写，意思不变，只改语言，不要解释。"


def test_correction_instruction_zh_tw():
    assert correction_instruction("zh-TW") == "上一條回覆用了英文，請用繁體中文完整重寫，意思不變，只改語言，不要解釋。"


def test_correction_instruction_ja():
    assert correction_instruction("ja") == (
        "前の返信が英語になっていました。日本語で書き直してください。意味は変えず、言語だけ直してください。"
    )


def test_correction_instruction_en():
    assert correction_instruction("en") == (
        "Your last reply was not in English. Please rewrite entirely in English without explanation."
    )
