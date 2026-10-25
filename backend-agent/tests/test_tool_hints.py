"""工具提示基线测试：Python 查表结果必须等于 golden 文案。

2026-10-25：位串从 5 位降到 4 位（`chat,memory,recent,diary`）—— 第五位 `computer`
在桌面能力层删除后恒为 false，留着只会让 golden 表多一倍无用组合。
"""

import json
from pathlib import Path

import pytest

from app import tools_hint as th

GOLDEN = Path(__file__).resolve().parent.parent / "app" / "prompts" / "tool_hints_golden.json"


@pytest.fixture(scope="module")
def golden() -> dict:
    return json.loads(GOLDEN.read_text(encoding="utf-8"))


def test_golden_covers_all_16_combinations(golden):
    assert len(golden["cases"]) == 16, "2^4 个开关组合应全部覆盖"


def test_flag_key_order_matches_java(golden):
    assert golden["flags"]["order"] == "chat,memory,recent,diary"
    assert th.flag_key(chat=True, memory=False, recent=False, diary=False) == "1000"


def test_every_case_matches_exactly(golden):
    for key, case in golden["cases"].items():
        actual = th.build_tool_hint(
            chat=key[0] == "1", memory=key[1] == "1", recent=key[2] == "1",
            diary=key[3] == "1")
        assert actual == case["hint"], f"组合 {key} 不一致"


def test_chat_disabled_yields_empty():
    assert th.build_tool_hint(chat=False, memory=True, recent=True, diary=True) == ""


def test_no_dead_capability_text_in_any_case(golden):
    """能力层删除后，任何一组文案都不许再教模型用 desk_* 或已退役的 computer_task。"""
    for key, case in golden["cases"].items():
        assert "desk_" not in case["hint"], f"{key} 文案里还有 desk_*：{case['hint'][:60]}"
        assert "computer_task" not in case["hint"], f"{key} 还在提已退役的 computer_task"


def test_build_from_flags_handles_missing_and_empty():
    assert th.build_from_flags(None) == ""
    assert th.build_from_flags({}) == ""
    assert th.build_from_flags({"chat": True}) == th.build_tool_hint(
        chat=True, memory=False, recent=False, diary=False)


def test_legacy_computer_flag_is_ignored():
    """老调用方（或历史请求体）仍带 computer=true 时不该影响查表结果。"""
    with_legacy = th.build_from_flags(
        {"chat": True, "memory": True, "recent": False, "diary": False, "computer": True})
    without = th.build_from_flags({"chat": True, "memory": True, "recent": False, "diary": False})
    assert with_legacy == without
