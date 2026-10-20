"""主聊天回合的 LangGraph 编排（编排的**唯一**实现）。

## 目标形态

    Java  →  转发原始输入 + 解密后的模型凭据
    Python (LangGraph)  →  组装 prompt → 组装 messages → 调模型（流式/阻塞）
    Java  →  落库、SSE 回写、token 记账

也就是说 Java 不再持有任何 prompt 文本、消息拼装规则或模型调用；
本模块之前，这条链路由 Java 的 Spring AI Alibaba Graph 编排
（`ChatTurnGraphConfig`：assemblePrompt → assembleMessages → invokeModel）。

## 图拓扑

    assemble_prompt → assemble_messages → invoke_model | invoke_model_stream

比对/回归用的 `invoke=False` 会在 assemble_messages 之后结束（条件边），
不产生任何模型调用开销。

## 与 Java 逐条对齐的细节（都有测试锁定，勿"简化"）

1. `ChatTurnMessageAssembler` 的 preparedMessages 分支（群聊主动发言用）：
   直接采用调用方给的消息列表，只把第 0 条 system 换成组装好的 prompt。
2. 图片消息的历史占位归一（`ImageMessageHistoryText.forHistory`）
3. 当前用户消息的 XML 包裹（`UserInputSanitizer.wrapStoredTextForModel`，属提示注入防护）
4. 语音通话 turn 消息过滤（`audioUrl == "system/voice-call-turn"`）
5. 历史取文本的优先级：`contextContent`（非空白，trim 后）优先于 `content`
"""

from __future__ import annotations

import logging
from typing import Any, TypedDict

from langgraph.graph import END, START, StateGraph

from app.gateway import get_gateway
from app.message_assembly import (
    image_history_text,
    is_empty_user_message_xml,
    is_voice_call_turn,
    wrap_stored_text_for_model,
)
from app.prompt_pipeline import assemble_turn_prompt, normalize_scene, scene_enable_tools

logger = logging.getLogger(__name__)

GENERIC_IMAGE_USER_TEXT = "用户发送了一张图片"

# 单聊流式回合可挂的只读工具（原先硬编码在 Java `AgentStreamAdapter.CHAT_TOOLS`）。
# 工具集本身就是「Python 侧能力」，放在这里而不是让 Java 传名字：
# Java 只需给 `enableChatTools` 这个事实。
CHAT_TOOL_NAMES = (
    "get_current_time",
    "get_weather",
    "memory_search",
    "get_my_recent_life",
)


class ChatTurnState(TypedDict, total=False):
    """回合状态：输入（请求参数）→ 中间产物 → 输出。"""

    # ── 输入：角色与会话事实（Java 取好，Python 不重复查） ──
    user_id: int
    conversation_id: int | None
    character_id: int | None
    character_name: str
    settings: dict[str, Any]
    prompt_template: str | None
    scene: str
    output_language: str

    # ── 输入：本轮文本 ──
    user_text: str
    """喂模型的用户文本（对齐 Java `modelUserText`，可含识图描述）。"""
    raw_user_text: str
    """用户原文（记忆检索与语言判定用，对齐 Java `rawUserText`）。"""
    history: list[dict[str, Any]]
    prepared_messages: list[dict[str, Any]]
    current_user_message_id: int | None

    # ── 输入：场景开关与显式数据块 ──
    time_fact: str | None
    tool_flags: dict[str, Any] | None
    enable_chat_tools: bool | None
    show_inner_thoughts: bool | None
    max_pieces: int | None
    memory_context: str
    emotion_block: str
    session_summary: str
    city: str | None
    extra_system_suffix: str
    group_max_pieces: int
    group_other_characters: str
    group_mention: str
    prefetch_real_world: bool

    # ── 输入：模型与凭据 ──
    invoke: bool
    provider: str | None
    base_url: str
    api_key: str
    model: str
    temperature: float
    max_tokens: int
    expected_language: str | None

    # ── 中间 ──
    system_prompt: str
    messages: list[dict[str, str]]

    # ── 输出 ──
    content: str
    prompt_tokens: int | None
    completion_tokens: int | None


