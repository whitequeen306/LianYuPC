"""backend-agent 入口：FastAPI 应用。

阶段 1 范围：
- POST /agent/desktop-observe  桌面感知（第一条迁移链路）
- GET  /agent/health           存活探针（compose healthcheck 用）

契约原则：与 Java 侧 ObserveController 的响应形状逐字段兼容，
由 Java 适配层（阶段 1 后续）做 vault 解析后转发，或 nginx 直接分流。
"""

import logging

from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel, Field

from app.config import get_settings
from app.desktop_observe import DesktopObserveRequest, observe_desktop
from app.gateway import ModelGateway, decode_png_base64, get_gateway

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
logger = logging.getLogger("lianyu-agent")

app = FastAPI(title="YuNian Agent Runtime", version="0.1.0")

# 进程级并发护栏（对齐 Java ObserveController 的双维度限流之一：占槽上限）
import asyncio

_observe_semaphore: dict[str, asyncio.Semaphore] = {}


def observe_limiter(key: str) -> asyncio.Semaphore:
    if key not in _observe_semaphore:
        _observe_semaphore[key] = asyncio.Semaphore(get_settings().desktop_observe_max_concurrency)
    return _observe_semaphore[key]


class DesktopObserveBody(BaseModel):
    """Java 适配层转发体：vault 解析后的 Stage 2 凭据以显式字段传入（仅内网可信）。"""

    user_id: int = Field(alias="userId")
    image_base64: str = Field(alias="imageBase64")
    window_title: str | None = Field(default=None, alias="windowTitle")
    persona: str | None = None
    text_base_url: str | None = Field(default=None, alias="textBaseUrl")
    text_api_key: str | None = Field(default=None, alias="textApiKey")
    text_model: str | None = Field(default=None, alias="textModel")

    model_config = {"populate_by_name": True}


class DesktopObserveResponse(BaseModel):
    """对齐 Java Result.ok(greeting)：有问候返回 greeting，无则 greeting 为 null。"""

    greeting: str | None


@app.get("/agent/health")
async def health() -> dict[str, str]:
    return {"status": "UP"}


@app.get("/agent/capabilities")
async def capabilities() -> dict[str, object]:
    """运行时可能力清单 —— Java 适配层切 AGENT 前必须校验。

    `missingTools` 里的工具在 Python 侧尚未实现；Java 链路若依赖它们，
    就**不应**路由到 Python（否则相对 Java 会降级，见总纲 §9 缺口 1）。
    """
    from app.tools import supported_tool_names

    java_tools = [
        "get_current_time",
        "get_weather",
        "memory_search",
        "get_my_recent_life",
        "write_diary",
        "computer_task",
    ]
    supported = sorted(supported_tool_names())
    return {
        "version": app.version,
        "supportedTools": supported,
        "missingTools": [t for t in java_tools if t not in supported],
        "languageGate": True,
        "circuitBreaker": True,
        "toolLoop": True,
        "maxToolRounds": get_settings().max_tool_rounds,
    }


@app.post("/agent/desktop-observe", response_model=DesktopObserveResponse)
async def desktop_observe(
    body: DesktopObserveBody,
    user_id_header: str | None = Header(default=None, alias="X-User-Id"),
) -> DesktopObserveResponse:
    # 阶段 1 由 Java 适配层完成 Sa-Token 认证后转发并携带 X-User-Id；
    # body.userId 与 header 不一致时拒绝（防跨用户读取）。
    if user_id_header and str(body.user_id) != user_id_header:
        raise HTTPException(status_code=403, detail="user mismatch")
    try:
        decode_png_base64(body.image_base64)
    except ValueError:
        logger.warning("Desktop observe: invalid base64 image")
        return DesktopObserveResponse(greeting=None)

    settings = get_settings()
    if not settings.multimodal_enabled or not settings.multimodal_api_key:
        return DesktopObserveResponse(greeting=None)

    gateway: ModelGateway = get_gateway()
    request = DesktopObserveRequest(
        user_id=body.user_id,
        image_base64=body.image_base64,
        window_title=body.window_title,
        persona=body.persona,
        text_base_url=body.text_base_url,
        text_api_key=body.text_api_key,
        text_model=body.text_model,
    )
    async with observe_limiter(str(body.user_id)):
        greeting = await observe_desktop(gateway, request=request)
    return DesktopObserveResponse(greeting=greeting)


# ─────────────────────────── 阶段 2：主动发言回合 ───────────────────────────


class ProactiveTurnBody(BaseModel):
    """主动发言生成请求（冷启动/跟进/城市变更/定时主动共用一张图）。

    Java 侧职责（不在 Python 重复）：vault 解析、人设 system 组装（ChatTurnPromptAssembler）、
    历史取窗、任务块选择、落库与通知。Python 只做「装配+模型调用」。
    """

    user_id: int = Field(alias="userId")
    scene: str = "PROACTIVE"
    persona: str                      # Java 组装好的 system 主体
    task_block: str = ""              # 资源化任务块（可空：SHADOW 对比时也可由 Python 渲染）
    history: list[dict[str, str]] = []
    user_trigger: str = "（系统触发主动发言，用户未发送新消息。）"
    text_base_url: str | None = Field(default=None, alias="textBaseUrl")
    text_api_key: str | None = Field(default=None, alias="textApiKey")
    text_model: str | None = Field(default=None, alias="textModel")
    character_id: int | None = Field(default=None, alias="characterId")
    provider: str | None = None
    expected_language: str | None = Field(default=None, alias="expectedLanguage")
    # Java 声明本条链路需要哪些工具；Python 只执行 supportedTools 内的（其余被忽略并记日志）
    tools: list[str] = []
    temperature: float = 0.8
    max_tokens: int | None = None

    model_config = {"populate_by_name": True}


class ProactiveTurnResponse(BaseModel):
    assistant_raw: str | None = None
    error: str | None = None


@app.post("/agent/proactive-turn", response_model=ProactiveTurnResponse)
async def proactive_turn(
    body: ProactiveTurnBody,
    user_id_header: str | None = Header(default=None, alias="X-User-Id"),
) -> ProactiveTurnResponse:
    if user_id_header and str(body.user_id) != user_id_header:
        raise HTTPException(status_code=403, detail="user mismatch")

    from app.graphs.proactive_turn import build_proactive_graph

    graph = build_proactive_graph(get_gateway())
    state = {
        "user_id": body.user_id,
        "scene": body.scene,
        "persona": body.persona,
        "task_block": body.task_block,
        "history": body.history,
        "user_trigger": body.user_trigger,
        "text_base_url": body.text_base_url or "",
        "text_api_key": body.text_api_key or "",
        "text_model": body.text_model or "",
        "provider": body.provider or "",
        "expected_language": body.expected_language or "",
        "character_id": body.character_id or 0,
        "tools": body.tools or [],
        "temperature": body.temperature,
    }
    if body.max_tokens:
        state["max_tokens"] = body.max_tokens
    final = await graph.ainvoke(state, config={"recursion_limit": 10})
    if final.get("error"):
        return ProactiveTurnResponse(error=final["error"])
    return ProactiveTurnResponse(assistant_raw=final.get("assistant_raw"))
