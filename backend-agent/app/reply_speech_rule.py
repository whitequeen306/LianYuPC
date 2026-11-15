"""「必须有一句说出口的话」这条回复硬约束。

为什么单独成模块而不是塞进 `reply_rule_templates.json`：那个文件是从 Java 的
`ReplyBehaviorRuleHook` 渲染出来的快照，任何改动都要重跑 Java 侧的 golden 生成，
而 Java 侧已经不再产出 prompt。这条规则属于模型行为约束，归 Python 所有，
所以放在这里、由 `system_prompt.build_system_prompt` 紧跟回复规则之后拼接。

为什么必须有它（2026-11-15 现象）：角色人设写「温柔、话少」，模型把「话少」
执行成了「只写括号动作、一句台词都没有」——用户发「今天开了一下午会，脑子都麻了」，
回「（伸手把窗边那盏小夜灯拧亮，光晕柔柔地铺在桌角）」。这既没接住用户的话，
也不像聊天。原 Java 侧那条「不要用括号写动作」在现装配里根本没进 prompt，
所以这里不复用旧文案，直接写死约束。
"""

from __future__ import annotations

_RULES: dict[str, str] = {
    "zh": (
        "\n\n【说话方式 · 硬性】\n"
        "1. 每条回复至少要有一句说出口的话，正面回应用户刚说的内容。\n"
        "2. 括号只能用来补充动作、神态或心理活动；括号里的内容不能单独构成一条回复。\n"
        "3. 「话少」是句子短、不啰嗦，不是只写动作不说话。"
    ),
    "zh-TW": (
        "\n\n【說話方式 · 硬性】\n"
        "1. 每條回覆至少要有一句說出口的話，正面回應使用者剛說的內容。\n"
        "2. 括號只能用來補充動作、神態或心理活動；括號裡的內容不能單獨構成一條回覆。\n"
        "3. 「話少」是句子短、不囉嗦，不是只寫動作不說話。"
    ),
    "en": (
        "\n\n[How to speak - hard rules]\n"
        "1. Every reply must contain at least one spoken line that answers what the user just said.\n"
        "2. Parentheses may only supplement actions, expressions or inner thoughts; a parenthetical\n"
        "   note can never be a reply on its own.\n"
        "3. \"Speaks little\" means short sentences, not answering with actions only."
    ),
    "ja": (
        "\n\n【話し方・厳守】\n"
        "1. 返信には必ず、ユーザーが今言ったことに応える「口に出した台詞」を一文以上含めること。\n"
        "2. 括弧は動作・表情・心の声の補足にのみ使う。括弧の中身だけで返信を構成してはいけない。\n"
        "3. 「口数が少ない」とは短い文で話すことであり、動作だけで返さないこと。"
    ),
}


def _lang_key(lang: str | None) -> str:
    code = (lang or "zh").strip()
    lowered = code.lower().replace("_", "-")
    if lowered in ("zh-tw", "zhtw", "zh-hant", "zh-hk"):
        return "zh-TW"
    if lowered.startswith("ja"):
        return "ja"
    if lowered.startswith("en"):
        return "en"
    return "zh"


def render_speech_rule(lang: str | None) -> str:
    """返回该语言的硬约束段落（未知语言回落简体中文）。"""
    return _RULES[_lang_key(lang)]


def rule_languages() -> list[str]:
    return sorted(_RULES)


__all__ = ["render_speech_rule", "rule_languages"]