async def assemble_prompt(state: ChatTurnState) -> dict[str, Any]:
    """节点 1：组装 system prompt（记忆/关系/情绪/摘要由 Python 自读）。"""
    scene = normalize_scene(state.get("scene"))
    system_prompt = await assemble_turn_prompt(
        user_id=state["user_id"],
        character_id=state.get("character_id"),
        conversation_id=state.get("conversation_id"),
        character_name=state.get("character_name") or "",
        settings=state.get("settings") or {},
        prompt_template=state.get("prompt_template"),
        scene=scene,
        output_language=state.get("output_language"),
        memory_context=state.get("memory_context") or "",
        emotion_block=state.get("emotion_block") or "",
        tool_flags=state.get("tool_flags"),
        city=state.get("city"),
        enable_chat_tools=state.get("enable_chat_tools"),
        show_inner_thoughts=state.get("show_inner_thoughts"),
        max_pieces=state.get("max_pieces"),
        time_fact=state.get("time_fact"),
        last_user_message=state.get("raw_user_text") or state.get("user_text"),
        session_summary=state.get("session_summary"),
        group_max_pieces=state.get("group_max_pieces") or 0,
        group_other_characters=state.get("group_other_characters") or "",
        group_mention=state.get("group_mention") or "",
        extra_system_suffix=state.get("extra_system_suffix") or "",
        prefetch_real_world=state.get("prefetch_real_world", True),
        use_internal_data=True,
    )
    logger.info("chat_turn.assemble_prompt scene=%s chars=%d", scene, len(system_prompt))
    return {"system_prompt": system_prompt}


def _stored_text(item: dict[str, Any]) -> str:
    """对齐 `MessageModelContent.forModel`：contextContent（trim）优先，其次 content。"""
    context = item.get("contextContent") or item.get("context_content")
    if context is not None and str(context).strip():
        return str(context).strip()
    content = item.get("content")
    return "" if content is None else str(content)


def _assemble_prepared(system_prompt: str,
                       prepared: list[dict[str, Any]]) -> list[dict[str, str]]:
    """群聊主动发言路径：调用方已给完整消息列表（对齐 assembler 的 prepared 分支）。"""
    out: list[dict[str, str]] = []
    for dto in prepared:
        has_image = bool(dto.get("imageUrl") or dto.get("image_url"))
        out.append({
            "role": dto.get("role"),
            "content": image_history_text(dto.get("content"), has_image),
        })
    if out and str(out[0].get("role") or "").lower() == "system":
        out[0]["content"] = system_prompt
    else:
        out.insert(0, {"role": "system", "content": system_prompt})
    return out


async def assemble_messages(state: ChatTurnState) -> dict[str, Any]:
    """节点 2：system + 历史 → 模型输入，对齐 Java `ChatTurnMessageAssembler`。"""
    system_prompt = state.get("system_prompt") or ""

    prepared = state.get("prepared_messages") or []
    if prepared:
        messages = _assemble_prepared(system_prompt, prepared)
        logger.info("chat_turn.assemble_messages count=%d prepared=true", len(messages))
        return {"messages": messages}

    messages: list[dict[str, str]] = [{"role": "system", "content": system_prompt}]
    current_user_id = state.get("current_user_message_id")
    user_text = state.get("user_text")

    for item in state.get("history") or []:
        if is_voice_call_turn(item):
            continue
        has_image = bool(item.get("imageUrl") or item.get("image_url"))
        content = _stored_text(item)
        is_current = (current_user_id is not None
                      and str(item.get("id")) == str(current_user_id))

        if is_current and user_text and user_text.strip():
            wrapped = (user_text if "<user_message" in user_text
                       else wrap_stored_text_for_model(user_text))
            if has_image and is_empty_user_message_xml(wrapped):
                wrapped = wrap_stored_text_for_model(GENERIC_IMAGE_USER_TEXT)
            content = wrapped
        elif has_image:
            # 优先 contextContent（识图描述），再归一成历史占位；勿只用可见 content 丢掉描述
            content = image_history_text(content, True)
        elif not content.strip():
            continue

        role = str(item.get("role") or "user").lower()
        messages.append({"role": role, "content": content})

    # 调用方没给 currentUserMessageId（独立调用/回归用）时补一条用户消息；
    # 真实链路 history 里已有该条并被上面的 is_current 分支命中，不会重复添加。
    if current_user_id is None and user_text and user_text.strip():
        messages.append({"role": "user", "content": wrap_stored_text_for_model(user_text)})

    logger.info("chat_turn.assemble_messages count=%d prepared=false", len(messages))
    return {"messages": messages}


