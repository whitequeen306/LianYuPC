"""`/agent/prompt/assemble` 端点测试。

复用已有的 `end_to_end_golden.json`（Java 真实 `assemble()` 产出）：
同样的输入必须得到同样的 2588 字符 —— 这就是接线后的可信依据。
"""

import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.main import app
from app.system_prompt import assemble_scene_prompt

GOLDEN = Path(__file__).resolve().parent.parent / "app" / "prompts" / "end_to_end_golden.json"
SETTINGS = {"personality": "温柔但有点固执", "city_mode": "real", "city": "上海"}

client = TestClient(app)


@pytest.fixture(scope="module")
def golden() -> dict:
    return json.loads(GOLDEN.read_text(encoding="utf-8"))


def payload(golden, lang: str) -> dict:
    memory = "\n\n".join([golden["memoryBlock"], golden["relationshipBlock"], golden["sessionBlock"]])
    return {
        "userId": 7,
        "characterName": "小林",
        "settings": SETTINGS,
        "memoryContext": memory,
        "toolHint": "[[TOOL_HINT]]",
        "emotionBlock": "\n\n[[EMOTION]]",
        "outputLanguage": lang,
        "enableChatTools": True,
        "showInnerThoughts": True,
        "maxPieces": 2,
        "includeTime": True,
        "timeFact": golden["timeFact"],
        "city": golden["city"],
        "afterBase": "full",
    }


def test_direct_call_matches_java_scene_assembly(golden):
    """assemble_scene_prompt 与 Java 的 assemble() 逐字节一致。"""
    memory = "\n\n".join([golden["memoryBlock"], golden["relationshipBlock"], golden["sessionBlock"]])
    actual = assemble_scene_prompt(
        character_name="小林", settings=SETTINGS, prompt_template=None,
        memory_context=memory, tool_hint="[[TOOL_HINT]]", emotion_block="\n\n[[EMOTION]]",
        output_language="zh", enable_chat_tools=True, show_inner_thoughts=True,
        max_pieces=2, include_time=True, time_fact=golden["timeFact"], city=golden["city"],
        after_base="full")
    assert actual == golden["prompts"]["zh"]


def test_endpoint_matches_java_scene_assembly(golden):
    response = client.post("/agent/prompt/assemble", json=payload(golden, "zh"))
    assert response.status_code == 200
    body = response.json()
    assert body["systemPrompt"] == golden["prompts"]["zh"]


def test_endpoint_rejects_user_mismatch(golden):
    bad = payload(golden, "zh")
    response = client.post("/agent/prompt/assemble", json=bad,
                           headers={"X-User-Id": "999"})
    assert response.status_code == 403


def test_style_only_branch_skips_time_and_city(golden):
    body = payload(golden, "zh")
    body["afterBase"] = "style_only"
    response = client.post("/agent/prompt/assemble", json=body)
    prompt = response.json()["systemPrompt"]
    assert "当前真实环境" not in prompt
    assert "用户当前所在现实城市" not in prompt
    assert "表达要求" in prompt  # 自然风格块仍在


def test_none_branch_adds_nothing(golden):
    body = payload(golden, "zh")
    body["afterBase"] = "none"
    prompt = client.post("/agent/prompt/assemble", json=body).json()["systemPrompt"]
    assert "当前真实环境" not in prompt
    assert "表达要求" not in prompt
    assert "安全与边界" in prompt  # base 内的安全块不受影响


def test_city_optional(golden):
    body = payload(golden, "zh")
    body["city"] = None
    prompt = client.post("/agent/prompt/assemble", json=body).json()["systemPrompt"]
    assert "用户当前所在现实城市" not in prompt
    assert "当前真实环境" in prompt


def test_group_scene_skips_time_and_appends_group_rules(golden):
    body = payload(golden, "zh")
    body.update({
        "scene": "GROUP",
        "afterBase": "none",
        "groupMaxPieces": 2,
        "groupOtherCharacters": "其他角色：阿明",
        "groupMention": "@小林",
    })
    prompt = client.post("/agent/prompt/assemble", json=body).json()["systemPrompt"]
    assert "当前真实环境" not in prompt
    assert "用户当前所在现实城市" not in prompt
    assert "群聊" in prompt
    assert "阿明" in prompt


def test_extra_system_suffix_is_appended_last(golden):
    body = payload(golden, "zh")
    body["extraSystemSuffix"] = "\n\n[[EXTRA]]"
    prompt = client.post("/agent/prompt/assemble", json=body).json()["systemPrompt"]
    assert prompt.endswith("[[EXTRA]]")


def test_proactive_scene_appends_real_world_block(golden, monkeypatch):
    body = payload(golden, "zh")
    body["scene"] = "PROACTIVE"

    async def fake_block(settings, **_kwargs):
        return "\n\n[[REAL_WORLD]]"

    monkeypatch.setattr(
        "app.proactive_context.build_proactive_real_world_block", fake_block)
    prompt = client.post("/agent/prompt/assemble", json=body).json()["systemPrompt"]
    assert prompt.endswith("[[REAL_WORLD]]")
