"""主聊天回合的 LangGraph 编排（迁移第一阶段：对照端点，不切换流量）。

## 为什么要有这个模块

重构的目标形态是「编排在 Python 的 LangGraph」，但截至迁移前，
主聊天链路仍由 Java 的 Spring AI Alibaba Graph 编排
（`ChatTurnGraphConfig`：assemblePrompt → assembleMessages → invokeModel），
LangGraph 只在 `proactive_turn.py` 用了一条。

本模块把**同一条 3 节点链路**用 LangGraph 建出来，先以「对照端点」的形式并行存在：

    assemble_prompt → assemble_messages → invoke_model

Java 侧暂不改动，比对通过后再把 `ChatTurnFacade` 切成「只转发 + 落库」，
并删除 Java 的 graph/ 包。

## 当前阶段的已知缺口（第二阶段补齐）

`ChatTurnMessageAssembler`（Java, 95 行）里有几处细节尚未迁：

1. 图片消息的历史占位归一（`ImageMessageHistoryText.forHistory`）
2. 当前用户消息的 XML 包裹（`UserInputSanitizer.wrapStoredTextForModel`，属于提示注入防护）
3. 语音通话 turn 消息过滤（`audioUrl == "system/voice-call-turn"`）

这些都会改变喂给模型的内容，必须逐条对照迁移，不能凭印象实现。
"""

from __future__ import annotations

import logging
from typing import Any, TypedDict

from langgraph.graph import END, START, StateGraph

from app.data_readers import load_character_owner, load_emotion, load_memory_context
from app.message_assembly import (
    image_history_text,
    is_empty_user_message_xml,
    is_voice_call_turn,
    wrap_stored_text_for_model,
)
from app.gateway import get_gateway
from app.system_prompt import assemble_scene_prompt

logger = logging.getLogger(__name__)


class ChatTurnState(TypedDict, total=False):
    """回合状态：输入（请求参数）→ 中间产物 → 输出。"""

    # ── 输入 ──
    user_id: int
    conversation_id: int | None
    character_id: int | None
    character_name: str
    settings: dict[str, Any]
    prompt_template: str | None
    scene: str
    output_language: str
    user_text: str
    time_fact: str | None
    invoke: bool
    tool_flags: dict[str, Any] | None
    enable_chat_tools: bool | None
    show_inner_thoughts: bool | None
    max_pieces: int | None
    current_user_message_id: int | None
    history: list[dict[str, Any]]
    provider: str | None
    base_url: str
    api_key: str
    model: str
    temperature: float
    max_tokens: int

    # ── 中间 ──
    system_prompt: str
    messages: list[dict[str, str]]

    # ── 输出 ──
    content: str


def _after_base(scene: str) -> str:
    """场景 → 场景块策略，对齐 Java `ChatTurnScene` 的分支。"""
    if scene in ("MOMENTS", "DIARY"):
        return "style_only"
    if scene == "VOICE_CALL":
        return "time_only"
    return "full"


def _include_flags(scene: str) -> dict[str, bool]:
    """场景 → 记忆/关系/摘要是否参与，对齐 Java 三个 include* 方法。"""
    return {
        "include_memory": scene != "VOICE_CALL",
        "include_relationship": scene in ("SINGLE", "PROACTIVE", "MOMENTS", "DIARY"),
        "include_session": scene in ("SINGLE", "PROACTIVE"),
    }


