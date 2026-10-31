"""C1–C5：朋友圈发帖 / 评论 / 回评 / 场景护栏的文案从 Java 搬到 Python。

断言口径与 A/B 批一致：**逐字等于 Java 原字面量**，包括
· 文本块结尾的换行（C1–C4 有、C5 没有）；
· 中文引号、顿号、波浪号等标点；
· C5 前导两个空行由 `leading_newlines=2` 补，模板文件本身不含空行。
"""

import pytest

from app.prompts.loader import render_prompt


def test_c1_mood_post_instruction():
    expected = (
        "请以该角色身份发一条像真人随手发的社交动态（第一人称）。\n"
        "主题：日常生活里的具体小事——通勤、吃饭、天气、逛街、追剧、晒太阳、加班、堵车、偶遇等；"
        "写看得见摸得着的生活细节，语气像真的在记录今天，不要架空剧情或堆砌世界观设定。\n"
        "要求：20~120字；口语自然；不要话题标签；不要解释自己是AI；不要写「朋友圈」三字；"
        "一条即可，不要换行分段。\n"
    )
    assert render_prompt("moments_post_mood", leading_newlines=0) == expected


def test_c2_reflection_post_instruction():
    expected = (
        "请以该角色身份发一条像真人随手发的社交动态（第一人称）。\n"
        "主题：结合最近和用户的聊天余韵，落到日常生活里的具体场景或心情（吃饭、出行、工作间隙、睡前等），"
        "可自然引用对话关键词，但不要照搬原句；不要写成对用户喊话。\n"
        "要求：20~120字；口语自然；不要话题标签；不要解释自己是AI；一条即可。\n"
    )
    assert render_prompt("moments_post_reflection", leading_newlines=0) == expected


def test_c3a_comment_on_user_post():
    out = render_prompt(
        "moments_comment_user_post", leading_newlines=0, content="今天下班路上看到一只橘猫"
    )
    assert out == (
        "用户刚发了一条朋友圈（不是你的用户专属对话，而是公开动态）：\n"
        "「今天下班路上看到一只橘猫」\n"
        "请以你的身份写一条评论：像朋友随口回一句，可以自然称呼用户。\n"
        "15~60字，不要重复原文，不要话题标签，不要解释自己是AI。\n"
    )


def test_c3b_comment_on_peer_post():
    out = render_prompt(
        "moments_comment_peer_post", leading_newlines=0, author="刻晴", content="加班到现在"
    )
    assert out == (
        "另一位角色「刻晴」刚发了一条朋友圈（不是你的用户，也不是你在对主人说话）：\n"
        "「加班到现在」\n"
        "请以你的身份，对「刻晴」这条动态写评论：像同行随口回一句，可以直呼对方名字。\n"
        "禁止：对用户/主人/Darling 说话；不要使用你专指用户的昵称；"
        "不要写「你来找我」这类明显在喊用户的句子。\n"
        "15~60字，不要重复原文，不要话题标签，不要解释自己是AI。\n"
    )


def test_c4a_reply_to_user_comment():
    out = render_prompt(
        "moments_reply_user", leading_newlines=0, post="今天的晚霞很好", comment="拍得真好看"
    )
    assert out == (
        "你在朋友圈发了一条动态，现在「用户」评论了：\n"
        "动态原文：「今天的晚霞很好」\n"
        "用户的评论：「拍得真好看」\n"
        "请以发帖人身份回复用户这条评论，简短自然，20~80字，不要话题标签，不要解释自己是AI。\n"
    )


def test_c4b_reply_to_peer_comment():
    out = render_prompt(
        "moments_reply_peer",
        leading_newlines=0,
        commenter="甘雨",
        post="今天的晚霞很好",
        comment="确实漂亮",
    )
    assert out == (
        "你在朋友圈发了一条动态，现在另一位角色「甘雨」评论了（不是用户）：\n"
        "动态原文：「今天的晚霞很好」\n"
        "甘雨 的评论：「确实漂亮」\n"
        "请以发帖人身份回复 TA 这条评论；对话对象是 甘雨，不要对屏幕前的用户说话，"
        "也不要把对方当成你的 Darling/主人。\n"
        "20~80字，不要话题标签，不要解释自己是AI。\n"
    )


@pytest.mark.parametrize(
    ("name", "text"),
    [
        ("moments_scene_guard_user_zh",
         "【朋友圈场景】你在回复「用户」对你所发动态的评论；对方是人类用户，可以自然称呼。"),
        ("moments_scene_guard_user_zh_tw",
         "【朋友圈場景】你在回覆「用戶」對你所發動態的評論；對方是人類用戶，可以自然稱呼。"),
        ("moments_scene_guard_user_ja",
         "【朋友圈】あなたの投稿への「ユーザー」コメントへの返信。相手は人間ユーザー。"),
        ("moments_scene_guard_user_en",
         "[Moment scene] You are replying to the human user's comment on your post. "
         "The user is your chat partner."),
    ],
)
def test_c5_user_guards(name, text):
    # Java 原文是 "\n\n" + 文案，且结尾不带换行
    assert render_prompt(name, leading_newlines=2) == "\n\n" + text


@pytest.mark.parametrize(
    ("name", "template"),
    [
        ("moments_scene_guard_peer_zh",
         "【朋友圈场景·角色互动】你正在对另一角色「{t}」说话，不是对屏幕前的用户。"
         "禁止把单聊里专指用户的昵称（如 Darling、主人、宝贝）用在本次评论；请用「{t}」称呼对方。"),
        ("moments_scene_guard_peer_zh_tw",
         "【朋友圈場景·角色互動】你正在對另一角色「{t}」說話，不是對螢幕前的用戶。"
         "禁止把單聊裡專指用戶的暱稱（如 Darling、主人、寶貝）用在本次評論；請用「{t}」稱呼對方。"),
        ("moments_scene_guard_peer_ja",
         "【朋友圈・キャラ同士】相手は別キャラ「{t}」で、ユーザーではない。"
         "ユーザー専用の呼び方（Darling 等）は使わない。投稿者の名前で話す。"),
        ("moments_scene_guard_peer_en",
         "[Moment scene — character only] You are talking to another character \"{t}\", "
         "NOT the human user. Do not use pet names you reserve for the user (e.g. Darling). "
         "Address \"{t}\" by name or natural second person toward them."),
    ],
)
def test_c5_peer_guards(name, template):
    expected = "\n\n" + template.format(t="刻晴")
    assert render_prompt(name, leading_newlines=2, target="刻晴") == expected
