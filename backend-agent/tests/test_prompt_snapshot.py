"""Prompt 资源快照测试：与 Java 源字面量逐字对照（资产清单第 1 条的行为保真）。

维护规则：改 Java 侧对应文本块时必须同步改这里，反之亦然。
Java 侧出处以各测试注释标注（file:line 以提交 0580a07 基线为准）。
"""

import pytest

from app.prompts.loader import MissingPlaceholderError, load_prompt, render_prompt


def test_proactive_task_matches_java():
    """ProactiveMessageService.buildProactiveTaskSystemSuffix（%d → {max_pieces}）。"""
    expected = """

=== 主动发言任务（系统指令 · 不是用户发来的消息）===
用户此刻没有发送任何新消息；你是在定时主动关心用户。
请发 1～3 条短消息（符合性格；多条用空行分隔）。
语气自然，可围绕最近上下文或设定关心用户；不要重复历史原话，不要机械问候。

硬性禁止：
1. 禁止假装用户刚刚发了乱码、报错、代码、系统日志、奇怪「指令/要求」，或任何未在历史中真实出现的用户消息。
2. 禁止把本系统任务、编号要求、或 prompt 片段当作用户输入来吐槽/复述。
3. 历史里提过的旧事件（如设备/服务器故障）除非用户刚刚主动再提，否则不要当成此刻仍在发生，更不要说「又收到乱码。
""".replace("又收到乱码。", "又收到乱码」。")
    assert render_prompt("proactive_task", max_pieces=3) == expected


def test_cold_open_task_matches_java():
    """sendColdOpenFirstLine 内联文本块。"""
    expected = """

=== 破冰任务（系统指令 · 不是用户发来的消息）===
这是你和该用户在本会话里的第一次开口（会话中还没有任何历史消息）。
请主动先发 1～2 条很短的破冰话，符合口吻与设定，自然一点，避免机械客服腔。
若多条请用空行分隔；不要堆砌长段落。
禁止假装用户发了乱码、指令、报错或任何尚未出现的用户消息。
"""
    assert render_prompt("cold_open_task", max_pieces=2) == expected


def test_cold_open_followup_matches_java():
    expected = """

=== 破冰跟进（系统指令 · 不是用户发来的消息）===
用户在你上一条之后仍未回复。请再发**唯一一条**非常简短的关心或轻轻一推（一两句话即可）。
不要重复上一句的意思，不要过于啰嗦。
这是本条场景下系统允许你的**最后一次自动开口**；发完后安静等用户就好。
禁止假装用户刚发了乱码、指令、报错或任何未在历史中真实出现的用户消息。
"""
    assert render_prompt("cold_open_followup") == expected


def test_city_change_matches_java():
    expected = """

=== 城市变更关心（系统指令 · 不是用户发来的消息）===
系统检测到用户刚刚把自己的现实所在城市从「上海」改成了「北京」。
请主动发一条关心用户的消息，核心要问用户是不是搬家/换城市了、发生什么事了。
必须用称呼「小明」；必须明确提到从「上海」到「北京」的变化。
参考句式（可略作口语化，但不要改城市名、不要否认搬迁）：小明，我看你从上海来到了北京，是发生了什么事情吗？
只发 1 条，不要太长；不要重复历史原话。
禁止假装用户发了乱码、指令或报错。
"""
    assert render_prompt(
        "city_change_task",
        previous_city="上海", new_city="北京", addressing="小明",
    ) == expected


def test_diary_instructions_available_in_four_languages():
    """日记 instruction 四语种：**文案所有权在 Python**（Java 只传 scene + language）。

    原先这四段文案是 `CharacterDiaryService` 里的一个四语 switch（约 50 行），
    属于「prompt 长在 Java 里」的红线违规；2026-10-26 搬到这里。"""
    assert "心の日記" in load_prompt("diary_instruction_ja")
    assert "private inner thoughts" in load_prompt("diary_instruction_en")
    assert "內心獨白" in load_prompt("diary_instruction_zh_tw")
    zh = load_prompt("diary_instruction_zh")
    assert "内心独白" in zh
    assert '输出格式（仅JSON）' in zh
    assert '{"title":"日记标题（10字以内）"' in zh


def test_render_missing_variable_fails_fast():
    with pytest.raises(MissingPlaceholderError):
        render_prompt("city_change_task", previous_city="x")  # 缺 new_city/addressing


