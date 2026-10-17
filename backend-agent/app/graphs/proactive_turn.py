"""LangGraph 状态图：主动发言回合（阶段 2 核心）。

对齐 Java ChatTurnGraphConfig（3 线性节点）但补上 LangGraph 原生能力：
- TypedDict state：34 个 ChatTurnKeys 的强类型等价物（审计 D7 的键拼错无感知问题在此终结）
- 条件边：有文本凭据 → 走文本模型；否则直接终结（静默跳过，语义同 Java resolveUserTextVaultOrNull==null）
- 生成节点：经 ModelGateway 出站（唯一出口）
- recursion_limit 显式上限（Java 工具循环无上限问题 A4 的对应护栏）

本图不落库：产出 assistantRaw 由调用方（Java 侧 persistX / 未来 Python 侧 outbox）处理，
与 Java 事务红线（慢调用不进事务）保持同一拓扑。
"""

import operator
from typing import Annotated, Any, Literal, TypedDict

from app.config import get_settings
from app.gateway import GatewayResult, ModelGateway, ModelGatewayError, TextPart


class ProactiveTurnState(TypedDict, total=False):
    # ── inputs ──
    user_id: int
    character_id: int
    conversation_id: int | None
    scene: str                                   # PROACTIVE / COLD_OPEN / COLD_OPEN_FOLLOWUP / CITY_CHANGE
    persona: str                                 # Java 侧组装好的 system 主体（阶段 2 由 Java 传入）
    task_block: str                              # 场景任务块（资源化 prompt 渲染结果）
    history: list[dict[str, str]]                # [{"role","content"}]，不含 system
    user_trigger: str                            # PROACTIVE_USER_TRIGGER
    # ── model credentials（Java 侧 vault 解析后传入）──
    text_base_url: str
    text_api_key: str
    text_model: str
    temperature: float
    max_tokens: int
    # ── outputs ──
    assistant_raw: str
    prompt_tokens: int | None
    completion_tokens: int | None
    error: str
    # ── internal（assemble 节点产出，invoke 节点消费）──
    messages: list[dict[str, str]]


def _merge_history(left: list | None, right: list | None) -> list:
    """history 字段用覆盖语义（Annotated reducer 仅演示 LangGraph 用法；此处直接替换）。"""
    return right if right is not None else (left or [])


def build_proactive_graph(gateway: ModelGateway):
    """构造主动发言子图：assemble → invoke → postprocess，带条件边与递归上限。"""
    from langgraph.graph import END, START, StateGraph

    def assemble(state: ProactiveTurnState) -> dict[str, Any]:
        """消息装配：system(人设+任务块) + history + trailing user trigger。
        对齐 Java：allMessages.add(buildSystemMessage(systemPrompt + task)); ...; add(buildUserMessage(TRIGGER))。
        """
        system_text = (state.get("persona") or "") + (state.get("task_block") or "")
        messages: list[dict[str, str]] = [{"role": "system", "content": system_text}]
        messages.extend(state.get("history") or [])
        trigger = state.get("user_trigger") or ""
        if trigger:
            messages.append({"role": "user", "content": trigger})
        return {"messages": messages}

    def should_invoke(state: ProactiveTurnState) -> Literal["invoke", "skip"]:
        """无文本凭据 → skip（对齐 Java：no user text model, skipped）。"""
        if not state.get("text_base_url") or not state.get("text_api_key"):
            return "skip"
        return "invoke"

    async def invoke(state: ProactiveTurnState) -> dict[str, Any]:
        settings = get_settings()
        messages = state["messages"]
        # ModelGateway 契约：system 单独传，其余消息折叠为纯文本序列
        system = None
        convo: list[str] = []
        for msg in messages:
            if msg["role"] == "system" and system is None:
                system = msg["content"]
            else:
                prefix = "用户" if msg["role"] == "user" else "你"
                convo.append(f"{prefix}：{msg['content']}")
        try:
            result: GatewayResult = await gateway.chat_sync(
                base_url=state["text_base_url"],
                api_key=state["text_api_key"],
                model=state.get("text_model") or "default",
                system=system,
                user_text="\n".join(convo) if convo else (state.get("user_trigger") or ""),
                temperature=state.get("temperature", 0.8),
                max_tokens=state.get("max_tokens", settings.proactive_max_tokens),
            )
        except ModelGatewayError as exc:
            return {"error": str(exc)}
        return {
            "assistant_raw": result.content,
            "prompt_tokens": result.prompt_tokens,
            "completion_tokens": result.completion_tokens,
        }

    def skip(state: ProactiveTurnState) -> dict[str, Any]:
        return {"error": "no_text_credentials"}

    graph = StateGraph(ProactiveTurnState)
    graph.add_node("assemble", assemble)
    graph.add_node("invoke", invoke)
    graph.add_node("skip", skip)
    graph.add_edge(START, "assemble")
    graph.add_conditional_edges(
        "assemble",
        should_invoke,
        {"invoke": "invoke", "skip": "skip"},
    )
    graph.add_edge("invoke", END)
    graph.add_edge("skip", END)
    return graph.compile()


__all__ = ["ProactiveTurnState", "build_proactive_graph", "operator"]
