"""工具层 + 有界工具循环测试。

重点：**循环上限**（审计 A4 的解药）——Java 侧无计数器，本侧显式 3 轮封顶。
"""

import httpx
import pytest
import respx

from app.gateway import ModelGateway
from app.http_client import outbound_timeout
from app.resilience import reset_breakers, reset_lanes
from app.tool_loop import run_tool_loop
from app.tools import supported_tool_names, supported_tool_schemas
from app.tools.time_tool import read_current_time_fact, resolve_day_part
from app.tools.weather_tool import WEATHER_TOOL, clear_cache, handle_get_weather

BASE = "https://fake/v1"


@pytest.fixture(autouse=True)
def _reset():
    reset_breakers()
    reset_lanes()
    clear_cache()
    yield
    reset_breakers()
    reset_lanes()
    clear_cache()


def _client():
    return httpx.AsyncClient(timeout=outbound_timeout())


# ── 能力声明 ──


def test_supported_tools_are_declared():
    """先只声明无外部依赖的两个工具（memory_search / recent_life / write_diary / computer_task 待 P5）。"""
    assert supported_tool_names() == frozenset({"get_current_time", "get_weather"})


def test_tool_schema_shape_is_openai_compatible():
    schemas = supported_tool_schemas(["get_current_time"])
    assert len(schemas) == 1
    assert schemas[0]["type"] == "function"
    assert schemas[0]["function"]["name"] == "get_current_time"
    assert schemas[0]["function"]["parameters"]["type"] == "object"


def test_unknown_tool_is_ignored():
    assert supported_tool_schemas(["does_not_exist"]) == []


# ── 时间工具（对齐 TimeTool）──


@pytest.mark.parametrize(
    "hour,minute,label",
    [
        (3, 0, "深夜"),
        (7, 0, "清晨"),
        (10, 0, "上午"),
        (12, 0, "中午"),
        (15, 0, "下午"),
        (18, 0, "傍晚"),
        (21, 0, "晚上"),
        (23, 30, "深夜"),
    ],
)
def test_day_part_boundaries_match_java(hour, minute, label):
    assert resolve_day_part(hour, minute).label == label


def test_time_fact_wording_contains_required_anchors():
    fact = read_current_time_fact("Asia/Shanghai")
    assert fact.startswith("当前真实时间：")
    assert "当前时段：" in fact
    assert "时段问候只能使用：" in fact
    assert "禁止使用：" in fact
    assert "历史消息里的「早上好/晚安」等只代表当时，不代表现在。" in fact


# ── 天气工具 ──


@pytest.mark.asyncio
@respx.mock
async def test_weather_parses_wttr_payload():
    respx.get("https://wttr.in/上海").respond(
        json={
            "current_condition": [
                {
                    "temp_C": "21",
                    "FeelsLikeC": "20",
                    "humidity": "55",
                    "lang_zh": [{"value": "多云"}],
                }
            ]
        }
    )
    fact = await handle_get_weather({"city": "上海"})
    assert "上海 当前天气：多云" in fact
    assert "21°C" in fact
    assert "55%" in fact


@pytest.mark.asyncio
@respx.mock
async def test_weather_cached_within_ttl():
    route = respx.get("https://wttr.in/上海").respond(
        json={"current_condition": [{"temp_C": "20", "lang_zh": [{"value": "晴"}]}]}
    )
    await handle_get_weather({"city": "上海"})
    await handle_get_weather({"city": "上海"})
    assert route.call_count == 1  # 第二次命中进程内缓存


@pytest.mark.asyncio
@respx.mock
async def test_weather_http_error_raises():
    respx.get("https://wttr.in/上海").respond(status_code=503)
    with pytest.raises(RuntimeError):
        await handle_get_weather({"city": "上海"})


def test_weather_tool_has_city_parameter_optional():
    params = WEATHER_TOOL.parameters
    assert "city" in params["properties"]
    assert params.get("required") == []


# ── 工具循环 ──


def _tool_call(name: str, args: str = "{}", call_id: str = "call_1") -> dict:
    return {
        "id": call_id,
        "type": "function",
        "function": {"name": name, "arguments": args},
    }


