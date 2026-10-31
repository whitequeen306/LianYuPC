"""B3：群聊 @ 提及裁决器的 prompt 从 Java 字面量搬到 Python 资源。

锁定两件事：
1. 渲染结果与 Java 原字面量**逐字相等**（含结尾换行、顿号、空行位置）；
2. 模板里的 `{{ }}` 转义在渲染后必须还原成单花括号 —— 模型要看到的是
   `{"shouldMention":...}`，不是 `{{"shouldMention":...}}`。
"""

from app.prompts.loader import render_prompt

# Java 原文（text block，结尾带一个换行）：
#   String systemPrompt = """
#       你是“群聊@提及裁决器”。……
#       4) 仅输出 JSON：{"shouldMention":true/false,...}
#       """;
LEGACY_SYSTEM = (
    "你是“群聊@提及裁决器”。只做一件事：判断当前消息是否应该添加一个@对象。\n"
    "规则：\n"
    "1) 只允许从候选列表中选择1个目标，或选择 NONE；\n"
    "2) 若语义没有明显点名对象，返回 NONE；\n"
    "3) 不要改写原句，不要输出解释；\n"
    '4) 仅输出 JSON：{"shouldMention":true/false,"target":"候选名或NONE","confidence":0~1}\n'
)


def test_system_prompt_matches_legacy_literal():
    assert render_prompt("group_mention_judge_system", leading_newlines=0) == LEGACY_SYSTEM


def test_system_prompt_renders_single_braces():
    out = render_prompt("group_mention_judge_system", leading_newlines=0)
    assert '{"shouldMention"' in out
    assert "{{" not in out and "}}" not in out


def test_user_prompt_matches_legacy_concatenation():
    # Java: "说话者: " + name + "\n候选目标: " + join("、") + "\n当前消息: " + content
    #       + "\n最近上下文:\n" + ctx
    out = render_prompt(
        "group_mention_judge_user",
        leading_newlines=0,
        speaker="甘雨",
        candidates="刻晴、你",
        content="你们觉得呢？",
        context="刻晴: 今天好累",
    )
    assert out == (
        "说话者: 甘雨\n"
        "候选目标: 刻晴、你\n"
        "当前消息: 你们觉得呢？\n"
        "最近上下文:\n"
        "刻晴: 今天好累"
    )


def test_user_content_braces_are_not_reparsed():
    out = render_prompt(
        "group_mention_judge_user",
        leading_newlines=0,
        speaker="甘雨",
        candidates="你",
        content='{"raw":true}',
        context="(空)",
    )
    assert '当前消息: {"raw":true}' in out
