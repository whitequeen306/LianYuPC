"""system prompt 编排：与 Java 逐字节比对（16 组合）。"""

import json
from pathlib import Path

import pytest

from app import system_prompt as sp

GOLDEN = Path(__file__).resolve().parent.parent / "app" / "prompts" / "system_prompt_golden.json"
SETTINGS = {"personality": "温柔但有点固执"}


@pytest.fixture(scope="module")
def golden() -> dict:
    assert GOLDEN.exists(), (
        "缺少 system_prompt_golden.json：运行 "
        "mvn -pl lianyu-service -am test -Dtest=SystemPromptOrchestrationGoldenTest"
    )
    return json.loads(GOLDEN.read_text(encoding="utf-8"))


def render(golden, lang, tools, inner, with_memory):
    return sp.build_system_prompt(
        character_name="小林",
        settings=SETTINGS,
        prompt_template=None,
        memory_context=golden["memoryWithName"] if with_memory else "",
        tool_hint=golden["toolHint"],
        emotion_block=golden["emotionBlock"],
        output_language=lang,
        enable_chat_tools=tools,
        show_inner_thoughts=inner,
        max_pieces=2,
    )


@pytest.mark.parametrize("lang", ["zh", "en"])
@pytest.mark.parametrize("tools", [True, False])
@pytest.mark.parametrize("inner", [True, False])
@pytest.mark.parametrize("with_memory", [True, False])
def test_orchestration_matches_java(golden, lang, tools, inner, with_memory):
    key = f"{lang}|tools={str(tools).lower()}|inner={str(inner).lower()}|memory={str(with_memory).lower()}"
    assert render(golden, lang, tools, inner, with_memory) == golden["cases"][key]


def test_emotion_block_comes_after_reply_rules(golden):
    """顺序语义：情绪块必须在回复规则之后（权重更高）。"""
    prompt = render(golden, "zh", True, True, True)
    reply_at = prompt.index("回复规则：")
    emotion_at = prompt.index(golden["emotionBlock"].strip())
    guard_at = prompt.index("安全与边界")
    assert reply_at < emotion_at < guard_at


def test_guard_block_is_always_last(golden):
    for tools in (True, False):
        prompt = render(golden, "zh", tools, True, True)
        assert prompt.rstrip().endswith(golden_fragment_tail())


def golden_fragment_tail() -> str:
    from app.prompt_fragments import fragment
    return fragment("UserInputSanitizer.java", 92).strip()


def test_tool_hint_only_when_enabled(golden):
    with_tools = render(golden, "zh", True, True, False)
    without = render(golden, "zh", False, True, False)
    assert golden["toolHint"] in with_tools
    assert golden["toolHint"] not in without


def test_memory_block_only_when_present(golden):
    with_memory = render(golden, "zh", False, True, True)
    without = render(golden, "zh", False, True, False)
    assert "关于用户的记忆" in with_memory
    assert "关于用户的记忆" not in without


def test_prompt_template_replaces_placeholders():
    out = sp.build_system_prompt(
        character_name="小林",
        settings=SETTINGS,
        prompt_template="名字={{name}}\n人设={{persona}}",
        output_language="zh",
    )
    assert out.startswith("名字=小林\n人设=")
    assert "{{name}}" not in out


def test_user_addressing_rule_produces_hint_block():
    """有姓名时返回「当前名字是「X」」的强制措辞块；无姓名时为空。"""
    block = sp.build_user_addressing_rule("【长期记忆/姓名】小林", "zh")
    assert "用户称呼（强制）" in block
    assert "小林" in block
    assert sp.build_user_addressing_rule("【长期记忆/爱好】听雨声", "zh") == ""
    assert sp.build_user_addressing_rule("", "zh") == ""


def test_addressing_block_per_language():
    for lang in ("zh", "zh-TW", "ja", "en"):
        assert sp.addressing_hint_block("小林", lang).strip()
        assert "{{NAME}}" not in sp.addressing_hint_block("小林", lang)
# ── 场景分支：time_only（VOICE_CALL）──


E2E = Path(__file__).resolve().parent.parent / "app" / "prompts" / "end_to_end_golden.json"


@pytest.fixture(scope="module")
def time_fact() -> str:
    return json.loads(E2E.read_text(encoding="utf-8"))["timeFact"]


def test_time_only_appends_time_but_not_style(time_fact):
    """VOICE_CALL：includeTime 为 true 但不加自然风格块 —— 曾因 afterBase="none"
    提前 return 导致整段时间锚点丢失（276 字符差异）。"""
    base = sp.build_system_prompt(
        character_name="小林", settings=SETTINGS, prompt_template=None,
        output_language="zh", enable_chat_tools=False)
    with_time = sp.assemble_scene_prompt(
        character_name="小林", settings=SETTINGS, prompt_template=None,
        output_language="zh", enable_chat_tools=False,
        include_time=True, time_fact=time_fact, after_base="time_only")

    assert "当前真实环境" in with_time
    assert time_fact in with_time
    assert "表达要求" not in with_time, "VOICE_CALL 不应追加自然风格块"
    assert len(with_time) > len(base)


def test_none_branch_has_no_time_and_no_style(time_fact):
    out = sp.assemble_scene_prompt(
        character_name="小林", settings=SETTINGS, prompt_template=None,
        output_language="zh", enable_chat_tools=False,
        include_time=True, time_fact=time_fact, after_base="none")
    assert "当前真实环境" not in out
    assert "表达要求" not in out
