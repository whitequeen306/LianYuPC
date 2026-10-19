"""场景扩展块：群聊 / 晚安 / 主动开口真实环境 / 时间兜底。"""

from datetime import datetime

import pytest

from app.proactive_context import (
    PROACTIVE_ENV_HEADER,
    PROACTIVE_ENV_RULES,
    build_proactive_real_world_block,
    resolve_proactive_city,
)
from app.prompt_assembly import is_goodnight_time
from app.system_prompt import assemble_scene_prompt


def test_time_fact_computed_when_not_passed():
    prompt = assemble_scene_prompt(
        character_name="小林", settings={"personality": "温柔"},
        prompt_template=None, include_time=True, after_base="full", city=None)
    assert "当前真实时间" in prompt


def test_goodnight_block_only_in_late_night_and_with_keyword():
    late = datetime(2024, 1, 1, 23, 30)
    assert is_goodnight_time(late)
    prompt = assemble_scene_prompt(
        character_name="小林", settings={"personality": "温柔"}, prompt_template=None,
        include_time=True, time_fact="[[TIME]]", after_base="full", city=None,
        last_user_message="我先睡了", now=late)
    assert "晚安模式" in prompt
    assert prompt.index("晚安模式") < prompt.index("表达要求")


def test_goodnight_not_added_in_daytime():
    daytime = datetime(2024, 1, 1, 14, 0)
    assert not is_goodnight_time(daytime)
    prompt = assemble_scene_prompt(
        character_name="小林", settings={"personality": "温柔"}, prompt_template=None,
        include_time=True, time_fact="[[TIME]]", after_base="full", city=None,
        last_user_message="晚安", now=daytime)
    assert "晚安模式" not in prompt


def test_resolve_proactive_city_priority():
    assert resolve_proactive_city({"fictional_city": "枫丹", "city": "上海"}) == "枫丹"
    assert resolve_proactive_city({"city": "上海"}) == "上海"
    assert resolve_proactive_city({}) == "上海"


@pytest.mark.asyncio
async def test_proactive_block_with_weather():
    async def fake_fetch(city: str) -> str:
        return f"{city} 当前天气：晴，气温 26°C。"

    block = await build_proactive_real_world_block({"city": "上海"}, fetch_weather=fake_fetch)
    assert block.startswith(PROACTIVE_ENV_HEADER)
    assert "上海 当前天气：晴" in block
    assert block.endswith(PROACTIVE_ENV_RULES)


@pytest.mark.asyncio
async def test_proactive_block_weather_failure_still_has_rules():
    async def broken(city: str) -> str:
        raise RuntimeError("weather down")

    block = await build_proactive_real_world_block({"city": "上海"}, fetch_weather=broken)
    assert "未能获取「上海」当前天气" in block
    assert block.endswith(PROACTIVE_ENV_RULES)