async def assemble_prompt(state: ChatTurnState) -> dict[str, Any]:
    """节点 1：组装 system prompt（记忆/关系/情绪/摘要由 Python 自读）。"""
    scene = (state.get("scene") or "SINGLE").upper()
    flags = _include_flags(scene)
    memory_context = await load_memory_context(
        state["user_id"],
        state.get("character_id") or 0,
        state.get("conversation_id"),
        **flags,
        last_user_message=state.get("user_text"),
    ) or ""

    # 情绪块：按角色所有者维度读取（与 Java 一致），格式化在 Python
    from app.emotion_block import format_emotion_block

    owner_id = await load_character_owner(state.get("character_id") or 0) or state["user_id"]
    emotion = await load_emotion(state.get("character_id") or 0, owner_id)
    emotion_block = format_emotion_block(
        lang=state.get("output_language") or "zh",
        current_emotion=(emotion or {}).get("current_emotion"),
        emotion_intensity=(emotion or {}).get("emotion_intensity"),
        status_text=(emotion or {}).get("status_text"),
        previous_emotion=(emotion or {}).get("previous_emotion"),
    ) if emotion else ""

    # 工具提示文案例由 Python 查表生成（Java 只传开关事实，与 prompt/assemble 一致）
    from app.tools_hint import build_from_flags

    tool_flags = state.get("tool_flags")
    scene_enables_tools = scene in ("SINGLE", "GROUP", "DIARY", "PROACTIVE")
    enable_tools = (state.get("enable_chat_tools")
                    if state.get("enable_chat_tools") is not None else scene_enables_tools)
    tool_hint = build_from_flags(tool_flags) if (enable_tools and tool_flags) else ""
    show_inner = (state.get("show_inner_thoughts")
                  if state.get("show_inner_thoughts") is not None else scene != "VOICE_CALL")
    max_pieces = state.get("max_pieces") or 2

    # 城市块：与 Java appendCurrentRealCityContext 同一判断（city_mode=real 且城市非空）
    city: str | None = None
    settings = state.get("settings") or {}
    if str(settings.get("city_mode") or "") == "real":
        raw_city = settings.get("city")
        city = str(raw_city).strip() or None if raw_city else None

    system_prompt = assemble_scene_prompt(
        character_name=state.get("character_name") or "",
        settings=state.get("settings"),
        prompt_template=state.get("prompt_template"),
        memory_context=memory_context,
        tool_hint=tool_hint,
        emotion_block=emotion_block,
        output_language=state.get("output_language") or "zh",
        enable_chat_tools=enable_tools,
        show_inner_thoughts=show_inner,
        max_pieces=max_pieces,
        include_time=scene in ("SINGLE", "PROACTIVE", "VOICE_CALL"),
        time_fact=state.get("time_fact"),
        city=city,
        after_base=_after_base(scene),
    )
    logger.info("chat_turn.assemble_prompt scene=%s chars=%d", scene, len(system_prompt))
    return {"system_prompt": system_prompt}


async def assemble_messages(state: ChatTurnState) -> dict[str, Any]:
    """节点 2：system + 历史 → 模型输入，对齐 Java `ChatTurnMessageAssembler`。

    三处细节全部照 Java 分支实现（XML 包裹属注入防护，不可简化）：
    - 当前用户消息：优先用 `user_text`，并按 `<user_message trusted="false">` 包裹
    - 图片消息：历史占位归一（带识图描述时保留描述）
    - 语音通话 turn：不喂给文字聊模型
    """
    system_prompt = state.get("system_prompt") or ""
    messages: list[dict[str, str]] = [{"role": "system", "content": system_prompt}]

    current_user_id = state.get("current_user_message_id")
    user_text = state.get("user_text")

    for item in state.get("history") or []:
        if is_voice_call_turn(item):
            continue
        has_image = bool(item.get("imageUrl") or item.get("image_url"))
        content = item.get("contextContent") or item.get("content") or ""
        is_current = (current_user_id is not None
                      and item.get("id") == current_user_id)

        if is_current and user_text and user_text.strip():
            wrapped = (user_text if "<user_message" in user_text
                       else wrap_stored_text_for_model(user_text))
            if has_image and is_empty_user_message_xml(wrapped):
                wrapped = wrap_stored_text_for_model("用户发送了一张图片")
            content = wrapped
        elif has_image:
            content = image_history_text(content, True)
        elif not str(content).strip():
            continue

        role = str(item.get("role") or "user").lower()
        messages.append({"role": role, "content": str(content)})

    if user_text and user_text.strip():
        last = messages[-1] if messages else None
        already_present = (last is not None and last.get("role") == "user"
                           and wrap_stored_text_for_model(user_text) in last.get("content", ""))
        if not already_present:
            messages.append({"role": "user", "content": wrap_stored_text_for_model(user_text)})

    logger.info("chat_turn.assemble_messages count=%d", len(messages))
    return {"messages": messages}

async def invoke_model(state: ChatTurnState) -> dict[str, Any]:
    """节点 3：调用模型（当前为阻塞调用，便于与 Java 结果对照）。"""
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
    return {"content": result.content or ""}


def route_after_messages(state: ChatTurnState) -> str:
    """条件边：只做组装时跳过模型调用（对照端点默认走这条）。"""
    return "invoke_model" if state.get("invoke") else END


def build_chat_turn_graph():
    """3 节点图，对齐 Java `ChatTurnGraphConfig` 的拓扑。

    比 Java 版多一条条件边：`invoke=False` 时在 assemble_messages 之后结束，
    用于「只取 prompt + messages」的对照与回归，不产生任何模型调用开销。
    """
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


__all__ = ["ChatTurnState", "build_chat_turn_graph", "run_chat_turn"]
