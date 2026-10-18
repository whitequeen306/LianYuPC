"""system prompt 纯文本段落：与 Java 真实输出逐字节比对。

基准来自 `assembler_golden.json`，由 Java 的 `PromptAssemblerGoldenTest`
真实执行 `appendCurrentTimeContext` / `appendCurrentRealCityContext` 后落盘。
**不在 Python 里重算期望值** —— 那样两边同错也会通过。
"""

import json
from pathlib import Path

import pytest

from app import prompt_assembly as pa

GOLDEN = Path(__file__).resolve().parent.parent / "app" / "prompts" / "assembler_golden.json"


@pytest.fixture(scope="module")
def golden() -> dict:
    assert GOLDEN.exists(), (
        "缺少 assembler_golden.json：运行 "
        "mvn -pl lianyu-service -am test -Dtest=PromptAssemblerGoldenTest"
    )
    return json.loads(GOLDEN.read_text(encoding="utf-8"))


# ── 逐字节比对（核心）──


def test_time_context_matches_java_byte_for_byte(golden):
    actual = pa.append_time_context(golden["base"], golden["timeFact"])
    assert actual == golden["timeContext"], (
        "时间锚点段落与 Java 输出不一致（连空白/标点都必须一致）"
    )


def test_city_context_matches_java_byte_for_byte(golden):
    actual = pa.append_city_context(golden["base"], golden["city"])
    assert actual == golden["cityContext"], "城市权威段与 Java 输出不一致"


def test_time_context_contains_value_between_fragments(golden):
    actual = pa.append_time_context(golden["base"], golden["timeFact"])
    prefix_at = actual.index("=== 当前真实环境")
    value_at = actual.index(golden["timeFact"])
    suffix_at = actual.index("硬性要求")
    assert prefix_at < value_at < suffix_at, "拼接顺序必须是 前缀 → 时间事实 → 后缀"


def test_city_context_contains_city_between_fragments(golden):
    actual = pa.append_city_context(golden["base"], golden["city"])
    assert actual.index("=== 用户当前所在现实城市") < actual.index(golden["city"]) < actual.index("注意：")


# ── 晚安模式（四语种）──


@pytest.mark.parametrize(
    "lang,anchor",
    [
        ("ja", "おやすみモード"),
        ("en", "Goodnight Mode"),
        ("zh-TW", "晚安模式（最高優先）"),
        ("zh", "晚安模式（最高优先级）"),
    ],
)
def test_goodnight_block_per_language(lang, anchor):
    assert anchor in pa.goodnight_block(lang)


def test_goodnight_block_appends_to_base():
    out = pa.append_goodnight_context("BASE", "zh")
    assert out.startswith("BASE")
    assert "晚安模式" in out


@pytest.mark.parametrize(
    "raw,expected",
    [
        ("ja", "ja"), ("JA", "ja"), ("ja-JP", "ja"),
        ("en", "en"), ("en-US", "en"),
        ("zh-TW", "zh-tw"), ("zh_Hant", "zh-tw"),
        ("zh", "zh"), ("zh-CN", "zh"), (None, "zh"), ("", "zh"),
    ],
)
def test_language_normalization(raw, expected):
    assert pa.normalize_language(raw) == expected


# ── 晚安关键词（对齐 Java 正则的语义）──


@pytest.mark.parametrize(
    "text",
    ["晚安", "我先睡了", "好困了我要睡了", "おやすみ", "Good night", "goodnight", "gn", "睡吧"],
)
def test_goodnight_keywords_detected(text):
    assert pa.contains_goodnight_keyword(text)


@pytest.mark.parametrize("text", [None, "", "   ", "你在干嘛", "今天天气不错"])
def test_non_goodnight_text_not_detected(text):
    assert not pa.contains_goodnight_keyword(text)


def test_keyword_matching_is_case_insensitive():
    assert pa.contains_goodnight_keyword("GOOD NIGHT")


# ── 纯函数约定 ──


def test_appenders_are_pure_and_do_not_mutate_base():
    base = "BASE"
    pa.append_time_context(base, "T")
    pa.append_city_context(base, "C")
    pa.append_goodnight_context(base, "zh")
    assert base == "BASE"
