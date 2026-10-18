"""情绪块：与 Java 逐字节比对（448 个组合）。"""

import json
from pathlib import Path

import pytest

from app import emotion_block as eb

GOLDEN = Path(__file__).resolve().parent.parent / "app" / "prompts" / "emotion_golden.json"

LANGS = {"ZH": "zh", "ZH_TW": "zh-TW", "JA": "ja", "EN": "en"}
EMOTIONS = ["平静", "生气", "吃醋", "难过", "担心", "疲惫", "想念", "撒娇", "开心", "兴奋", "未知情绪"]
INTENSITIES = [10, 39, 40, 59, 60, 64, 65, 79, 80, 100]


@pytest.fixture(scope="module")
def golden() -> dict:
    assert GOLDEN.exists(), (
        "缺少 emotion_golden.json：运行 "
        "mvn -pl lianyu-service -am test -Dtest=EmotionBlockGoldenTest"
    )
    return json.loads(GOLDEN.read_text(encoding="utf-8"))


@pytest.mark.parametrize("lang_key,lang", sorted(LANGS.items()))
@pytest.mark.parametrize("emotion", EMOTIONS)
@pytest.mark.parametrize("intensity", INTENSITIES)
def test_block_matches_java(golden, lang_key, lang, emotion, intensity):
    actual = eb.format_emotion_block(
        lang=lang, current_emotion=emotion, emotion_intensity=intensity,
        status_text="今天有点累", previous_emotion="")
    assert actual == golden["cases"][f"{lang_key}|{emotion}|{intensity}"]


@pytest.mark.parametrize("lang_key,lang", sorted(LANGS.items()))
def test_previous_emotion_and_empty_status_match_java(golden, lang_key, lang):
    actual = eb.format_emotion_block(
        lang=lang, current_emotion="难过", emotion_intensity=70,
        status_text=None, previous_emotion="开心")
    assert actual == golden["cases"][f"{lang_key}|prev|empty-status"]


@pytest.mark.parametrize("lang_key,lang", sorted(LANGS.items()))
def test_defaults_match_java(golden, lang_key, lang):
    """全空 state：情绪默认平静、强度默认 50。"""
    actual = eb.format_emotion_block(lang=lang)
    assert actual == golden["cases"][f"{lang_key}|defaults"]


def test_strong_threshold_differs_from_label_band():
    """强度 65 是 strong 分界，但标签档位分界是 60/80 —— 两者不同，容易混。"""
    assert eb.STRONG_THRESHOLD == 65
    assert eb.emotion_behavior_guide("zh", "生气", 65) == eb.emotion_behavior_guide("zh", "生气", 80)
    assert eb.emotion_behavior_guide("zh", "生气", 64) != eb.emotion_behavior_guide("zh", "生气", 65)


def test_previous_emotion_line_absent_when_blank():
    with_prev = eb.format_emotion_block(lang="zh", previous_emotion="开心")
    without = eb.format_emotion_block(lang="zh", previous_emotion="")
    assert "上一刻心情" in with_prev
    assert "上一刻心情" not in without