"""关系块：阶段行为契约 + 陪伴事实 + 事件归属。

旧版断言的是「四行英文形容词」，已随重构废弃：现在锁定的是
**阶段 × 语言** 的行为指南，以及分数不泄漏进 prompt 正文。
golden 由本模块自身导出（prompt 归 Python，不再依赖已删除的 Java 生成器）。
"""

import json
from pathlib import Path

import pytest

from app import relationship_context as rc

GOLDEN = Path(__file__).resolve().parent.parent / "app" / "prompts" / "relationship_golden.json"
LANGS = ("zh", "zh-TW", "en", "ja")

CANONICAL = dict(
    trust=52, intimacy=48, security=44, anticipation=60,
    recent_events=[
        {"type": "USER_VULNERABLE_SHARE", "summary": "今天被领导骂了"},
        {"type": "ASSISTANT_VULNERABLE_REPLY", "summary": "别怕，我还在"},
    ],
    days_known=12, streak_days=5, away_hours=26,
)


@pytest.fixture(scope="module")
def golden() -> dict:
    assert GOLDEN.exists(), "缺少 relationship_golden.json（见 tests 重建方式）"
    return json.loads(GOLDEN.read_text(encoding="utf-8"))


@pytest.mark.parametrize("lang", LANGS)
def test_full_block_matches_golden_for_each_phase_and_language(golden, lang):
    for phase in rc.KNOWN_PHASES:
        actual = rc.build_relationship_context(phase=phase, lang=lang, **CANONICAL)
        assert actual == golden["cases"][f"{phase}|{lang}"]


def test_scores_never_leak_into_prompt():
    block = rc.build_relationship_context(phase="FAMILIAR", lang="zh", **CANONICAL)
    for token in ("trust", "intimacy", "security", "anticipation", "FAMILIAR"):
        assert token not in block


def test_longing_line_only_when_away_and_anticipation_high():
    base = dict(CANONICAL)
    base["recent_events"] = []

    quiet = rc.build_relationship_context(
        phase="DEPENDENT", lang="zh", **{**base, "anticipation": 20, "away_hours": 26})
    assert "没看到你" not in quiet

    # 想念分够，但刚见过面 → 不该念叨
    just_met = rc.build_relationship_context(
        phase="DEPENDENT", lang="zh", **{**base, "anticipation": 80, "away_hours": 2})
    assert "没看到你" not in just_met

    longing = rc.build_relationship_context(
        phase="DEPENDENT", lang="zh", **{**base, "anticipation": 80, "away_hours": 50})
    assert "没看到你" in longing


def test_event_attribution_separates_user_lines_from_character_lines():
    block = rc.build_relationship_context(phase="FAMILIAR", lang="zh", **CANONICAL)
    assert "- 他说：「今天被领导骂了」" in block
    assert "- 你说过：「别怕，我还在」" in block


def test_plain_string_events_treated_as_user_lines():
    block = rc.build_relationship_context(
        phase="FAMILIAR", lang="zh", recent_events=["一起看过流星雨"], days_known=3)
    assert "- 他说：「一起看过流星雨」" in block


def test_empty_events_uses_invitation_line():
    block = rc.build_relationship_context(
        phase="TESTING", lang="zh", recent_events=[], days_known=1)
    assert "还没有共同的回忆" in block


def test_presence_facts_render_only_when_meaningful():
    block = rc.build_relationship_context(
        phase="FAMILIAR", lang="zh", recent_events=[], days_known=1, streak_days=1, away_hours=3)
    assert "认识第 1 天" in block
    assert "连续" not in block
    assert "没见" not in block


def test_unknown_phase_falls_back_to_default():
    assert rc.normalize_phase("SOULMATE") == "TESTING"
    assert rc.normalize_phase(None) == "TESTING"
    block = rc.build_relationship_context(phase="SOULMATE", lang="zh", recent_events=[])
    assert "阶段：试探中" in block


def test_phase_formatting():
    assert rc.format_phase("STABLE_INTIMATE") == "stable-intimate"
    assert rc.format_phase("FAMILIAR") == "familiar"
    assert rc.format_phase("CLOSE_FRIEND") == "testing"
    assert rc.format_phase(None) == "testing"


def test_behavior_table_covers_every_phase_and_language():
    table = json.loads(rc.BEHAVIOR_PATH.read_text(encoding="utf-8"))
    for phase in rc.KNOWN_PHASES:
        for lang_key in ("ZH", "ZH_TW", "EN", "JA"):
            entry = table["phases"][phase][lang_key]
            assert entry["label"].strip()
            assert len(entry["guide"]) >= 3
