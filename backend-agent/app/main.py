"""backend-agent 入口：FastAPI 应用。

阶段 1 范围：
- POST /agent/desktop-observe  桌面感知（第一条迁移链路）
- GET  /agent/health           存活探针（compose healthcheck 用）

契约原则：与 Java 侧 ObserveController 的响应形状逐字段兼容，
由 Java 适配层（阶段 1 后续）做 vault 解析后转发，或 nginx 直接分流。
"""

import logging

from typing import Any
import asyncio
from contextlib import asynccontextmanager, suppress
from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, Field

from app.config import get_settings
from app.desktop_observe import DesktopObserveRequest, observe_desktop
from app.gateway import ModelGateway, decode_png_base64, get_gateway
from app.stream_chat import DONE_FRAME, sse_error, stream_chat_events
from app.system_prompt import assemble_scene_prompt
from app.image_chat import chat_with_image
from app.text_turn import complete_text
from app.tools import supported_tool_schemas

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
logger = logging.getLogger("lianyu-agent")

@asynccontextmanager
async def lifespan(app: FastAPI):
    """启动 outbox relay 后台循环（Python 侧单写者）。"""
    task = None
    settings = get_settings()
    if settings.relay_enabled:
        task = asyncio.create_task(_relay_loop())
    yield
    if task is not None:
        task.cancel()
        with suppress(asyncio.CancelledError):
            await task
    # 优雅关闭：连接池与 Redis 不关会在长跑/热重启时留下句柄
    # （日志里出现过 aiomysql Connection.__del__ 的未关闭警告）
    from app.db import close as close_db

    try:
        await close_db()
    except Exception as exc:  # noqa: BLE001 - 关闭失败不应阻止进程退出
        logger.warning("shutdown: close db resources failed: %s", exc)


async def _relay_loop() -> None:
    from app.memory.relay import drain_once

    settings = get_settings()
    while True:
        try:
            stats = await drain_once()
            if stats["picked"]:
                logger.info("memory relay: %s", stats)
        except Exception as exc:  # noqa: BLE001 - 后台循环不能因单次失败退出
            logger.warning("memory relay cycle failed: %s", exc)
        await asyncio.sleep(max(1.0, settings.relay_interval_seconds))


app = FastAPI(title="YuNian Agent Runtime", version="0.1.0", lifespan=lifespan)

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


# ─────────────────────────── P4：单聊流式（SSE） ───────────────────────────


class ChatStreamBody(BaseModel):
    """单聊流式请求：Java 已组装好 system 与历史，Python 只做模型调用 + 语言门。"""

    user_id: int = Field(alias="userId")
    character_id: int | None = Field(default=None, alias="characterId")
    system: str = ""
    messages: list[dict[str, Any]] = []
    text_base_url: str | None = Field(default=None, alias="textBaseUrl")
    text_api_key: str | None = Field(default=None, alias="textApiKey")
    text_model: str | None = Field(default=None, alias="textModel")
    provider: str | None = None
    expected_language: str | None = Field(default=None, alias="expectedLanguage")
    temperature: float = 0.8
    max_tokens: int = 1024
    tools: list[str] = []

    model_config = {"populate_by_name": True}