@pytest.mark.asyncio
@respx.mock
async def test_loop_without_tools_is_single_call():
    route = respx.post(f"{BASE}/chat/completions").respond(
        json={"choices": [{"message": {"content": "你好"}}]}
    )
    client = _client()
    try:
        result = await run_tool_loop(
            ModelGateway(client=client),
            base_url=BASE,
            api_key="k",
            model="m",
            messages=[{"role": "user", "content": "在吗"}],
            tool_names=None,
            temperature=0.8,
            max_tokens=100,
        )
    finally:
        await client.aclose()
    assert result.content == "你好"
    assert result.rounds == 0
    assert route.call_count == 1


@pytest.mark.asyncio
@respx.mock
async def test_loop_executes_tool_then_answers():
    """模型先要时间 → 工具结果回灌 → 再给最终答复。"""
    responses = [
        httpx.Response(200, json={
            "choices": [{"message": {"content": "", "tool_calls": [_tool_call("get_current_time")]}}]
        }),
        httpx.Response(200, json={"choices": [{"message": {"content": "现在快十点了"}}]}),
    ]
    respx.post(f"{BASE}/chat/completions").mock(side_effect=responses)
    client = _client()
    try:
        result = await run_tool_loop(
            ModelGateway(client=client),
            base_url=BASE, api_key="k", model="m",
            messages=[{"role": "user", "content": "现在几点了"}],
            tool_names=["get_current_time"],
            temperature=0.8, max_tokens=100,
        )
    finally:
        await client.aclose()
    assert result.content == "现在快十点了"
    assert result.rounds == 1
    assert result.tool_names_called == ["get_current_time"]
    assert result.truncated is False


@pytest.mark.asyncio
@respx.mock
async def test_loop_is_bounded_by_max_rounds():
    """模型无限要工具也必须在上限处截断（审计 A4 的核心断言）。"""
    always_tool = httpx.Response(200, json={
        "choices": [{"message": {"content": "", "tool_calls": [_tool_call("get_current_time")]}}]
    })
    route = respx.post(f"{BASE}/chat/completions").mock(
        side_effect=[always_tool for _ in range(10)]
    )
    client = _client()
    try:
        result = await run_tool_loop(
            ModelGateway(client=client),
            base_url=BASE, api_key="k", model="m",
            messages=[{"role": "user", "content": "loop"}],
            tool_names=["get_current_time"],
            temperature=0.8, max_tokens=100,
            max_rounds=3,
        )
    finally:
        await client.aclose()
    assert result.truncated is True
    assert result.rounds == 3
    # 3 轮工具执行 + 最后 1 次判定超过上限的调用
    assert route.call_count == 4


@pytest.mark.asyncio
@respx.mock
async def test_unknown_tool_in_call_yields_error_text_not_crash():
    responses = [
        httpx.Response(200, json={
            "choices": [{"message": {"content": "", "tool_calls": [_tool_call("nope")]}}]
        }),
        httpx.Response(200, json={"choices": [{"message": {"content": "好的"}}]}),
    ]
    respx.post(f"{BASE}/chat/completions").mock(side_effect=responses)
    client = _client()
    try:
        result = await run_tool_loop(
            ModelGateway(client=client),
            base_url=BASE, api_key="k", model="m",
            messages=[{"role": "user", "content": "x"}],
            tool_names=["get_current_time"],
            temperature=0.8, max_tokens=100,
        )
    finally:
        await client.aclose()
    assert result.content == "好的"
    assert result.tool_names_called == ["nope"]


@pytest.mark.asyncio
@respx.mock
async def test_tool_failure_is_fed_back_not_raised():
    """工具自身失败（如天气 503）要回灌成文本，不能让整轮挂掉。"""
    respx.get("https://wttr.in/上海").respond(status_code=503)
    responses = [
        httpx.Response(200, json={
            "choices": [{"message": {"content": "", "tool_calls": [_tool_call("get_weather", '{"city":"上海"}')]}}]
        }),
        httpx.Response(200, json={"choices": [{"message": {"content": "天气查不到，不过记得带伞"}}]}),
    ]
    respx.post(f"{BASE}/chat/completions").mock(side_effect=responses)
    client = _client()
    try:
        result = await run_tool_loop(
            ModelGateway(client=client),
            base_url=BASE, api_key="k", model="m",
            messages=[{"role": "user", "content": "上海天气"}],
            tool_names=["get_weather"],
            temperature=0.8, max_tokens=100,
        )
    finally:
        await client.aclose()
    assert result.content == "天气查不到，不过记得带伞"
