"""评测题集生成：解析、去重、单条失败不拖垮整批。"""

import pytest

from app.memory import eval_gen


def test_build_messages_carries_type_and_summary():
    messages = eval_gen.build_messages("她喜欢冰美式", "FACT")

    assert messages[0]["role"] == "system"
    assert "只能由这条记忆回答" in messages[0]["content"]
    assert "FACT" in messages[1]["content"]
    assert "她喜欢冰美式" in messages[1]["content"]


@pytest.mark.parametrize(
    "raw,expected",
    [
        ('{"query": "我平时爱喝什么"}', "我平时爱喝什么"),
        ('```json\n{"query": "我早上一般喝啥"}\n```', "我早上一般喝啥"),
        ('{"query": "「还记得我喜欢喝什么吗」"}', "还记得我喜欢喝什么吗"),
        ('{"query": ""}', None),
        ("不是 json", None),
        ('{"query": "' + "长" * 80 + '"}', None),
    ],
)
def test_parse_query(raw, expected):
    assert eval_gen.parse_query(raw) == expected


@pytest.mark.asyncio
async def test_generate_cases_skips_failures_and_duplicates():
    rows = [
        {"id": 1, "summary": "她喜欢冰美式", "memory_type": "FACT"},
        {"id": 2, "summary": "她怕黑", "memory_type": "EMOTION"},
        {"id": 3, "summary": "她怕黑", "memory_type": "EMOTION"},
        {"id": 4, "summary": "", "memory_type": "FACT"},
    ]

    async def ask(messages):
        body = messages[1]["content"]
        if "冰美式" in body:
            return '{"query": "我平时爱喝什么"}'
        if "她怕黑" in body:
            return '{"query": "晚上关灯我怕什么"}'
        raise RuntimeError("model down")

    cases = await eval_gen.generate_cases(rows, ask, limit=10)

    assert [case["expected"] for case in cases] == ["1", "2"]   # 重复提问被去重
    assert cases[0]["query"] == "我平时爱喝什么"


@pytest.mark.asyncio
async def test_generate_cases_respects_limit_and_survives_exceptions():
    rows = [{"id": i, "summary": "事实" + str(i), "memory_type": "FACT"} for i in range(1, 6)]

    async def ask(messages):
        idx = messages[1]["content"].rsplit("事实", 1)[1].split(chr(10))[0]
        if idx == "3":
            raise RuntimeError("boom")
        return '{"query": "问' + idx + '"}'

    cases = await eval_gen.generate_cases(rows, ask, limit=3)

    assert [case["expected"] for case in cases] == ["1", "2", "4"]   # 第 3 条失败被跳过
