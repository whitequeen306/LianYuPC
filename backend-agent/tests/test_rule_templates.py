"""输出语言规则 / 群聊规则：与 Java 渲染结果逐字节比对。"""

import json
from pathlib import Path

import pytest

from app import rule_templates as rt

TEMPLATES = Path(__file__).resolve().parent.parent / "app" / "prompts" / "rule_templates.json"


@pytest.fixture(scope="module")
def payload() -> dict:
    assert TEMPLATES.exists(), (
        "缺少 rule_templates.json：运行 "
        "mvn -pl lianyu-service -am test -Dtest=RuleTemplatesGoldenTest"
    )
    return json.loads(TEMPLATES.read_text(encoding="utf-8"))


# ── 输出语言规则（4 语种）──


@pytest.mark.parametrize("lang", ["ja", "en", "zh-TW", "zh"])
def test_output_language_matches_java(payload, lang):
    assert rt.render_output_language_rules(lang) == payload["outputLanguage"][lang]


@pytest.mark.parametrize(
    "raw,expected",
    [("zh", "zh"), ("zh-CN", "zh"), (None, "zh"),
     ("zh-TW", "zh-TW"), ("zh_Hant", "zh-TW"),
     ("ja-JP", "ja"), ("en-US", "en")],
)
def test_output_language_normalization(raw, expected):
    assert rt.render_output_language_rules(raw) == rt.render_output_language_rules(expected)


# ── 群聊规则（4 语种 × 2 气泡数）──


@pytest.mark.parametrize("lang", ["ja", "en", "zh-TW", "zh"])
@pytest.mark.parametrize("pieces", [1, 2])
def test_group_chat_matches_java_with_placeholders(payload, lang, pieces):
    """用占位符渲染必须与 Java 模板逐字节相同。"""
    ph = payload["placeholders"]
    actual = rt.render_group_chat_rules(
        lang, ph["name"], ph["other"], ph["mention"], pieces, True)
    assert actual == payload["groupChat"][f"{lang}|{pieces}|true"]


def test_group_chat_substitutes_dynamic_parts():
    out = rt.render_group_chat_rules(
        "zh", "小林", "其他角色：阿澈、小夜", "@小林 在吗", 2, True)
    assert "小林" in out
    assert "阿澈" in out
    assert "{{NAME}}" not in out
    assert "{{OTHER}}" not in out
    assert "{{MENTION}}" not in out


def test_group_chat_pieces_branch():
    a = rt.render_group_chat_rules("zh", "P", "", "", 1, True)
    b = rt.render_group_chat_rules("zh", "P", "", "", 2, True)
    c = rt.render_group_chat_rules("zh", "P", "", "", 5, True)
    assert a != b
    assert b == c, "maxPieces>1 应该落到同一模板"


def test_group_chat_defaults_do_not_leave_placeholders():
    out = rt.render_group_chat_rules("en", "Alice", "", "", 2, True)
    assert "{{" not in out


def test_template_keys_complete():
    keys = rt.template_keys()
    assert keys["outputLanguage"] == ["en", "ja", "zh", "zh-TW"]
    assert len(keys["groupChat"]) == 8


def test_missing_template_raises(monkeypatch):
    rt.reload_templates()
    monkeypatch.setattr(rt, "_load", lambda: {"outputLanguage": {"zh": "X"}, "groupChat": {}})
    with pytest.raises(rt.RuleTemplateMissing) as exc:
        rt.render_output_language_rules("en")
    assert "available" in str(exc.value)