"""记忆块格式：与 Java 真实组装结果逐字节比对。

golden 由 `MemoryProfileContextGoldenTest` 用 mock mapper 固定数据后
调用 `retrieveProfileContext` 产出，覆盖：
四段齐全、空白摘要忽略、同类型重复去重、空数据返回 null。
"""

import json
from pathlib import Path

import pytest

from app.memory.profile_context import build_profile_context

GOLDEN = Path(__file__).resolve().parent.parent / "app" / "prompts" / "profile_context_golden.json"

# 与 Java 测试同一组 fixture（顺序也一致）
FIXTURE = [
    ("【长期记忆/姓名】小林", "FACT"),
    ("【长期记忆/爱好】听雨声", "FACT"),
    ("最近工作压力大", "EMOTION"),
    ("一起看过流星雨", "RELATION"),
    ("每晚互道晚安", "RITUAL"),
    ("   ", "FACT"),
    ("【长期记忆/爱好】听雨声", "FACT"),
]


@pytest.fixture(scope="module")
def golden() -> dict:
    assert GOLDEN.exists(), (
        "缺少 profile_context_golden.json：运行 "
        "mvn -pl lianyu-service -am test -Dtest=MemoryProfileContextGoldenTest"
    )
    return json.loads(GOLDEN.read_text(encoding="utf-8"))


def test_profile_context_matches_java(golden):
    assert build_profile_context(FIXTURE) == golden["full"]


def test_empty_matches_java(golden):
    assert build_profile_context([]) == golden["empty"]
    assert golden["empty"] is None


def test_sections_in_java_order(golden):
    text = build_profile_context(FIXTURE)
    order = [text.index(label) for label in ("用户画像", "近期情绪线索", "关系事件", "专属仪式")]
    assert order == sorted(order)


def test_blank_summary_ignored():
    text = build_profile_context([("   ", "FACT"), ("真事实", "FACT")])
    assert text == "[用户画像]\n- 真事实"


def test_duplicate_summary_deduped():
    text = build_profile_context([("同一句", "FACT"), ("同一句", "FACT")])
    assert text.count("- 同一句") == 1


def test_unknown_type_ignored():
    assert build_profile_context([("x", "UNKNOWN")]) is None


def test_dict_items_supported():
    assert build_profile_context([{"summary": "x", "memory_type": "RELATION"}]) == "[关系事件]\n- x"