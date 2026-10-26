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


# 「主动开口参考环境」块的字节基线（golden）。
# 这段文案原先长在 Java `ProactiveRealWorldContextService.buildBlock` 里，2026-10-26 的
# b161fa7 已在 Python 重建（app/proactive_context.py），但 Java 那份一直没删、也没人调用。
# 下面的字面量是当时 Java 输出的**逐字快照**（按 JLS 文本块语义还原：开分隔符后的换行不算内容、
# 去公共缩进、行尾 `\` 续行不产生换行 → 规则段是一整行）。故意在这里把文案抄一遍而不是
# 引用模块常量：引用常量等于拿常量校验常量，改错字也发现不了。
_GOLDEN_HEADER = (
    "\n\n=== 主动开口参考环境（时间以 system 中已注入的当前真实时间为准，"
    "请自然融入问候，勿逐条朗读） ===\n"
)
_GOLDEN_RULES = (
    "写主动消息时可自然参考以上真实时间与天气（如早晚安、冷暖、是否适合出门等），"
    "语气仍须完全符合角色设定；早晚安必须与 system 中「当前时段」一致，"
    "绝不要因为历史里出现过「早上好」就在晚上再说早上好；"
    "不要像播报员念数据，也不要编造与上述不符的时间或天气。"
    "主动开口也要像日常私聊：可关心用户今天过得怎样、累不累、吃了没，"
    "不要自顾自讲设定剧情或推进世界观故事。"
)


def test_proactive_env_constants_match_java_snapshot():
    assert PROACTIVE_ENV_HEADER == _GOLDEN_HEADER
    assert PROACTIVE_ENV_RULES == _GOLDEN_RULES
    # 规则段必须是「一整行」：Java 用行尾 \ 把 5 行连成一句，换成多行会改变模型读到的段落结构
    assert "\n" not in PROACTIVE_ENV_RULES


@pytest.mark.asyncio
async def test_proactive_block_bytes_with_weather():
    async def fake_fetch(city: str) -> str:
        return "上海 当前天气：晴，气温 26°C。"

    block = await build_proactive_real_world_block({"city": "上海"}, fetch_weather=fake_fetch)
    assert block == _GOLDEN_HEADER + "上海 当前天气：晴，气温 26°C。\n" + _GOLDEN_RULES


@pytest.mark.asyncio
async def test_proactive_block_bytes_without_weather():
    async def empty_fetch(city: str) -> str:
        return ""

    block = await build_proactive_real_world_block({"city": "上海"}, fetch_weather=empty_fetch)
    assert block == _GOLDEN_HEADER + "（未能获取「上海」当前天气，可仅结合时段与季节问候。）\n" + _GOLDEN_RULES
