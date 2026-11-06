"""recent 槽裁剪接线：开关关着时一行都不能少。"""

from types import SimpleNamespace

import pytest

from app import chat_turn


def _history(count: int) -> list[dict]:
    return [
        {
            "id": i + 1,
            "role": "user" if i % 2 == 0 else "assistant",
            "content": f"{i}" + "啊" * 120,
        }
        for i in range(count)
    ]


def _state(count: int) -> dict:
    return {
        "system_prompt": "人设",
        "history": _history(count),
        "user_text": "最后一句",
        "current_user_message_id": count,
    }


@pytest.mark.asyncio
async def test_trim_drops_oldest_but_keeps_system_and_latest(monkeypatch):
    monkeypatch.setattr(
        "app.config.get_settings",
        lambda: SimpleNamespace(history_trim_enabled=True),
    )

    result = await chat_turn.assemble_messages(_state(40))
    messages = result["messages"]

    assert messages[0] == {"role": "system", "content": "人设"}
    assert len(messages) < 41
    assert "最后一句" in messages[-1]["content"]
    assert result["prompt_tokens_estimated"] > 0


@pytest.mark.asyncio
async def test_trimming_is_off_by_default(monkeypatch):
    monkeypatch.setattr(
        "app.config.get_settings",
        lambda: SimpleNamespace(history_trim_enabled=False),
    )

    result = await chat_turn.assemble_messages(_state(40))

    assert len(result["messages"]) == 41
