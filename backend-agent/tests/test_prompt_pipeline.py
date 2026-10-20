"""prompt 组装单一实现 + 回合消息组装的边界语义。

这两块曾经各写了两份（`main.py` 端点一份、`chat_turn.py` 节点一份），
实测已经漂移（城市块在 settings 缺 `city_mode` 时行为不一致）。这里锁住：
Python 只有一处实现，且场景开关与 Java `ChatTurnScene` 逐条对应。
"""

from __future__ import annotations

import pytest

from app.chat_turn import assemble_messages
from app.prompt_pipeline import (
    assemble_turn_prompt,
    resolve_city,
    resolve_city_mode,
    scene_after_base,
    scene_enable_tools,
    scene_include_time,
)

BASE_SETTINGS = {"city": "郑州", "city_mode": "real"}


# ─────────────────────────── 城市语义（对齐 Java resolveCityMode） ───────────────────────────


def test_city_mode_defaults_to_real_when_field_missing():
    """settings 里没有 city_mode 时按现实城市处理（Java resolveCityMode 的默认分支）。"""
    assert resolve_city_mode({}) == "real"
    assert resolve_city_mode(None) == "real"
    assert resolve_city({"city": "郑州"}) == "郑州"


def test_city_mode_fictional_wins_over_city():
    assert resolve_city_mode({"city_mode": "fictional"}) == "fictional"
    assert resolve_city({"city": "虚构城", "city_mode": "FICTIONAL"}) is None


def test_city_mode_legacy_flag_is_fictional():
    """历史字段 use_fictional_city 仍然生效（老角色未迁移的情况）。"""
    assert resolve_city_mode({"use_fictional_city": True}) == "fictional"
    assert resolve_city_mode({"use_fictional_city": "true"}) == "fictional"
    assert resolve_city_mode({"use_fictional_city": False}) == "real"


def test_city_mode_blank_string_is_not_fictional():
    assert resolve_city_mode({"city_mode": ""}) == "real"
    assert resolve_city_mode({"city_mode": "  "}) == "real"


# ─────────────────────────── 场景开关 ───────────────────────────


@pytest.mark.parametrize(
    "scene,expected",
    [
        ("SINGLE", "full"),
        ("PROACTIVE", "full"),
        ("MOMENTS", "style_only"),
        ("DIARY", "style_only"),
        ("VOICE_CALL", "time_only"),
        ("GROUP", "none"),
    ],
)
def test_scene_after_base_matches_java(scene, expected):
    assert scene_after_base(scene) == expected


def test_scene_tools_and_time_flags():
    assert scene_enable_tools("GROUP") is True
    assert scene_enable_tools("MOMENTS") is False
    assert scene_enable_tools("VOICE_CALL") is False
    assert scene_include_time("VOICE_CALL") is True
    assert scene_include_time("GROUP") is False


# ─────────────────────────── 组装结果 ───────────────────────────


async def test_group_prompt_appends_group_rules():
    """群聊要追加群聊规则段；且 afterBase=none 时不再重复场景块。"""
    common = dict(
        user_id=1,
        character_name="小雨",
        settings=BASE_SETTINGS,
        prompt_template="你是{{char}}。",
        output_language="zh",
        use_internal_data=False,
    )
    single = await assemble_turn_prompt(scene="SINGLE", **common)
    group = await assemble_turn_prompt(
        scene="GROUP",
        group_max_pieces=2,
        group_other_characters="阿泽、小雨",
        group_mention="@小雨",
        **common,
    )
    assert len(group) > len(single)
    assert "@小雨" in group


async def test_extra_system_suffix_appended_last():
    base = await assemble_turn_prompt(
        user_id=1,
        character_name="小雨",
        settings=BASE_SETTINGS,
        prompt_template="你是{{char}}。",
        output_language="zh",
        scene="GROUP",
        use_internal_data=False,
    )
    with_suffix = await assemble_turn_prompt(
        user_id=1,
        character_name="小雨",
        settings=BASE_SETTINGS,
        prompt_template="你是{{char}}。",
        output_language="zh",
        scene="GROUP",
        extra_system_suffix="\n\n【后缀】",
        use_internal_data=False,
    )
    assert with_suffix == base + "\n\n【后缀】"


