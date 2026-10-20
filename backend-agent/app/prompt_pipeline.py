"""system prompt 组装的**单一实现**，供 `/agent/prompt/assemble` 与聊天回合图共用。

## 为什么需要这个模块

迁移过程中同一套组装逻辑被写了两遍：

- `main.py:/agent/prompt/assemble`（Java 传数据块，Python 只拼接）
- `chat_turn.py:assemble_prompt` 节点（Python 自己读库再拼接）

两份实现很快就开始漂移，实测已经有一处语义分歧：

    城市块 —— main.py 用「不是虚构城市就带上真实城市」，
              chat_turn.py 用「city_mode == 'real' 才带」。
    而 Java `CharacterCitySettingsService.resolveCityMode` 的判定是
    「显式 fictional 或历史 use_fictional_city 才虚构，其余都算现实」，
    即 settings 里**没有** city_mode 时按现实城市处理。
    → chat_turn.py 那份在「字段缺失」时会漏掉城市块。

所以这里把两者合并为一个函数：场景开关（哪些 section 参与）、
真实数据读取、群聊附加段、主动开口环境块、调用方后缀，全部只有一处实现。

## 场景开关的事实来源

Java `ChatTurnScene` 的方法即事实，这里逐条对应：

    includeTimeCityGoodnight : SINGLE, PROACTIVE        → afterBase=full
    includeTime              : + VOICE_CALL
    includeProactiveRealWorld: PROACTIVE
    enableChatTools          : SINGLE, GROUP, DIARY, PROACTIVE
    includeMemory            : 非 VOICE_CALL
    includeRelationship      : SINGLE, PROACTIVE, MOMENTS, DIARY
    includeSessionSummary    : SINGLE, PROACTIVE
    afterBase 其余            : MOMENTS/DIARY=style_only, VOICE_CALL=time_only, GROUP=none
"""

from __future__ import annotations

import logging
from typing import Any, Mapping

from app.system_prompt import assemble_scene_prompt

logger = logging.getLogger(__name__)

# ── 场景开关（对齐 Java ChatTurnScene） ──

_SCENES_TIME_CITY_GOODNIGHT = frozenset({"SINGLE", "PROACTIVE"})
_SCENES_TIME = _SCENES_TIME_CITY_GOODNIGHT | {"VOICE_CALL"}
_SCENES_TOOLS = frozenset({"SINGLE", "GROUP", "DIARY", "PROACTIVE"})
_SCENES_RELATIONSHIP = frozenset({"SINGLE", "PROACTIVE", "MOMENTS", "DIARY"})
_SCENES_SESSION = frozenset({"SINGLE", "PROACTIVE"})

MODE_REAL = "real"
MODE_FICTIONAL = "fictional"


def normalize_scene(scene: str | None) -> str:
    return (scene or "SINGLE").strip().upper()


def scene_after_base(scene: str) -> str:
    """场景 → 场景块策略（Java `afterBaseOf` 逐条对应）。"""
    if scene in _SCENES_TIME_CITY_GOODNIGHT:
        return "full"
    if scene in ("MOMENTS", "DIARY"):
        return "style_only"
    if scene == "VOICE_CALL":
        return "time_only"
    # GROUP 不属于 full：群聊规则段自己带说话方式，不需要重复场景块
    return "none"


def scene_include_time(scene: str) -> bool:
    return scene in _SCENES_TIME


def scene_enable_tools(scene: str) -> bool:
    return scene in _SCENES_TOOLS


def scene_include_flags(scene: str) -> dict[str, bool]:
    """记忆/关系/会话摘要是否参与（对齐 Java 三个 include* 方法）。"""
    return {
        "include_memory": scene != "VOICE_CALL",
        "include_relationship": scene in _SCENES_RELATIONSHIP,
        "include_session": scene in _SCENES_SESSION,
    }


def resolve_city_mode(settings: Mapping[str, Any] | None) -> str:
    """对齐 Java `CharacterCitySettingsService.resolveCityMode`。

    默认是「现实城市」：只有显式 fictional 或历史 `use_fictional_city` 才算虚构。
    字段缺失时按 real 处理（这是与旧 chat_turn 实现分歧的地方）。
    """
    if not settings:
        return MODE_REAL
    mode = settings.get("city_mode")
    if isinstance(mode, str) and mode.strip().lower() == MODE_FICTIONAL:
        return MODE_FICTIONAL
    legacy = settings.get("use_fictional_city")
    if legacy is True or str(legacy).strip().lower() == "true":
        return MODE_FICTIONAL
    return MODE_REAL


def resolve_city(settings: Mapping[str, Any] | None) -> str | None:
    """现实城市模式下取 settings.city；虚构城市角色不带真实城市。"""
    if resolve_city_mode(settings) == MODE_FICTIONAL:
        return None
    raw = (settings or {}).get("city")
    if raw is None:
        return None
    city = str(raw).strip()
    return city or None


