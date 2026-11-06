"""端到端验收：完整 system prompt 的拼接与顺序。

迁移期这里逐字节比对 Java `assemble()`；Java 已不再产出 prompt，硬编码的
`end_to_end_golden.json#prompts` 随槽位重排作废。现在校验的是：
静态头（人设/工具/规则）在前，动态块（记忆/称呼/情绪）在后，场景块（时间/城市/风格）最末。
数据块仍用 golden 里的固定字符串，取数差异不在本文件范围内。
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
def test_full_prompt_keeps_tail_blocks(golden, lang):
    """场景块必须都在：时间锚点、城市、自然风格；语言不同文案不同，但结构一致。"""
    actual = compose(golden, lang)
    assert golden["timeFact"] in actual
    assert golden["city"] in actual
    # 自然风格文案随语言变化（zh 是「表达要求」，en 是 Reply only in English…），
    # 断言「结尾就是这一语言的风格块」，而不是硬编码中文标题。
    assert actual.endswith(pa.natural_style_block(lang, True))
    assert len(actual) > 1500


def test_all_blocks_present_in_order(golden):
    text = compose(golden, "zh")
    order = [
        text.index("你是小林"),
        text.index("[[TOOL_HINT]]"),
        text.index("回复规则："),
        text.index("关于用户的记忆"),
        text.index("用户称呼（强制）"),
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