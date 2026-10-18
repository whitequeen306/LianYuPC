"""关系块：与 Java 逐字节比对（49 用例 + 404 条标签）。"""

import json
from pathlib import Path

import pytest

from app import relationship_context as rc

GOLDEN = Path(__file__).resolve().parent.parent / "app" / "prompts" / "relationship_golden.json"
EVENTS = ["一起看过流星雨", "吵过一次架"]


@pytest.fixture(scope="module")
def golden() -> dict:
    assert GOLDEN.exists(), (
        "缺少 relationship_golden.json：运行 "
        "mvn -pl lianyu-service -am test -Dtest=RelationshipContextGoldenTest"
    )
    return json.loads(GOLDEN.read_text(encoding="utf-8"))


@pytest.mark.parametrize("score", [0, 24, 25, 49, 50, 74, 75, 100])
def test_label_bands_match_java_at_boundaries(golden, score):
    """分档边界（24/25、49/50、74/75）逐个比对。"""
    for dimension in ("trust", "intimacy", "security", "anticipation"):
        expected = golden["labels"][dimension][str(score)]
        assert rc.describe_dimension(dimension, score) == expected


@pytest.mark.parametrize("score", list(range(0, 101)))
def test_labels_match_java_all_scores(golden, score):
    for dimension in ("trust", "intimacy", "security", "anticipation"):
        assert rc.describe_dimension(dimension, score) == golden["labels"][dimension][str(score)]


def test_unknown_dimension_falls_back(golden):
    # Java 的 default 分支返回 high/medium/low/low
    assert rc.describe_dimension("unknown", 80) == "high"
    assert rc.describe_dimension("unknown", 60) == "medium"
    assert rc.describe_dimension("unknown", 30) == "low"
    assert rc.describe_dimension("unknown", 0) == "low"


@pytest.mark.parametrize("score", [0, 24, 25, 49, 50, 74, 75, 100])
def test_full_block_matches_java_for_each_phase(golden, score):
    for phase in golden["phases"]:
        key = f"{phase}|{score}"
        actual = rc.build_relationship_context(
            phase=phase, trust=score, intimacy=100 - score,
            security=score, anticipation=100 - score, recent_events=EVENTS)
        assert actual == golden["cases"][key]


def test_empty_events_branch_matches_java(golden):
    phase = golden["phases"][0]
    actual = rc.build_relationship_context(
        phase=phase, trust=60, intimacy=60, security=60, anticipation=60, recent_events=[])
    assert actual == golden["cases"]["empty-events"]
    assert rc.EMPTY_EVENTS_LINE in actual


def test_phase_formatting():
    assert rc.format_phase("STABLE_INTIMATE") == "stable-intimate"
    assert rc.format_phase("FAMILIAR") == "familiar"
    # 非法/空值回退到 Java 的默认阶段，避免产出 Java 永不生成的 prompt
    assert rc.format_phase("CLOSE_FRIEND") == "testing"
    assert rc.format_phase("STRANGER") == "testing"
    assert rc.format_phase(None) == "testing"