@app.post("/agent/chat-stream")
async def chat_stream(
    body: ChatStreamBody,
    user_id_header: str | None = Header(default=None, alias="X-User-Id"),
) -> StreamingResponse:
    """SSE 流式回复，帧格式与 Java `SseChatStreamHelper` 逐字节对齐。"""
    if user_id_header and str(body.user_id) != user_id_header:
        raise HTTPException(status_code=403, detail="user mismatch")

    async def event_source():
        yield ": connected\n\n"
        if not body.text_base_url or not body.text_api_key:
            yield sse_error("未配置文本模型，请在设置中添加")
            yield DONE_FRAME
            return
        messages: list[dict[str, Any]] = []
        if body.system:
            messages.append({"role": "system", "content": body.system})
        messages.extend(body.messages)
        async for frame in stream_chat_events(
            get_gateway(),
            base_url=body.text_base_url,
            api_key=body.text_api_key,
            model=body.text_model or "default",
            messages=messages,
            temperature=body.temperature,
            max_tokens=body.max_tokens,
            provider=body.provider,
            expected_language=body.expected_language,
            tools=supported_tool_schemas(body.tools) if body.tools else None,
        ):
            yield frame

    return StreamingResponse(
        event_source(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


# ─────────────────────────── P5.5：system prompt 组装 ───────────────────────────


class PromptAssembleBody(BaseModel):
    """Java 把已取好的数据块传进来，Python 只做组装（数据取数仍在 Java）。"""

    user_id: int = Field(alias="userId")
    character_name: str = Field(default="", alias="characterName")
    settings: dict[str, Any] = {}
    prompt_template: str | None = Field(default=None, alias="promptTemplate")
    memory_context: str = Field(default="", alias="memoryContext")
    tool_hint: str = Field(default="", alias="toolHint")
    emotion_block: str = Field(default="", alias="emotionBlock")
    output_language: str | None = Field(default="zh", alias="outputLanguage")
    enable_chat_tools: bool = Field(default=False, alias="enableChatTools")
    show_inner_thoughts: bool = Field(default=True, alias="showInnerThoughts")
    max_pieces: int = Field(default=2, alias="maxPieces")
    include_time: bool = Field(default=True, alias="includeTime")
    time_fact: str | None = Field(default=None, alias="timeFact")
    city: str | None = None
    after_base: str = Field(default="full", alias="afterBase")
    # 为 true 时忽略传入的数据块，由 Python 自己读库（P5.6）
    use_internal_data: bool = Field(default=False, alias="useInternalData")
    character_id: int | None = Field(default=None, alias="characterId")
    conversation_id: int | None = Field(default=None, alias="conversationId")
    # 场景名（SINGLE/GROUP/MOMENTS/DIARY/PROACTIVE/VOICE_CALL）：决定哪些块参与组装
    scene: str | None = None

    model_config = {"populate_by_name": True}


@app.post("/agent/prompt/assemble")
async def prompt_assemble(
    body: PromptAssembleBody,
    user_id_header: str | None = Header(default=None, alias="X-User-Id"),
) -> dict[str, Any]:
    """返回 {systemPrompt}。SHADOW 模式下 Java 用它与自己组装的结果比对。"""
    if user_id_header and str(body.user_id) != user_id_header:
        raise HTTPException(status_code=403, detail="user mismatch")
    try:
        memory_context = body.memory_context
        emotion_block = body.emotion_block
        city = body.city
        if body.use_internal_data:
            from app.data_readers import load_emotion, load_memory_context
            from app.emotion_block import format_emotion_block

            scene = (body.scene or "SINGLE").strip().upper()
            memory_context = await load_memory_context(
                body.user_id, body.character_id or 0, body.conversation_id,
                include_memory=scene != "VOICE_CALL",
                include_relationship=scene in {"SINGLE", "PROACTIVE", "MOMENTS", "DIARY"},
                include_session=scene in {"SINGLE", "PROACTIVE"},
            ) or ""
            emotion = await load_emotion(body.character_id or 0, body.user_id)
            emotion_block = format_emotion_block(
                lang=body.output_language,
                current_emotion=(emotion or {}).get("current_emotion"),
                emotion_intensity=(emotion or {}).get("emotion_intensity"),
                status_text=(emotion or {}).get("status_text"),
                previous_emotion=(emotion or {}).get("previous_emotion"),
            ) if emotion else ""
            # 城市：与 Java 同一判断（city_mode == real 且城市非空）
            if not city and str(body.settings.get("city_mode") or "") == "real":
                raw_city = body.settings.get("city")
                city = str(raw_city).strip() or None

        prompt = assemble_scene_prompt(
            character_name=body.character_name,
            settings=body.settings,
            prompt_template=body.prompt_template,
            memory_context=memory_context,
            tool_hint=body.tool_hint,
            emotion_block=emotion_block,
            output_language=body.output_language,
            enable_chat_tools=body.enable_chat_tools,
            show_inner_thoughts=body.show_inner_thoughts,
            max_pieces=body.max_pieces,
            include_time=body.include_time,
            time_fact=body.time_fact,
            city=city,
            after_base=body.after_base,
        )
    except Exception as exc:  # noqa: BLE001 - 组装失败返回错误码，Java 记录后忽略
        logger.warning("prompt assemble failed: %s", exc)
        return {"error": "assemble_failed"}
    return {"systemPrompt": prompt}


# ─────────────────────────── 图片消息回合（两段式） ───────────────────────────


class ChatImageBody(BaseModel):
    """图片消息：Java 已从对象存储读出字节并以 base64 传来（不引入 URL 下载面）。"""

    user_id: int = Field(alias="userId")
    character_id: int | None = Field(default=None, alias="characterId")
    image_png_base64: str = Field(alias="imagePngBase64")
    system: str = ""
    messages: list[dict[str, Any]] = []
    text_base_url: str | None = Field(default=None, alias="textBaseUrl")
    text_api_key: str | None = Field(default=None, alias="textApiKey")
    text_model: str | None = Field(default=None, alias="textModel")
    vision_base_url: str | None = Field(default=None, alias="visionBaseUrl")
    vision_api_key: str | None = Field(default=None, alias="visionApiKey")
    vision_model: str | None = Field(default=None, alias="visionModel")
    provider: str | None = None
    temperature: float = 0.8
    max_tokens: int = 1024

    model_config = {"populate_by_name": True}


@app.post("/agent/chat-image")
async def chat_image(
    body: ChatImageBody,
    user_id_header: str | None = Header(default=None, alias="X-User-Id"),
) -> dict[str, Any]:
    """识图 + 生成（阻塞），返回 {content, imageDescription}；失败返回 error 码。"""
    if user_id_header and str(body.user_id) != user_id_header:
        raise HTTPException(status_code=403, detail="user mismatch")
    settings = get_settings()
    if not body.text_base_url or not body.text_api_key:
        return {"error": "no_text_credentials"}
    vision_base = body.vision_base_url or settings.multimodal_base_url
    vision_key = body.vision_api_key or settings.multimodal_api_key
    vision_model = body.vision_model or settings.multimodal_model
    if not vision_base or not vision_key:
        return {"error": "no_vision_credentials"}

    return await chat_with_image(
        get_gateway(),
        image_png_base64=body.image_png_base64,
        vision_base_url=vision_base,
        vision_api_key=vision_key,
        vision_model=vision_model,
        base_url=body.text_base_url,
        api_key=body.text_api_key,
        model=body.text_model or "default",
        system=body.system,
        messages=body.messages,
        temperature=body.temperature,
        max_tokens=body.max_tokens,
        provider=body.provider,
    )


# ─────────────────────────── 通用阻塞文本回合 ───────────────────────────


class TextCompleteBody(BaseModel):
    """Java 已组装好 system + user 文本，只有一次模型调用需要执行。"""

    user_id: int = Field(alias="userId")
    system: str = ""
    user_text: str = Field(alias="userText")
    # 可选历史（朋友圈发帖/评论需要），按原序插在 system 与 userText 之间
    history: list[dict[str, Any]] = []
    text_base_url: str | None = Field(default=None, alias="textBaseUrl")
    text_api_key: str | None = Field(default=None, alias="textApiKey")
    text_model: str | None = Field(default=None, alias="textModel")
    provider: str | None = None
    temperature: float = 0.85
    max_tokens: int | None = Field(default=None, alias="maxTokens")

    model_config = {"populate_by_name": True}


@app.post("/agent/text/complete")
async def text_complete(
    body: TextCompleteBody,
    user_id_header: str | None = Header(default=None, alias="X-User-Id"),
) -> dict[str, Any]:
    if user_id_header and str(body.user_id) != user_id_header:
        raise HTTPException(status_code=403, detail="user mismatch")
    if not body.text_base_url or not body.text_api_key:
        return {"error": "no_text_credentials"}
    return await complete_text(
        get_gateway(),
        base_url=body.text_base_url,
        api_key=body.text_api_key,
        model=body.text_model or "default",
        system=body.system,
        user_text=body.user_text,
        history=body.history or None,
        temperature=body.temperature,
        max_tokens=body.max_tokens,
        provider=body.provider,
    )


# ─────────────────────────── P5：记忆写入（Python 侧接管） ───────────────────────────


class MemoryItem(BaseModel):
    summary: str
    memory_type: str = Field(default="FACT", alias="memoryType")
    importance: float = 0.5
    source_msg_ids: list[int] = Field(default_factory=list, alias="sourceMsgIds")

    model_config = {"populate_by_name": True}


class MemoryWriteBody(BaseModel):
    user_id: int = Field(alias="userId")
    character_id: int = Field(alias="characterId")
    memories: list[MemoryItem] = []

    model_config = {"populate_by_name": True}


class MemoryExtractBody(BaseModel):
    """抽取 + 写入一步到位：Java 只需把最近消息与凭据递过来。"""

    user_id: int = Field(alias="userId")
    character_id: int = Field(alias="characterId")
    messages: list[dict[str, Any]] = []
    text_base_url: str | None = Field(default=None, alias="textBaseUrl")
    text_api_key: str | None = Field(default=None, alias="textApiKey")
    text_model: str | None = Field(default=None, alias="textModel")
    provider: str | None = None

    model_config = {"populate_by_name": True}


@app.post("/agent/memory/extract-write")
async def memory_extract_write(
    body: MemoryExtractBody,
    user_id_header: str | None = Header(default=None, alias="X-User-Id"),
) -> dict[str, Any]:
    """规则 + LLM 抽取，过闸后直接写入（去重/合并/outbox 入队），整批单事务。"""
    if user_id_header and str(body.user_id) != user_id_header:
        raise HTTPException(status_code=403, detail="user mismatch")

    from app.memory.extract import extract_all
    from app.memory.writer import MemoryInput, write_memories

    try:
        extracted = await extract_all(
            get_gateway(),
            messages=body.messages,
            base_url=body.text_base_url,
            api_key=body.text_api_key,
            model=body.text_model,
            provider=body.provider,
        )
    except Exception as exc:  # noqa: BLE001 - 抽取失败降级为不写
        logger.warning("memory extract failed: %s", exc)
        return {"created": 0, "updated": 0, "skipped": 0, "ids": [], "error": "extract_failed"}

    if not extracted:
        return {"created": 0, "updated": 0, "skipped": 0, "ids": [], "extracted": 0}

    inputs = [
        MemoryInput(
            summary=m.summary,
            memory_type=m.memory_type,
            importance=m.importance,
            source_msg_ids=(m.source_msg_id,) if m.source_msg_id else (),
        )
        for m in extracted
    ]
    try:
        outcome = await write_memories(body.user_id, body.character_id, inputs)
    except Exception as exc:  # noqa: BLE001
        logger.warning("memory write failed: %s", exc)
        raise HTTPException(status_code=500, detail="memory write failed") from exc
    result = outcome.as_dict()
    result["extracted"] = len(extracted)
    return result


@app.post("/agent/memory/relay/drain")
async def memory_relay_drain() -> dict[str, int]:
    """手动触发一次 outbox 投影（运维/排障用）。"""
    from app.memory.relay import drain_once

    return await drain_once()


@app.post("/agent/memory/relay/requeue")
async def memory_relay_requeue() -> dict[str, int]:
    from app.memory.relay import requeue_stuck

    return {"requeued": await requeue_stuck()}


@app.post("/agent/memory/reconcile")
async def memory_reconcile(limit: int = 500) -> dict[str, int]:
    """全量对账：修复 vec_id 缺失的记忆（等价 Java startup resync）。"""
    from app.memory.relay import reconcile

    return await reconcile(limit=limit)


@app.post("/agent/memory/write")
async def memory_write(
    body: MemoryWriteBody,
    user_id_header: str | None = Header(default=None, alias="X-User-Id"),
) -> dict[str, Any]:
    """写入抽取好的记忆（去重 + 合并 + outbox 入队），整批单事务。"""
    if user_id_header and str(body.user_id) != user_id_header:
        raise HTTPException(status_code=403, detail="user mismatch")

    from app.memory.writer import MemoryInput, write_memories

    inputs = [
        MemoryInput(
            summary=item.summary,
            memory_type=item.memory_type,
            importance=item.importance,
            source_msg_ids=tuple(item.source_msg_ids or ()),
        )
        for item in body.memories
    ]
    try:
        outcome = await write_memories(body.user_id, body.character_id, inputs)
    except Exception as exc:  # noqa: BLE001 - 写失败要显式返回，便于 Java 回退
        logger.warning("memory write failed: %s", exc)
        raise HTTPException(status_code=500, detail="memory write failed") from exc
    return outcome.as_dict()


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
    # 必须带 alias：FastAPI 默认 response_model_by_alias=True，
    # 无 alias 的字段会以 snake_case 输出，Java 侧读 assistantRaw 会永远拿不到（AGENT 会静默回退）。
    assistant_raw: str | None = Field(default=None, alias="assistantRaw")
    error: str | None = None
    # 用量与执行元信息：Java 侧据此落库 token 统计（AGENT 模式不能丢这份数据）
    prompt_tokens: int | None = Field(default=None, alias="promptTokens")
    completion_tokens: int | None = Field(default=None, alias="completionTokens")
    total_tokens: int | None = Field(default=None, alias="totalTokens")
    language_retries: int | None = Field(default=None, alias="languageRetries")
    tool_rounds: int | None = Field(default=None, alias="toolRounds")
    tool_truncated: bool | None = Field(default=None, alias="toolTruncated")

    model_config = {"populate_by_name": True}


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
    prompt_tokens = final.get("prompt_tokens")
    completion_tokens = final.get("completion_tokens")
    total = None
    if prompt_tokens is not None or completion_tokens is not None:
        total = (prompt_tokens or 0) + (completion_tokens or 0)
    return ProactiveTurnResponse(
        assistant_raw=final.get("assistant_raw"),
        prompt_tokens=prompt_tokens,
        completion_tokens=completion_tokens,
        total_tokens=total,
        language_retries=final.get("language_retries"),
        tool_rounds=final.get("tool_rounds"),
        tool_truncated=final.get("tool_truncated"),
    )
