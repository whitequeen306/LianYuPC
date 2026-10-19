"""工具提示基线测试：Python 查表结果必须等于 Java 生成的文案。"""

import json
from pathlib import Path

import pytest

from app import tools_hint as th

GOLDEN = Path(__file__).resolve().parent.parent / "app" / "prompts" / "tool_hints_golden.json"


@pytest.fixture(scope="module")
def golden() -> dict:
    return json.loads(GOLDEN.read_text(encoding="utf-8"))


def test_golden_covers_all_32_combinations(golden):
    assert len(golden["cases"]) == 32, "2^5 个开关组合应全部覆盖"


def test_flag_key_order_matches_java(golden):
    assert golden["flags"]["order"] == "chat,memory,recent,diary,computer"
    assert th.flag_key(chat=True, memory=False, recent=False, diary=False, computer=False) == "10000"


def test_every_case_matches_exactly(golden):
    for key, case in golden["cases"].items():
        actual = th.build_tool_hint(
            chat=key[0] == "1", memory=key[1] == "1", recent=key[2] == "1",
            diary=key[3] == "1", computer=key[4] == "1")
        assert actual == case["hint"], f"组合 {key} 不一致"


def test_chat_disabled_yields_empty():
    assert th.build_tool_hint(chat=False, memory=True, recent=True, diary=True, computer=True) == ""


def test_computer_task_line_only_when_available(golden):
    without = golden["cases"]["11110"]["hint"]
    with_it = golden["cases"]["11111"]["hint"]
    assert "computer_task" not in without
    assert "computer_task" in with_it
    assert len(with_it) > len(without)


def test_build_from_flags_handles_missing_and_empty():
    assert th.build_from_flags(None) == ""
    assert th.build_from_flags({}) == ""
    assert th.build_from_flags({"chat": True}) == th.build_tool_hint(
        chat=True, memory=False, recent=False, diary=False, computer=False)
