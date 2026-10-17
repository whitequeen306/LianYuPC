"""`get_weather` —— 移植 Java `WeatherTool` 的对外语义（wttr.in + 缓存）。

差异（已在总纲 §9 记录）：Java 用 Redis 缓存 15 分钟；Python 侧本阶段不直连 Redis（D7），
改用**进程内 TTL 缓存**——单副本语义等价，多副本时各自缓存（可接受，天气数据本身弱一致）。
"""

from __future__ import annotations

import logging
import time
from typing import Any

import httpx

from app.config import get_settings
from app.http_client import new_client
from app.tools.registry import ToolSpec, register

logger = logging.getLogger(__name__)

_cache: dict[str, tuple[float, str]] = {}


def _cache_get(key: str, ttl_seconds: float) -> str | None:
    entry = _cache.get(key)
    if entry is None:
        return None
    stored_at, value = entry
    if time.monotonic() - stored_at > ttl_seconds:
        _cache.pop(key, None)
        return None
    return value


def _cache_put(key: str, value: str) -> None:
    _cache[key] = (time.monotonic(), value)


def clear_cache() -> None:
    _cache.clear()


async def fetch_weather(city: str, *, client: httpx.AsyncClient | None = None) -> str:
    """请求 wttr.in `?format=j1`，返回可直接进 prompt 的事实句。"""
    settings = get_settings()
    base = settings.weather_base_url.rstrip("/")
    url = f"{base}/{city}"
    params = {"format": "j1", "lang": "zh"}

    async def _fetch() -> httpx.Response:
        if client is not None:
            return await client.get(url, params=params)
        async with new_client() as own:
            return await own.get(url, params=params)

    response = await _fetch()
    if response.status_code != 200:
        raise RuntimeError(f"weather http {response.status_code}")
    data = response.json()
    current = (data.get("current_condition") or [{}])[0]
    desc_list = current.get("lang_zh") or current.get("weatherDesc") or [{}]
    desc = (desc_list[0] or {}).get("value", "").strip() if desc_list else ""
    temp = current.get("temp_C", "?")
    feels = current.get("FeelsLikeC", "?")
    humidity = current.get("humidity", "?")
    return (
        f"{city} 当前天气：{desc or '未知'}，气温 {temp}°C（体感 {feels}°C），湿度 {humidity}%。"
        "以上为真实天气数据，涉及天气、穿衣、是否带伞时以此为准。"
    )


async def handle_get_weather(arguments: dict[str, Any]) -> str:
    settings = get_settings()
    city = str((arguments or {}).get("city") or settings.default_city).strip() or settings.default_city
    key = city.lower()
    cached = _cache_get(key, settings.weather_cache_minutes * 60)
    if cached is not None:
        return cached
    fact = await fetch_weather(city)
    _cache_put(key, fact)
    return fact


WEATHER_TOOL = ToolSpec(
    name="get_weather",
    description=(
        "查询指定城市或角色设定城市的当前真实天气。"
        "当用户询问天气、气温、穿什么、是否带伞时调用。"
    ),
    parameters={
        "type": "object",
        "properties": {
            "city": {
                "type": "string",
                "description": "城市名（中文，如 上海）；省略则使用角色设定城市或默认城市。",
            }
        },
        "required": [],
    },
    handler=handle_get_weather,
)

register(WEATHER_TOOL)

__all__ = [
    "WEATHER_TOOL",
    "clear_cache",
    "fetch_weather",
    "handle_get_weather",
]
