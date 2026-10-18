"""人设提取 / 默认提示词：与 Java 真实输出逐字节比对。

基准来自 Java `CharacterPromptGoldenTest`（反射调用私有方法，8 个分支用例）：
null settings、空 settings、长人设单字段、长人设+风格、长人设已含关键词、
短人设全字段、personality 长度 19 / 20 的边界。
"""

import json
from pathlib import Path

import pytest

from app import character_prompt as cp

GOLDEN = Path(__file__).resolve().parent.parent / "app" / "prompts" / "character_prompt_golden.json"


@pytest.fixture(scope="module")
def golden() -> dict:
    assert GOLDEN.exists(), (
        "缺少 character_prompt_golden.json：运行 "
        "mvn -pl lianyu-service -am test -Dtest=CharacterPromptGoldenTest"
    )
    return json.loads(GOLDEN.read_text(encoding="utf-8"))


# 与 Java 测试里同一组 fixture（键必须一一对应）
SETTINGS = {
    "null-settings": None,
    "empty-settings": {},
    "long-personality-only": {
        "personality": "她是一个温柔又固执的人，喜欢在雨天听歌，讨厌被打断。",
    },
    "long-personality-with-style": {
        "personality": "她是一个温柔又固执的人，喜欢在雨天听歌，讨厌被打断。",
        "speakingStyle": "说话简短，多用省略号",
    },
    "long-personality-mentions-style": {
        "personality": "她是一个温柔的人。说话风格：轻快；背景：乡下长大，爱做饭。",
        "speakingStyle": "不应被追加",
        "backstory": "不应被追加",
    },
    "short-personality-full": {
        "personality": "温柔",
        "speakingStyle": "说话带点撒娇",
        "backstory": "小时候养过一只猫",
        "age": 22,
    },
    "boundary-19": {"personality": "一二三四五六七八九十一二三四五六七八九"},
    "boundary-20": {"personality": "一二三四五六七八九十一二三四五六七八九十"},
}


@pytest.mark.parametrize("case", sorted(SETTINGS))
def test_persona_matches_java(golden, case):
    actual = cp.extract_persona("小林", SETTINGS[case])
    assert actual == golden[case]["persona"]


@pytest.mark.parametrize("case", sorted(SETTINGS))
def test_default_prompt_matches_java(golden, case):
    actual = cp.build_default_prompt("小林", golden[case]["persona"])
    assert actual == golden[case]["defaultPrompt"]


def test_long_personality_appends_missing_fields():
    persona = cp.extract_persona("小林", SETTINGS["long-personality-with-style"])
    assert "说话简短" in persona
    assert persona.startswith("她是一个温柔又固执的人")


def test_long_personality_skips_already_present_keywords():
    persona = cp.extract_persona("小林", SETTINGS["long-personality-mentions-style"])
    assert "不应被追加" not in persona, "personality 已含关键词时不应重复追加"


def test_short_personality_includes_age_style_backstory():
    persona = cp.extract_persona("小林", SETTINGS["short-personality-full"])
    assert "年龄：22岁" in persona
    assert "人设：温柔" in persona
    assert "说话带点撒娇" in persona
    assert "小时候养过一只猫" in persona


def test_boundary_19_uses_short_branch():
    persona = cp.extract_persona("小林", SETTINGS["boundary-19"])
    assert persona.startswith("名字：小林")


def test_boundary_20_uses_long_branch():
    persona = cp.extract_persona("小林", SETTINGS["boundary-20"])
    assert not persona.startswith("名字：")
    assert len(SETTINGS["boundary-20"]["personality"]) == 20


def test_none_settings_returns_name_only():
    assert cp.extract_persona("小林", None) == "名字：小林"


def test_default_prompt_contains_required_anchors():
    out = cp.build_default_prompt("小林", "温柔")
    assert out.startswith("你是小林，一个真实的人")
    assert "不能让任何人知道你是AI" in out
    assert out.endswith("温柔")