async def invoke_model(state: ChatTurnState) -> dict[str, Any]:
    """节点 3：调用模型（阻塞版；群聊主动发言、关系事件等非流式场景用）。"""
    gateway = get_gateway()
    result = await gateway.chat_messages(
        base_url=state.get("base_url") or "",
        api_key=state.get("api_key") or "",
        model=state.get("model") or "",
        messages=state.get("messages") or [],
        temperature=state.get("temperature", 0.8),
        max_tokens=state.get("max_tokens", 1024),
        provider=state.get("provider"),
    )
    logger.info("chat_turn.invoke_model chars=%d", len(result.content or ""))
    return {
        "content": result.content or "",
        "prompt_tokens": result.prompt_tokens,
        "completion_tokens": result.completion_tokens,
    }


def route_after_messages(state: ChatTurnState) -> str:
    """条件边：只做组装时跳过模型调用（`invoke=False`）。"""
    return "invoke_model" if state.get("invoke") else END


def build_chat_turn_graph():
    """3 节点图，对齐 Java `ChatTurnGraphConfig` 的拓扑。"""
    graph = StateGraph(ChatTurnState)
    graph.add_node("assemble_prompt", assemble_prompt)
    graph.add_node("assemble_messages", assemble_messages)
    graph.add_node("invoke_model", invoke_model)
    graph.add_edge(START, "assemble_prompt")
    graph.add_edge("assemble_prompt", "assemble_messages")
    graph.add_conditional_edges("assemble_messages", route_after_messages,
                                {"invoke_model": "invoke_model", END: END})
    graph.add_edge("invoke_model", END)
    return graph.compile()


async def run_chat_turn(payload: dict[str, Any]) -> ChatTurnState:
    """跑一轮，返回完整状态（systemPrompt / messages / content 供对照）。"""
    graph = build_chat_turn_graph()
    return await graph.ainvoke(payload)  # type: ignore[return-value]


async def invoke_model_stream(state: ChatTurnState) -> dict[str, Any]:
    """节点 3（流式版）：把模型增量逐帧写出，供 `/agent/chat-turn/stream` 转发。

    用 LangGraph 的 `get_stream_writer()` 透出到 `astream(stream_mode="custom")`；
    帧格式复用 `stream_chat_events`，与既有 `/agent/chat-stream` 一致，
    Java 侧解析器无需改动。
    """
    from langgraph.config import get_stream_writer

    from app.stream_chat import stream_chat_events
    from app.tools import supported_tool_schemas

    enable_tools = state.get("enable_chat_tools")
    if enable_tools is None:
        enable_tools = scene_enable_tools(normalize_scene(state.get("scene")))
    tools = supported_tool_schemas(list(CHAT_TOOL_NAMES)) if enable_tools else None

    writer = get_stream_writer()
    async for frame in stream_chat_events(
        get_gateway(),
        base_url=state.get("base_url") or "",
        api_key=state.get("api_key") or "",
        model=state.get("model") or "",
        messages=state.get("messages") or [],
        temperature=state.get("temperature", 0.8),
        max_tokens=state.get("max_tokens", 1024),
        provider=state.get("provider"),
        expected_language=state.get("expected_language"),
        tools=tools,
        # 工具回退：流式无文本（模型选择调工具）时退化成有界工具循环
        tool_names=list(CHAT_TOOL_NAMES) if enable_tools else None,
        tool_context={
            "_user_id": state.get("user_id"),
            "_character_id": state.get("character_id"),
            "_language": state.get("expected_language") or state.get("output_language") or "zh",
        },
    ):
        writer({"frame": frame})
    return {}


def build_chat_turn_stream_graph():
    """流式图：前两个节点与阻塞版相同，第三个节点逐帧透出。"""
    graph = StateGraph(ChatTurnState)
    graph.add_node("assemble_prompt", assemble_prompt)
    graph.add_node("assemble_messages", assemble_messages)
    graph.add_node("invoke_model_stream", invoke_model_stream)
    graph.add_edge(START, "assemble_prompt")
    graph.add_edge("assemble_prompt", "assemble_messages")
    graph.add_edge("assemble_messages", "invoke_model_stream")
    graph.add_edge("invoke_model_stream", END)
    return graph.compile()


async def run_chat_turn_stream(payload: dict[str, Any]):
    """跑流式图，逐帧产出 SSE 文本（`data: {...}` 与 `data: [DONE]`）。"""
    graph = build_chat_turn_stream_graph()
    async for chunk in graph.astream(payload, stream_mode="custom"):
        frame = (chunk or {}).get("frame")
        if frame:
            yield frame


__all__ = [
    "ChatTurnState",
    "assemble_messages",
    "assemble_prompt",
    "build_chat_turn_graph",
    "build_chat_turn_stream_graph",
    "route_after_messages",
    "run_chat_turn",
    "run_chat_turn_stream",
]