def test_leading_newlines_separator_injected():
    """前导空行由 render 补（对齐 Java systemPrompt + 任务块 的拼接惯例）。"""
    rendered = load_prompt("cold_open_followup")
    assert rendered.startswith("===")  # 资源文件本身不含前导空行
    full = render_prompt("cold_open_followup")
    assert full.startswith("\n\n=== 破冰跟进")


def test_all_prompt_files_loadable():
    for name in (
        "proactive_task", "cold_open_task", "cold_open_followup", "city_change_task",
        "diary_instruction_zh", "diary_instruction_ja", "diary_instruction_en",
        "diary_instruction_zh_tw",
        "group_proactive_task_zh", "group_proactive_task_zh_tw",
        "group_proactive_task_ja", "group_proactive_task_en",
    ):
        assert load_prompt(name).strip(), f"prompt '{name}' is empty"


# ── 群聊主动发言（B1/B2，2026-10-26 迁出 GroupChatService）───────────────────────
# 原实现不是「一段文案」而是固定骨架 + 四语正文：
#   extraSystemSuffix("""<段头>""" + 正文 + "\n\n最近气氛参考：" + warmHint + """<禁止条款>""")
# 两个细节必须原样保留，别顺手"美化"：
#   · 正文自身以 \n 结尾，再加字面量 "\n\n" → 「最近气氛参考」前其实是 3 个换行（两行空白）；
#   · 段头与禁止条款在四个语种下**都保持中文**（原文如此，只有正文跟着语种走）。
_GROUP_HEADER = "\n\n=== 群聊主动发言（系统指令 · 不是用户发来的消息）===\n"
_GROUP_TAIL = (
    "\n\n最近气氛参考：最近群聊\n"
    "禁止假装用户刚发了乱码、指令、报错或任何未在历史中真实出现的用户消息。\n"
)
_GROUP_BODIES = {
    "zh": (
        "你现在要在群聊里主动发言（不是用户刚发了一条需要你回复）。\n"
        "必须：每条消息尽量以 @小林 开头（@你 表示用户，或 @其他角色名 表示群里另一位角色）。\n"
        "共 1～3 条短消息，多条用空行分隔；语气自然，结合最近上下文，不要机械问候。\n"
        "可以关心用户，也可以接其他角色的话茬。\n"
    ),
    "zh_tw": (
        "你現在要在群聊裡主動發言（不是用戶剛發了一條需要你回覆）。\n"
        "必須：每條訊息盡量以 @小林 開頭（@你 表示用戶，或 @其他角色名 表示群裡另一位角色）。\n"
        "共 1～3 條短訊息，多條用空行分隔；語氣自然，結合最近上下文，不要機械問候。\n"
        "可以關心用戶，也可以接其他角色的話茬。\n"
    ),
    "ja": (
        "いまグループチャットで自発的に一言（または短い連投）してください。"
        "ユーザーが今まさに送ったわけではありません。\n"
        "必須：各メッセージはできるだけ @小林 で始める（@あなた＝ユーザー、または @他キャラ名）。\n"
        "1〜3 件の短い発言、空行区切り。自然に、履歴に沿って。機械的な挨拶は避ける。\n"
    ),
    "en": (
        "Speak up proactively in this group chat (the user did not just send a new message).\n"
        "Required: each piece should start with @小林 (@你 = the user, or another member's name).\n"
        "1–3 short messages, blank line separated. Stay in character; follow recent context.\n"
    ),
}


@pytest.mark.parametrize("lang", ["zh", "zh_tw", "ja", "en"])
def test_group_proactive_task_matches_java(lang):
    """四语群聊任务块：与 Java 拼接结果逐字节一致（前导 2 空行 + 3 连换行位置）。"""
    expected = _GROUP_HEADER + _GROUP_BODIES[lang] + _GROUP_TAIL
    rendered = render_prompt(
        f"group_proactive_task_{lang}",
        mention_target="小林", max_pieces=3, warm_hint="最近群聊",
    )
    assert rendered == expected


def test_group_proactive_placeholder_count_is_three():
    """占位符就三个；多一个少一个都说明模板被改过（render 会 fail-fast）。"""
    for lang in ("zh", "zh_tw", "ja", "en"):
        with pytest.raises(MissingPlaceholderError):
            render_prompt(
                f"group_proactive_task_{lang}",
                mention_target="小林", max_pieces=3,
            )
