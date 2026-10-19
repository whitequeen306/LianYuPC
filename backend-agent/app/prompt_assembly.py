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

from datetime import datetime

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


# 自然口语风格块（OutputLanguageService.buildNaturalStyleBlock）
# 键：(语言, 是否允许内心独白) → fragments.json 中的行号
_NATURAL_STYLE_LINES = {
    ("ja", True): 162, ("ja", False): 167,
    ("en", True): 172, ("en", False): 177,
    ("zh-tw", True): 182, ("zh-tw", False): 187,
    ("zh", True): 192, ("zh", False): 197,
}


def natural_style_block(lang: str | None, show_inner_thoughts: bool = True) -> str:
    """对齐 OutputLanguageService.buildNaturalStyleBlock（四语种 × 内心独白开关）。"""
    key = (normalize_language(lang), bool(show_inner_thoughts))
    return fragment("OutputLanguageService.java", _NATURAL_STYLE_LINES[key])


def append_natural_style(base_prompt: str, lang: str | None,
                         show_inner_thoughts: bool = True) -> str:
    """对齐 ChatTurnPromptAssembler.enforceNaturalChatStyle。"""
    prompt = base_prompt or ""
    return prompt + natural_style_block(lang, show_inner_thoughts)


def compose_prompt(base_prompt: str,
                   time_fact: str | None = None,
                   city: str | None = None,
                   lang: str | None = "zh",
                   show_inner_thoughts: bool = True) -> str:
    """按 Java `assemble()` 的真实顺序组合已迁移的段落。

    顺序（对应 ChatTurnPromptAssembler.assemble 第 97-105 行）：
        base → 时间锚点（includeTime）→ 城市权威 → 自然风格
    晚安模式不在此处：它依赖「当前是否深夜」，属运行时判断，由调用方决定是否追加。

    未包含：记忆块 / 关系块 / 会话摘要块 —— 它们要拼接在 **base 内部**
    （`buildSystemPrompt(character, memoryContext, ...)`），不是 base 之后，
    所以本函数只组合 base 之后的部分。
    """
    prompt = base_prompt or ""
    if time_fact is not None:
        prompt = append_time_context(prompt, time_fact)
    if city is not None:
        prompt = append_city_context(prompt, city)
    return append_natural_style(prompt, lang, show_inner_thoughts)


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


def is_goodnight_time(now: datetime | None = None) -> bool:
    """对齐 Java `isGoodnightTime`：23:00–07:59 视为深夜。

    `now` 仅供测试注入；生产走服务器本地时区，与 Java `LocalTime.now()` 一致。
    """
    current = now or datetime.now()
    minutes = current.hour * 60 + current.minute
    return minutes >= 23 * 60 or minutes < 8 * 60


__all__ = [
    "compose_prompt",
    "append_natural_style",
    "natural_style_block",
    "GOODNIGHT_KEYWORDS",
    "append_city_context",
    "append_goodnight_context",
    "append_time_context",
    "contains_goodnight_keyword",
    "goodnight_block",
    "is_goodnight_time",
    "normalize_language",
]