async def assemble_turn_prompt(
    *,
    user_id: int,
    character_id: int | None = None,
    conversation_id: int | None = None,
    character_name: str = "",
    settings: Mapping[str, Any] | None = None,
    prompt_template: str | None = None,
    scene: str | None = None,
    output_language: str | None = "zh",
    # 调用方已取好的数据块（显式传入优先，避免本轮写入造成的假漂移）
    memory_context: str = "",
    emotion_block: str = "",
    tool_hint: str = "",
    tool_flags: Mapping[str, Any] | None = None,
    city: str | None = None,
    # 显式覆盖（None = 按场景默认）
    enable_chat_tools: bool | None = None,
    show_inner_thoughts: bool | None = None,
    max_pieces: int | None = None,
    include_time: bool | None = None,
    time_fact: str | None = None,
    after_base: str | None = None,
    # 记忆检索与摘要用的最后一句用户话
    last_user_message: str | None = None,
    session_summary: str | None = None,
    # 群聊附加段
    group_max_pieces: int = 0,
    group_other_characters: str = "",
    group_mention: str = "",
    # 调用方后缀（Java 不再拼接任何 prompt 文本，仅透传产品侧动态块）
    extra_system_suffix: str = "",
    prefetch_real_world: bool = True,
    use_internal_data: bool = True,
) -> str:
    """组装一轮的 system prompt。`use_internal_data=False` 时只拼调用方给的数据块。"""
    scene_key = normalize_scene(scene)
    if use_internal_data:
        memory_context, emotion_block, city = await _fill_internal_data(
            user_id=user_id,
            character_id=character_id,
            conversation_id=conversation_id,
            scene=scene_key,
            settings=settings,
            output_language=output_language,
            memory_context=memory_context,
            emotion_block=emotion_block,
            city=city,
            last_user_message=last_user_message,
            session_summary=session_summary,
        )

    # 工具提示：Java 只传开关事实，文案由 Python 查表生成
    if tool_flags is not None:
        from app.tools_hint import build_from_flags

        tool_hint = build_from_flags(tool_flags)

    prompt = assemble_scene_prompt(
        character_name=character_name,
        settings=settings,
        prompt_template=prompt_template,
        memory_context=memory_context,
        tool_hint=tool_hint,
        emotion_block=emotion_block,
        output_language=output_language,
        enable_chat_tools=(scene_enable_tools(scene_key)
                           if enable_chat_tools is None else enable_chat_tools),
        show_inner_thoughts=(scene_key != "VOICE_CALL"
                             if show_inner_thoughts is None else show_inner_thoughts),
        max_pieces=2 if max_pieces is None else max_pieces,
        include_time=(scene_include_time(scene_key)
                      if include_time is None else include_time),
        time_fact=time_fact,
        city=city,
        after_base=(scene_after_base(scene_key)
                    if after_base is None else after_base),
        last_user_message=last_user_message,
    )

    # 群聊附加段：顺序与 Java `ChatTurnPromptAssembler.assemble` 一致（自然风格之后）
    if scene_key == "GROUP":
        from app.rule_templates import render_group_chat_rules

        group_rules = render_group_chat_rules(
            output_language,
            character_name,
            group_other_characters,
            group_mention,
            group_max_pieces or (2 if max_pieces is None else max_pieces),
            scene_key != "VOICE_CALL" if show_inner_thoughts is None else show_inner_thoughts,
        )
        if group_rules.strip():
            prompt += "\n\n" + group_rules

    # 主动开口真实环境块（时间天气），在群聊之后、调用方后缀之前
    if scene_key == "PROACTIVE" and prefetch_real_world:
        from app.proactive_context import build_proactive_real_world_block

        prompt += await build_proactive_real_world_block(settings)

    if extra_system_suffix:
        prompt += extra_system_suffix

    return prompt


async def _fill_internal_data(
    *,
    user_id: int,
    character_id: int | None,
    conversation_id: int | None,
    scene: str,
    settings: Mapping[str, Any] | None,
    output_language: str | None,
    memory_context: str,
    emotion_block: str,
    city: str | None,
    last_user_message: str | None,
    session_summary: str | None,
) -> tuple[str, str, str | None]:
    """Python 自读记忆/情绪/城市。**已显式传入的块优先**，不覆盖。"""
    from app.data_readers import (
        load_character_owner,
        load_emotion,
        load_memory_context,
    )
    from app.emotion_block import format_emotion_block

    if not memory_context:
        memory_context = await load_memory_context(
            user_id,
            character_id or 0,
            conversation_id,
            **scene_include_flags(scene),
            last_user_message=last_user_message,
            session_summary_override=session_summary,
        ) or ""

    if not emotion_block:
        # 情绪按角色所有者维度存储，与 Java 保持一致
        owner_id = await load_character_owner(character_id or 0) or user_id
        emotion = await load_emotion(character_id or 0, owner_id)
        emotion_block = format_emotion_block(
            lang=output_language or "zh",
            current_emotion=(emotion or {}).get("current_emotion"),
            emotion_intensity=(emotion or {}).get("emotion_intensity"),
            status_text=(emotion or {}).get("status_text"),
            previous_emotion=(emotion or {}).get("previous_emotion"),
        ) if emotion else ""

    if not city:
        city = resolve_city(settings)

    return memory_context, emotion_block, city


__all__ = [
    "assemble_turn_prompt",
    "normalize_scene",
    "resolve_city",
    "resolve_city_mode",
    "scene_after_base",
    "scene_enable_tools",
    "scene_include_flags",
    "scene_include_time",
]
