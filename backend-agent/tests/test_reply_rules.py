"""回复规则模板：与 Java 渲染结果逐字节比对。

`reply_rule_templates.json` 由 Java 的 `ReplyRuleTemplateGoldenTest` 真实渲染产出
（persona 以 `{{PERSONA}}` 占位）。Python 只做选择与替换，因此：
- 用占位符渲染时，输出必须与模板**完全相同**（证明没有多做/少做任何拼接）
- 用真实 persona 渲染时，只能在占位处发生替换
"""

import json
from pathlib import Path

import pytest

from app import reply_rules as rr

TEMPLATES = Path(__file__).resolve().parent.parent / "app" / "prompts" / "reply_rule_templates.json"


def key(lang: str, inner: bool, pieces: int) -> str:
    """Java 侧用 String.valueOf(boolean) → 小写 true/false。"""
    return f"{lang}|{str(inner).lower()}|{pieces}"


@pytest.fixture(scope="module")
def payload() -> dict:
    assert TEMPLATES.exists(), (
        "缺少 reply_rule_templates.json：运行 "
        "mvn -pl lianyu-service -am test -Dtest=ReplyRuleTemplateGoldenTest"
    )
    return json.loads(TEMPLATES.read_text(encoding="utf-8"))


def test_all_sixteen_combinations_present(payload):
    assert len(payload["templates"]) == 16
    for lang in ("ja", "en", "zh-TW", "zh"):
        for inner in (True, False):
            for pieces in (1, 2):
                assert key(lang, inner, pieces) in payload["templates"]


@pytest.mark.parametrize("lang", ["ja", "en", "zh-TW", "zh"])
@pytest.mark.parametrize("inner", [True, False])
@pytest.mark.parametrize("pieces", [1, 2])
def test_render_with_placeholder_equals_java_template(payload, lang, inner, pieces):
    """用占位符渲染 → 必须与 Java 产出的模板逐字节相同。"""
    expected = payload["templates"][key(lang, inner, pieces)]
    actual = rr.render_reply_rules(lang, payload["personaPlaceholder"], inner, pieces)
    assert actual == expected


def test_persona_is_substituted_at_placeholder_only(payload):
    persona = "小林"
    lang = "zh"
    base = payload["templates"][key(lang, True, 2)]
    actual = rr.render_reply_rules(lang, persona, True, 2)

    assert persona in actual
    assert payload["personaPlaceholder"] not in actual
    # 替换只影响占位处：把 persona 换回占位符应与原模板一致
    assert actual.replace(persona, payload["personaPlaceholder"]) == base


def test_max_pieces_branch_selection():
    """maxPieces 只区分「1 条」与「多条两种」——3 与 2 必须落到同一模板。"""
    assert rr.render_reply_rules("zh", "P", True, 3) == rr.render_reply_rules("zh", "P", True, 2)
    assert rr.render_reply_rules("zh", "P", True, 1) != rr.render_reply_rules("zh", "P", True, 2)


def test_inner_thoughts_switch_changes_output():
    assert rr.render_reply_rules("zh", "P", True, 2) != rr.render_reply_rules("zh", "P", False, 2)


@pytest.mark.parametrize(
    "raw,expected_key_lang",
    [
        ("zh", "zh"), ("zh-CN", "zh"), (None, "zh"),
        ("zh-TW", "zh-TW"), ("zh_Hant", "zh-TW"),
        ("ja", "ja"), ("ja-JP", "ja"), ("en", "en"), ("en-US", "en"),
    ],
)
def test_language_keys(raw, expected_key_lang):
    assert key(expected_key_lang, True, 2) in rr.template_keys()
    # 不抛异常即可
    assert rr.render_reply_rules(raw, "P", True, 2)


def test_missing_template_raises_with_hint(monkeypatch):
    """未知语言会兜底成 zh，因此用「模板里没有的键」触发缺失分支。"""
    rr.reload_templates()
    monkeypatch.setattr(rr, "_load", lambda: ("{{PERSONA}}", {"zh|true|2": "X"}))
    with pytest.raises(rr.ReplyRuleTemplateMissing) as exc:
        rr.render_reply_rules("en", "P", True, 2)
    assert "available" in str(exc.value)


def test_none_persona_becomes_empty_string():
    out = rr.render_reply_rules("zh", None, True, 2)
    assert "{{PERSONA}}" not in out
