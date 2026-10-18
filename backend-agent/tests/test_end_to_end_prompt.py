"""端到端验收：Python 组装的完整 system prompt 与 Java `assemble()` 比对。

这是 P5.5 各层的总验收 —— 前面每一步都是分段比对，这里验证「拼起来」。
数据块（记忆/关系/会话摘要）用 golden 里的固定字符串，所以校验的是
**拼接与顺序**，不含取数差异。
"""

import json
from pathlib import Path

import pytest

from app import prompt_assembly as pa
from app import system_prompt as sp

GOLDEN = Path(__file__).resolve().parent.parent / "app" / "prompts" / "end_to_end_golden.json"
SETTINGS = {"personality": "温柔但有点固执", "city_mode": "real", "city": "上海"}


@pytest.fixture(scope="module")
def golden() -> dict:
    assert GOLDEN.exists(), (
        "缺少 end_to_end_golden.json：运行 "
        "mvn -pl lianyu-service -am test -Dtest=AssemblerEndToEndGoldenTest"
    )
    return json.loads(GOLDEN.read_text(encoding="utf-8"))


def compose(golden, lang: str) -> str:
    memory_context = "\n\n".join([
        golden["memoryBlock"], golden["relationshipBlock"], golden["sessionBlock"],
    ])
    base = sp.build_system_prompt(
        character_name="小林",
        settings=SETTINGS,
        prompt_template=None,
        memory_context=memory_context,
        tool_hint="[[TOOL_HINT]]",
        emotion_block="\n\n[[EMOTION]]",
        output_language=lang,
        enable_chat_tools=True,
        show_inner_thoughts=True,
        max_pieces=2,
    )
    return pa.compose_prompt(base, time_fact=golden["timeFact"],
                             city=golden["city"], lang=lang, show_inner_thoughts=True)


@pytest.mark.parametrize("lang", ["zh", "en"])
def test_full_prompt_matches_java(golden, lang):
    actual = compose(golden, lang)
    expected = golden["prompts"][lang]
    assert actual == expected, (
        f"{lang} 完整 prompt 与 Java 不一致：python={len(actual)} 字符, "
        f"java={len(expected)} 字符"
    )


def test_all_blocks_present_in_order(golden):
    text = compose(golden, "zh")
    order = [
        text.index("你是小林"),
        text.index("关于用户的记忆"),
        text.index("用户称呼（强制）"),
        text.index("回复规则："),
        text.index("[[EMOTION]]"),
        text.index("安全与边界"),
        text.index("当前真实环境"),
        text.index("用户当前所在现实城市"),
    ]
    assert order == sorted(order), "段落顺序被打乱"


def test_relationship_and_session_blocks_included(golden):
    text = compose(golden, "zh")
    assert golden["relationshipBlock"] in text
    assert golden["sessionBlock"] in text