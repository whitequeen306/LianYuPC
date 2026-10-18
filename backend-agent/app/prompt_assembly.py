"""system prompt 组装（P5.5，逐段迁移中）。

当前已迁：**纯文本段落** —— 时间锚点、城市权威、晚安模式。
未迁：记忆块、关系/情绪块、会话摘要块、场景指令主体（依赖 Java 侧服务与数据）。

保真策略：
- 文本本身来自 `fragments.json`（由 Java 源码生成，非手抄）
- 拼接顺序与 Java 一致（`base + 前缀片段 + 值 + 后缀片段`）
- `tests/test_prompt_assembly.py` 用 Java 真实执行产出的
  `assembler_golden.json` 做**逐字节比对**，而不是在 Python 里重算一遍
  （那样两边都错也会通过）
"""

from __future__ import annotations

from app.prompt_fragments import fragment

# Java: ChatTurnPromptAssembler.java 行号定位的片段
_TIME_PREFIX = ("ChatTurnPromptAssembler.java", 161)
_TIME_SUFFIX = ("ChatTurnPromptAssembler.java", 165)
_CITY_PREFIX = ("ChatTurnPromptAssembler.java", 183)
_CITY_SUFFIX = ("ChatTurnPromptAssembler.java", 187)

# 晚安模式：语言 → 片段行号（对齐 Java switch 的 case）
_GOODNIGHT_LINES = {
    "ja": 201,
    "en": 207,
    "zh-tw": 213,
    "zh": 219,
}

GOODNIGHT_KEYWORDS = (
    "晚安", "睡了", "睡觉", "眠い", "おやすみ", "good night", "goodnight",
    "nighty", "night night", "gn", "安", "困了", "我要睡了", "先睡了", "去睡了", "睡吧",
)


def append_time_context(base_prompt: str, time_fact: str) -> str:
    """对齐 Java `appendCurrentTimeContext`：base + 前缀 + timeFact + 后缀。"""
    return (
        base_prompt
        + fragment(*_TIME_PREFIX)
        + time_fact
        + fragment(*_TIME_SUFFIX)
    )


def append_city_context(base_prompt: str, city: str) -> str:
    """对齐 Java `appendCurrentRealCityContext`。

    Java 侧在此之前会判断 city_mode 是否为 real、城市是否为空；
    这些判断由调用方完成 —— 本函数只负责拼接（纯函数，便于比对）。
    """
    return (
        base_prompt
        + fragment(*_CITY_PREFIX)
        + city
        + fragment(*_CITY_SUFFIX)
    )


def normalize_language(lang: str | None) -> str:
    """把 Java 的 OutputLanguage 代码归一成本模块的键。"""
    code = (lang or "zh").strip().lower().replace("_", "-")
    if code in ("zh-tw", "zhtw", "zh-hant", "zh-hk"):
        return "zh-tw"
    if code.startswith("ja"):
        return "ja"
    if code.startswith("en"):
        return "en"
    return "zh"


def goodnight_block(lang: str | None) -> str:
    """返回晚安模式片段（不含 base）。"""
    return fragment("ChatTurnPromptAssembler.java", _GOODNIGHT_LINES[normalize_language(lang)])


def append_goodnight_context(base_prompt: str, lang: str | None) -> str:
    """对齐 Java `appendGoodnightContextIfApplicable` 的拼接部分。

    触发条件（是否深夜 + 用户是否说了晚安）由调用方判断，
    与 Java 一致地留在外层 —— 这样本函数仍是纯函数。
    """
    return base_prompt + goodnight_block(lang)


def contains_goodnight_keyword(text: str | None) -> bool:
    """对齐 Java `containsGoodnightKeyword`（大小写不敏感）。"""
    if not text:
        return False
    lowered = text.lower()
    return any(keyword.lower() in lowered for keyword in GOODNIGHT_KEYWORDS)


__all__ = [
    "GOODNIGHT_KEYWORDS",
    "append_city_context",
    "append_goodnight_context",
    "append_time_context",
    "contains_goodnight_keyword",
    "goodnight_block",
    "normalize_language",
]
