"""`get_current_time` —— 逐字移植 Java `TimeTool`（便于跨端 diff）。

时段划分与允许/禁止问候语直接对齐 Java `DayPart`：
00:00–04:59 深夜 / 05:00–08:59 清晨 / 09:00–11:29 上午 / 11:30–13:29 中午 /
13:30–17:29 下午 / 17:30–19:29 傍晚 / 19:30–22:59 晚上 / 23:00– 深夜
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from typing import Any
from zoneinfo import ZoneInfo

from app.config import get_settings
from app.tools.registry import ToolSpec, register

_WEEKDAYS_ZH = ("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")


@dataclass(frozen=True)
class DayPart:
    label: str
    allowed_greetings: str
    forbidden_greetings: str


EARLY_MORNING = DayPart("清晨", "早安、早上好、早", "晚安、晚上好、午安")
MORNING = DayPart("上午", "早上好、上午好、早", "晚安、晚上好")
NOON = DayPart("中午", "中午好、午安", "早上好、晚安、晚上好")
AFTERNOON = DayPart("下午", "下午好", "早上好、晚安")
DUSK = DayPart("傍晚", "傍晚好、晚上好", "早上好、午安")
EVENING = DayPart("晚上", "晚上好、晚安（若用户要睡）", "早上好、早安、上午好、午安")
LATE_NIGHT = DayPart("深夜", "还没睡吗、夜深了、晚安", "早上好、早安、上午好、午安、下午好")


def resolve_day_part(hour: int, minute: int) -> DayPart:
    """对齐 Java `TimeTool.resolveDayPart`。"""
    minute_of_day = hour * 60 + minute
    if minute_of_day < 5 * 60:
        return LATE_NIGHT
    if minute_of_day < 9 * 60:
        return EARLY_MORNING
    if minute_of_day < 11 * 60 + 30:
        return MORNING
    if minute_of_day < 13 * 60 + 30:
        return NOON
    if minute_of_day < 17 * 60 + 30:
        return AFTERNOON
    if minute_of_day < 19 * 60 + 30:
        return DUSK
    if minute_of_day < 23 * 60:
        return EVENING
    return LATE_NIGHT


def read_current_time_fact(zone_name: str | None = None, *, now: datetime | None = None) -> str:
    """逐字移植 Java `readCurrentTimeFact`。`now` 仅供测试注入。"""
    zone = ZoneInfo(zone_name or get_settings().time_zone)
    current = now.astimezone(zone) if now is not None else datetime.now(zone)
    part = resolve_day_part(current.hour, current.minute)
    weekday = _WEEKDAYS_ZH[current.weekday()]
    formatted = f"{current.year}年{current.month:02d}月{current.day:02d}日 {weekday} {current:%H:%M:%S}"
    return (
        f"当前真实时间：{formatted}（{zone.key}）。"
        f"当前时段：{part.label}。"
        f"时段问候只能使用：{part.allowed_greetings}。"
        f"禁止使用：{part.forbidden_greetings}。"
        "涉及今天、现在、几点、星期几、早晚安等，必须以本条时间为准；"
        "历史消息里的「早上好/晚安」等只代表当时，不代表现在。"
    )


async def handle_get_current_time(arguments: dict[str, Any]) -> str:
    city = (arguments or {}).get("city")
    zone_name = None
    if city:
        # Java 走 CityTimeZone.fromCity；Python 侧仅支持显式 IANA 时区名，
        # 城市→时区映射属 Java 的 CityTimeZone 表，未迁移前回落到配置时区。
        candidate = str(city).strip()
        if "/" in candidate:
            zone_name = candidate
    return read_current_time_fact(zone_name)


TIME_TOOL = ToolSpec(
    name="get_current_time",
    description=(
        "获取当前真实日期与时间（应用配置时区）。"
        "当用户询问现在几点、今天几号、星期几、日期等与「当前时间」相关的问题时调用。"
    ),
    parameters={
        "type": "object",
        "properties": {
            "city": {
                "type": "string",
                "description": "可选，IANA 时区名（如 Asia/Shanghai）；省略则用默认时区。",
            }
        },
        "required": [],
    },
    handler=handle_get_current_time,
)

register(TIME_TOOL)

__all__ = [
    "AFTERNOON",
    "DUSK",
    "DayPart",
    "EARLY_MORNING",
    "EVENING",
    "LATE_NIGHT",
    "MORNING",
    "NOON",
    "TIME_TOOL",
    "handle_get_current_time",
    "read_current_time_fact",
    "resolve_day_part",
]
