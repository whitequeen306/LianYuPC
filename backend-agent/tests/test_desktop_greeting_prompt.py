"""桌面感知问候 prompt 的行为保真测试。

对照 Java VisionChatService.buildDesktopGreetingPrompt（VisionChatService.java:536-548），
断言措辞逐字一致 —— 资产清单第 1 条（行为保真）的直接落地。
"""

from app.desktop_observe import build_desktop_greeting_prompt
from app.vision import VisionAnalysisResult


def test_prompt_matches_java_wording():
    analysis = VisionAnalysisResult(
        sub_intent="分享日常", confidence="high", image_description="一只橘猫趴在窗台上"
    )
    prompt = build_desktop_greeting_prompt("你是一个温柔的角色。", "Visual Studio Code", analysis)
    expected = (
        "你是一个温柔的角色。\n\n"
        "你正在看着用户的电脑屏幕。当前画面：一只橘猫趴在窗台上\n"
        "图像可辨识度：high\n"
        "用户正在使用的窗口：Visual Studio Code\n\n"
        "如果图像可辨识度低，请自然承认看不太清。"
        "请用你的角色语气，对用户正在做的事情说一句话。\n"
        "要求：自然、口语化、不超过40字。不要加括号或动作描写。"
    )
    assert prompt == expected


def test_prompt_defaults_persona_and_title():
    analysis = VisionAnalysisResult(sub_intent=None, confidence=None, image_description=None)
    prompt = build_desktop_greeting_prompt("", "", analysis)
    assert prompt.startswith("你是一个可爱的桌面宠物。")
    assert "用户正在使用的窗口：未知" in prompt
    assert "图像可辨识度：unknown" in prompt
