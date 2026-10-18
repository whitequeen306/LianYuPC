"""情绪块 —— 对齐 `CharacterStateService.formatEmotionBlock`。

为什么用查表而不是把文案写进代码：这些是四语种的情绪行为指南
（生气/吃醋/难过/担心/疲惫/想念/撒娇/开心/兴奋 × strong/weak），
手抄必然出错。表由 Java 的 `EmotionBlockGoldenTest` 反射导出，
Python 只负责「选表 + 拼装」。

两个容易混的阈值（Java 里确实不同）：
- 强度档位：>=80 / >=60 / >=40 / else → 非常/比较/有些/略微
- strong 阈值：intensity >= 65（决定行为指南用强版还是弱版）
"""

from __future__ import annotations

import json
from functools import lru_cache
from pathlib import Path

GOLDEN_PATH = Path(__file__).resolve().parent / "prompts" / "emotion_golden.json"

DEFAULT_EMOTION = "平静"
DEFAULT_INTENSITY = 50
STRONG_THRESHOLD = 65

MOOD_LINE = "\n当前心情："
STATUS_LINE = "\n状态签名："
PREVIOUS_LINE = "\n上一刻心情："
PREVIOUS_SUFFIX = "（若刚切换，语气可带一点过渡，勿瞬间判若两人）"


@lru_cache(maxsize=1)
def _tables() -> dict:
    payload = json.loads(GOLDEN_PATH.read_text(encoding="utf-8"))
    return {
        "headers": payload["headers"],
        "labels": payload["labels"],
        "guides": payload["guides"],
        "notes": payload["notes"],
    }


def _lang_key(lang: str | None) -> str:
    code = (lang or "zh").strip().upper().replace("-", "_")
    if code in ("ZH_TW", "ZHTW", "ZH_HANT", "ZH_HK"):
        return "ZH_TW"
    if code.startswith("JA"):
        return "JA"
    if code.startswith("EN"):
        return "EN"
    return "ZH"


def _label_band(intensity: int) -> int:
    if intensity >= 80:
        return 90
    if intensity >= 60:
        return 70
    if intensity >= 40:
        return 50
    return 20


def intensity_label(lang: str | None, intensity: int) -> str:
    band = _label_band(intensity)
    return _tables()["labels"][f"{_lang_key(lang)}|{band}"]


def emotion_header(lang: str | None) -> str:
    return _tables()["headers"][_lang_key(lang)]


def emotion_priority_note(lang: str | None, emotion: str) -> str:
    key = f"{_lang_key(lang)}|{emotion}"
    notes = _tables()["notes"]
    if key in notes:
        return notes[key]
    # 未知情绪走 Java 的 default 分支（按「平静」处理）
    return notes[f"{_lang_key(lang)}|平静"]


def emotion_behavior_guide(lang: str | None, emotion: str, intensity: int) -> str:
    strong = intensity >= STRONG_THRESHOLD
    key = f"{_lang_key(lang)}|{emotion}|{str(strong).lower()}"
    guides = _tables()["guides"]
    if key in guides:
        return guides[key]
    return guides[f"{_lang_key(lang)}|平静|{str(strong).lower()}"]


def format_emotion_block(
    *,
    lang: str | None = "zh",
    current_emotion: str | None = None,
    emotion_intensity: int | None = None,
    status_text: str | None = None,
    previous_emotion: str | None = None,
) -> str:
    """对齐 `formatEmotionBlock(state, lang)`。"""
    emotion = current_emotion or DEFAULT_EMOTION
    intensity = DEFAULT_INTENSITY if emotion_intensity is None else int(emotion_intensity)
    status = status_text or ""
    previous = previous_emotion or ""

    header = emotion_header(lang)
    label = intensity_label(lang, intensity)
    guide = emotion_behavior_guide(lang, emotion, intensity)
    note = emotion_priority_note(lang, emotion)

    block = header + MOOD_LINE + label + emotion + STATUS_LINE + status
    if previous.strip():
        block += PREVIOUS_LINE + previous + PREVIOUS_SUFFIX
    return block + "\n\n" + guide + "\n" + note


__all__ = [
    "DEFAULT_EMOTION",
    "DEFAULT_INTENSITY",
    "MOOD_LINE",
    "STRONG_THRESHOLD",
    "emotion_behavior_guide",
    "emotion_header",
    "emotion_priority_note",
    "format_emotion_block",
    "intensity_label",
]