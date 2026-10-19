"""主动开口前的真实环境块（时间 + 天气）。

逐字对齐 Java `ProactiveRealWorldContextService.buildBlock`：
- 城市优先级：角色虚构城市 `fictional_city` > 角色设定 `city` > 配置默认城市
- 天气取不到时仍输出「未能获取…」提示，不中断组装
- 时间是 system 里已注入的锚点，这里只补天气与使用约束
"""

from __future__ import annotations

import logging
from typing import Any, Awaitable, Callable, Mapping

from app.config import get_settings

logger = logging.getLogger(__name__)

PROACTIVE_ENV_HEADER = (
    "\n\n=== 主动开口参考环境（时间以 system 中已注入的当前真实时间为准，"
    "请自然融入问候，勿逐条朗读） ===\n"
)

PROACTIVE_ENV_RULES = (
    "写主动消息时可自然参考以上真实时间与天气（如早晚安、冷暖、是否适合出门等），"
    "语气仍须完全符合角色设定；早晚安必须与 system 中「当前时段」一致，"
    "绝不要因为历史里出现过「早上好」就在晚上再说早上好；"
    "不要像播报员念数据，也不要编造与上述不符的时间或天气。"
    "主动开口也要像日常私聊：可关心用户今天过得怎样、累不累、吃了没，"
    "不要自顾自讲设定剧情或推进世界观故事。"
)


def resolve_proactive_city(settings: Mapping[str, Any] | None) -> str:
    """对齐 Java `resolveProactiveCity`。"""
    current = settings or {}
    for key in ("fictional_city", "city"):
        value = current.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    return (get_settings().default_city or "").strip()


async def build_proactive_real_world_block(
    settings: Mapping[str, Any] | None,
    *,
    fetch_weather: Callable[[str], Awaitable[str]] | None = None,
    enabled: bool = True,
) -> str:
    """返回可追加在 system prompt 末尾的环境块；未启用返回空串。"""
    if not enabled:
        return ""
    city = resolve_proactive_city(settings)
    weather_fact = ""
    if city:
        try:
            if fetch_weather is None:
                from app.tools.weather_tool import fetch_weather as _fetch

                fetch_weather = _fetch
            weather_fact = (await fetch_weather(city) or "").strip()
        except Exception as exc:  # noqa: BLE001 - 天气失败不应阻塞主动发言
            logger.debug("proactive weather fetch failed for %s: %s", city, exc)
    block = PROACTIVE_ENV_HEADER
    if weather_fact:
        block += weather_fact + "\n"
    else:
        block += f"（未能获取「{city}」当前天气，可仅结合时段与季节问候。）\n"
    block += PROACTIVE_ENV_RULES
    return block


__all__ = [
    "PROACTIVE_ENV_HEADER",
    "PROACTIVE_ENV_RULES",
    "build_proactive_real_world_block",
    "resolve_proactive_city",
]
