"""VisionAnalysisParser 行为保真测试。

移植 Java backend/lianyu-service/src/test/.../VisionAnalysisParserTest 的断言集，
三形态 unwrap（<json> / ```json``` / 裸 JSON）与空字段语义逐一对照。
"""

import pytest

from app.vision import parse_vision_analysis


def test_parses_tagged_json():
    raw = '<json>{"subIntent":"求识图","confidence":"high","imageDescription":"一只橘猫趴在窗台上"}</json>'
    result = parse_vision_analysis(raw)
    assert result.sub_intent == "求识图"
    assert result.confidence == "high"
    assert result.image_description == "一只橘猫趴在窗台上"


def test_parses_fenced_json():
    raw = '```json\n{"subIntent":"分享日常","confidence":"low","imageDescription":"屏幕较模糊"}\n```'
    result = parse_vision_analysis(raw)
    assert result.sub_intent == "分享日常"
    assert result.confidence == "low"


def test_parses_bare_json():
    raw = '{"subIntent":"闲聊","confidence":"high","imageDescription":"桌面壁纸"}'
    result = parse_vision_analysis(raw)
    assert result.image_description == "桌面壁纸"


def test_missing_fields_become_none():
    raw = '{"subIntent":null,"confidence":null,"imageDescription":null}'
    result = parse_vision_analysis(raw)
    assert result.sub_intent is None
    assert result.confidence is None
    assert result.image_description is None


@pytest.mark.parametrize("bad", ["", "   ", "not json at all", "[1,2,3]", "<json>not json</json>"])
def test_invalid_payload_raises(bad):
    with pytest.raises(ValueError):
        parse_vision_analysis(bad)


def test_blank_strings_treated_as_none():
    raw = '{"subIntent":"  ","confidence":"high","imageDescription":""}'
    result = parse_vision_analysis(raw)
    assert result.sub_intent is None
    assert result.image_description is None
    assert result.confidence == "high"
