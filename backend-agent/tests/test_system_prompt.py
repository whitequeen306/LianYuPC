"""system prompt 编排：顺序与前缀稳定性。

2026-11 架构改革后，Java 已不再产出 prompt，`system_prompt_golden.json#cases`
那套「与 Java 逐字节一致」的断言随槽位重排失效（记忆块从人设后挪到静态头之后）。
现在断言真正要保护的三件事：

  1. 静态头在同一角色/语言下逐字稳定 —— 它是 prompt cache 的可复用前缀；
  2. 记忆 / 称呼 / 情绪这些逐轮变化的块必须落在静态头之后；
  3. 语义顺序不变：情绪块在回复规则之后，安全边界永远最后。
"""

import json
from pathlib import Path

import pytest

from app import system_prompt as sp

GOLDEN = Path(__file__).resolve().parent.parent / "app" / "prompts" / "system_prompt_golden.json"
SETTINGS = {"personality": "温柔但有点固执"}


@pytest.fixture(scope="module")
def golden() -> dict:
    assert GOLDEN.exists(), f"缺少 {GOLDEN.name}"
    return json.loads(GOLDEN.read_text(encoding="utf-8"))


def render(golden, lang, tools, inner, with_memory, memory=None, emotion=None):
    return sp.build_system_prompt(
        character_name="小林",
        settings=SETTINGS,
        prompt_template=None,
        memory_context=(
            memory if memory is not None
            else (golden["memoryWithName"] if with_memory else "")
        ),
        tool_hint=golden["toolHint"],
        emotion_block=golden["emotionBlock"] if emotion is None else emotion,
        output_language=lang,
        enable_chat_tools=tools,
        show_inner_thoughts=inner,
        max_pieces=2,
    )


def test_static_head_comes_before_dynamic_tail(golden):
    """人设 → 工具提示 → 回复规则 → 记忆 → 情绪 → 安全边界。"""
    prompt = render(golden, "zh", True, True, True)
    persona_at = prompt.index("你是小林")
    hint_at = prompt.index(golden["toolHint"].strip())
    reply_at = prompt.index("回复规则：")
    memory_at = prompt.index("关于用户的记忆")
    emotion_at = prompt.index(golden["emotionBlock"].strip())
    guard_at = prompt.index("安全与边界")
    assert persona_at < hint_at < reply_at < memory_at < emotion_at < guard_at


def test_static_prefix_is_byte_stable_across_turns(golden):
    """换一段记忆、换一种情绪，静态前缀必须逐字不变，否则缓存整段失效。"""
    first = render(golden, "zh", True, True, True,
                   memory="【长期记忆/爱好】听雨声", emotion="\n\n今天她有点低落。")
    second = render(golden, "zh", True, True, True,
                    memory="【长期记忆/爱好】爬山", emotion="\n\n她刚下班，很累。")

    cut = first.index("关于用户的记忆")
    assert first[:cut] == second[:cut], "静态前缀被动态内容污染"
    assert len(first[:cut]) >= 800, "静态头太短，够不到缓存的最小前缀"


def test_dynamic_blocks_never_precede_static_head(golden):
    prompt = render(golden, "zh", True, True, True,
                    memory="【长期记忆/姓名】小林", emotion="\n\n[[EMOTION]]")
    static_cut = prompt.index("关于用户的记忆")
    assert "[[EMOTION]]" not in prompt[:static_cut]
    assert "用户称呼（强制）" not in prompt[:static_cut]


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
