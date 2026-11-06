"""场景卡必须真的进 system prompt，而且在记忆块之后。

只测函数返回值不够：接线断了（参数没传、场景名不匹配、异常被吞）时
单测照样绿，线上却看不到【当前场景】。这里直接断言最终 prompt 文本。
"""

from __future__ import annotations

from types import SimpleNamespace

import pytest

from app.context import scene_card
from app.prompt_pipeline import assemble_turn_prompt


@pytest.fixture()
def card_store(monkeypatch):
    """用内存字典替掉 Redis，保留真实的 update_card/render_card。"""
    store: dict[int, dict] = {}

    async def fake_load(conversation_id: int):
        return store.get(conversation_id)

    async def fake_save(card: dict):
        store[int(card["conversationId"])] = card

    monkeypatch.setattr(scene_card, "load_card", fake_load)
    monkeypatch.setattr(scene_card, "save_card", fake_save)
    return store


async def _assemble(**overrides) -> str:
    kwargs = dict(
        user_id=7,
        character_id=3,
        conversation_id=11,
        character_name="小念",
        settings={"name": "小念"},
        scene="SINGLE",
        memory_context="【记忆】她怕打雷",
        last_user_message="今天加班到很晚，好累，你在吗？",
        use_internal_data=False,
    )
    kwargs.update(overrides)
    return await assemble_turn_prompt(**kwargs)


async def test_scene_block_lands_after_memory_block(card_store):
    prompt = await _assemble()

    assert "【当前场景】" in prompt
    assert "话题" in prompt
    assert prompt.index("【记忆】她怕打雷") < prompt.index("【当前场景】")
    # 问句进了"还没接的话"，这一轮模型能看到"她刚问了我一句"
    assert "还没接的话" in prompt


async def test_scene_card_persists_across_turns(card_store):
    await _assemble(last_user_message="今天加班到很晚，好累，你在吗？")
    card = card_store[11]
    assert "加班" in card["topic"]

    prompt = await _assemble(last_user_message="加班的时候领导还在催进度")
    assert card_store[11]["topic"] == card["topic"]  # 同话题不漂移
    assert "还没接的话" not in prompt  # 上一轮的问句已经清账


async def test_group_and_voice_scenes_skip_scene_card(card_store):
    for scene in ("GROUP", "VOICE_CALL", "DIARY"):
        prompt = await _assemble(scene=scene)
        assert "【当前场景】" not in prompt
    assert card_store == {}


async def test_scene_card_failure_does_not_break_prompt(monkeypatch):
    async def boom(conversation_id: int):
        raise RuntimeError("redis down")

    monkeypatch.setattr(scene_card, "load_card", boom)

    prompt = await _assemble()
    assert "【记忆】她怕打雷" in prompt
    assert "【当前场景】" not in prompt


async def test_same_turn_retry_does_not_duplicate_beat(card_store):
    await _assemble(last_user_message="你还记得我生日吗？")
    await _assemble(last_user_message="你还记得我生日吗？")

    beats = card_store[11]["beats"]
    assert beats.count("你还记得我生日吗？") == 1


async def test_switch_off_restores_plain_memory_block(monkeypatch, card_store):
    """回滚开关：线上把场景卡关掉，prompt 必须回到「只有记忆块」的样子。"""
    monkeypatch.setattr(
        "app.config.get_settings",
        lambda: SimpleNamespace(scene_card_enabled=False),
    )

    prompt = await _assemble()

    assert "【当前场景】" not in prompt
    assert "【记忆】她怕打雷" in prompt
    assert card_store == {}  # 连读都不读，避免半开状态
