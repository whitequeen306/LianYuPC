"""会话摘要块 —— 对齐 `SessionSummaryService.formatForPrompt`。

格式：
    \\n\\n=== 本次对话 Earlier 摘要 ===\\n
    <trim 后的摘要>
    \\n\\n说明：摘要描述本次聊天中较早的内容；与用户最近几条消息冲突时，以最近消息为准。

文本来自 `fragments.json`（由 Java 源码生成），不手抄。
空串 / 纯空白返回 None（与 Java 一致）。
"""

from __future__ import annotations

from app.prompt_fragments import fragment

PREFIX_LINE = 177
SUFFIX_LINE = 183


def format_session_summary(text: str | None) -> str | None:
    """对齐 `formatPrompt`：空白返回 None，否则 前缀 + trim(text) + 后缀。"""
    if text is None or not text.strip():
        return None
    return (
        fragment("SessionSummaryService.java", PREFIX_LINE)
        + text.strip()
        + fragment("SessionSummaryService.java", SUFFIX_LINE)
    )


__all__ = ["SUFFIX_LINE", "PREFIX_LINE", "format_session_summary"]