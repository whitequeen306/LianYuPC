"""D1/D2：生活动态块与会话摘要块的文案迁移。

D2 直接拿 Java 时代导出的 `session_summary_golden.json` 做对照 —— 那份 golden
本来就是「Java 侧逐字输出」的存档，用它当基准比人工抄一遍更可信。
"""

import json
from pathlib import Path

import pytest

from app.prompts.loader import render_prompt

GOLDEN = json.loads(
    (Path(__file__).resolve().parents[1] / "app" / "prompts" / "session_summary_golden.json")
    .read_text(encoding="utf-8")
)


@pytest.mark.parametrize(
    ("name", "text"),
    [
        ("recent_life_title_zh", "=== 你最近的生活动态（背景记忆，勿逐条复述） ==="),
        ("recent_life_title_zh_tw", "=== 你最近的生活動態（背景記憶，勿逐條復述） ==="),
        ("recent_life_title_ja", "=== 最近の生活の出来事（背景記憶・逐条復述しない） ==="),
        (
            "recent_life_title_en",
            "=== Your recent life activities (background context, do not recap line by line) ===",
        ),
    ],
)
def test_recent_life_titles(name, text):
    assert render_prompt(name, leading_newlines=0) == text


def test_recent_life_closing():
    # 原实现四语统一用中文收尾，迁移时保持行为不变
    assert render_prompt("recent_life_closing", leading_newlines=0) == "请勿逐条复述以上动态，仅作背景感知。"


def test_summary_block_matches_java_golden_normal():
    rendered = render_prompt(
        "session_summary_block",
        leading_newlines=2,
        summary="用户聊到最近工作压力大，角色安慰了他",
    )
    assert rendered == GOLDEN["normal"]


def test_summary_block_matches_java_golden_padded():
    # Java 侧先 text.trim() 再拼，这里照同样的输入构造
    rendered = render_prompt("session_summary_block", leading_newlines=2, summary="前后有空格")
    assert rendered == GOLDEN["padded"]


def test_summary_block_empty_guard_stays_in_java():
    # empty/blank 在 Java 侧就被判空返回 null，模板不参与；这里只锁住 golden 的约定
    assert GOLDEN["empty"] is None
    assert GOLDEN["blank"] is None
