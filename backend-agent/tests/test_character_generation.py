"""角色设定生成 / 抽取：prompt 与模型调用（含 DeepSeek 回退）。"""

import pytest
from fastapi.testclient import TestClient

from app.main import app
from app.character_generation import (
    GENERATE_SYSTEM,
    GENERATION_QUALITY_RULES,
    IMPORT_SYSTEM,
    build_generate_messages,
    build_import_messages,
    generate_character,
    import_character,
)


def test_generate_prompt_contains_rules_and_description():
    messages = build_generate_messages("雷电将军，稻妻的神")
    assert messages[0]["role"] == "system"
    assert GENERATE_SYSTEM in messages[0]["content"]
    assert GENERATION_QUALITY_RULES in messages[0]["content"]
    assert messages[1]["content"] == "角色描述：雷电将军，稻妻的神"


def test_import_prompt_wraps_material_and_addressing():
    messages = build_import_messages("你是将军。", "将军")
    assert IMPORT_SYSTEM in messages[0]["content"]
    body = messages[1]["content"]
    assert "用户指定该角色最常用的称呼是「将军」" in body
    assert "<user_message trusted=\"false\">" in body
    assert "你是将军。" in body


def test_import_source_is_xml_escaped():
    messages = build_import_messages("<script>alert(1)</script>")
    assert "&lt;script&gt;" in messages[1]["content"]


class _Result:
    def __init__(self, content: str):
        self.content = content


class _Gateway:
    def __init__(self, contents, fail_first: bool = False):
        self.contents = list(contents)
        self.fail_first = fail_first
        self.models = []

    async def chat_messages(self, **kwargs):
        self.models.append(kwargs["model"])
        if self.fail_first and len(self.models) == 1:
            raise RuntimeError("primary down")
        return _Result(self.contents.pop(0))


@pytest.mark.asyncio
async def test_generate_returns_content_and_model():
    gateway = _Gateway(['{"name":"雷电将军"}'])
    content, model = await generate_character(
        gateway, base_url="https://api.example/v1", api_key="k", model="m",
        description="雷电将军")
    assert content.startswith('{"name"')
    assert model == "m"


@pytest.mark.asyncio
async def test_deepseek_falls_back_to_chat_model():
    gateway = _Gateway(['{"name":"x"}'], fail_first=True)
    content, model = await generate_character(
        gateway, base_url="https://api.deepseek.com/v1", api_key="k", model="deepseek-v3",
        description="原创角色")
    assert content == '{"name":"x"}'
    assert model == "deepseek-chat"
    assert gateway.models == ["deepseek-v3", "deepseek-chat"]


@pytest.mark.asyncio
async def test_import_uses_import_prompt():
    gateway = _Gateway(['{"name":"甲"}'])
    content, _ = await import_character(
        gateway, base_url="https://api.example/v1", api_key="k", model="m",
        prepared_source="人设卡", user_addressing="")
    assert content == '{"name":"甲"}'


def test_generate_endpoint_contract(monkeypatch):
    client = TestClient(app)
    monkeypatch.setattr("app.main.get_gateway", lambda: _Gateway(['{"name":"甲"}']))
    response = client.post("/agent/character/generate", json={
        "userId": 7, "baseUrl": "https://api.example/v1", "apiKey": "k",
        "model": "m", "description": "一个原创角色",
    })
    assert response.status_code == 200
    body = response.json()
    assert body["content"] == '{"name":"甲"}'
    assert body["model"] == "m"


def test_character_endpoint_rejects_user_mismatch():
    client = TestClient(app)
    response = client.post("/agent/character/import", json={
        "userId": 7, "baseUrl": "https://api.example/v1", "apiKey": "k",
        "model": "m", "preparedSource": "人设卡",
    }, headers={"X-User-Id": "8"})
    assert response.status_code == 403