async def test_proactive_prefetch_can_be_disabled():
    """prefetch_real_world=False 时不读真实天气（测试里避免外部 IO）。"""
    prompt = await assemble_turn_prompt(
        user_id=1,
        character_name="小雨",
        settings=BASE_SETTINGS,
        prompt_template="你是{{char}}。",
        output_language="zh",
        scene="PROACTIVE",
        prefetch_real_world=False,
        use_internal_data=False,
    )
    assert "你是小雨" in prompt or prompt


# ─────────────────────────── 消息组装：preparedMessages 分支 ───────────────────────────


async def test_prepared_messages_replaces_system_at_index_zero():
    """群聊主动发言：调用方给全量消息，只把第 0 条 system 换成组装好的 prompt。"""
    state = {
        "system_prompt": "SYS",
        "prepared_messages": [
            {"role": "system", "content": "旧 system"},
            {"role": "user", "content": "历史一"},
            {"role": "assistant", "content": "历史二"},
        ],
    }
    out = await assemble_messages(state)
    assert out["messages"] == [
        {"role": "system", "content": "SYS"},
        {"role": "user", "content": "历史一"},
        {"role": "assistant", "content": "历史二"},
    ]


async def test_prepared_messages_inserts_system_when_absent():
    state = {
        "system_prompt": "SYS",
        "prepared_messages": [{"role": "user", "content": "历史一"}],
    }
    out = await assemble_messages(state)
    assert out["messages"][0] == {"role": "system", "content": "SYS"}
    assert out["messages"][1] == {"role": "user", "content": "历史一"}


async def test_prepared_messages_normalizes_image_placeholder():
    """prepared 分支同样要做图片占位归一（Java forHistory 对每条都调）。"""
    state = {
        "system_prompt": "SYS",
        "prepared_messages": [
            {"role": "user", "content": "", "imageUrl": "a.png"},
        ],
    }
    out = await assemble_messages(state)
    assert out["messages"][1]["content"] == "（用户发了一张图片）"


# ─────────────────────────── 消息组装：history 分支细节 ───────────────────────────


async def test_history_prefers_trimmed_context_content():
    """对齐 MessageModelContent.forModel：contextContent 去空白后优先。"""
    state = {
        "system_prompt": "SYS",
        "history": [{"id": 1, "role": "user", "content": "可见文案", "contextContent": "  模型文案  "}],
    }
    out = await assemble_messages(state)
    assert out["messages"][1]["content"] == "模型文案"


async def test_history_skips_blank_context_and_blank_content():
    state = {
        "system_prompt": "SYS",
        "history": [
            {"id": 1, "role": "user", "content": "  ", "contextContent": "   "},
            {"id": 2, "role": "user", "content": "留下"},
        ],
    }
    out = await assemble_messages(state)
    assert [m["content"] for m in out["messages"]] == ["SYS", "留下"]


async def test_current_message_wins_over_stored_content():
    state = {
        "system_prompt": "SYS",
        "current_user_message_id": 7,
        "user_text": "现在说的话",
        "history": [
            {"id": 7, "role": "user", "content": "旧落库内容"},
            {"id": 8, "role": "assistant", "content": "上一条回复"},
        ],
    }
    out = await assemble_messages(state)
    content = out["messages"][1]["content"]
    assert content.startswith('<user_message trusted="false">')
    assert content.rstrip().endswith("</user_message>")
    assert "现在说的话" in content
    assert "旧落库内容" not in content


async def test_no_current_id_appends_user_text_once():
    """没给 currentUserMessageId 的独立调用补一条用户消息（真实链路会给 id，不会重复）。"""
    state = {
        "system_prompt": "SYS",
        "user_text": "你好",
        "history": [{"id": 8, "role": "assistant", "content": "上一句"}],
    }
    out = await assemble_messages(state)
    assert [m["role"] for m in out["messages"]] == ["system", "assistant", "user"]
