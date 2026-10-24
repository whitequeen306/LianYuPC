"""动作意图识别 —— 只为一件事服务：**防止模型空口承诺**。

真实事故（2026-10-24，user 402）：
用户说「帮我打开网易云音乐播放大海」，模型回了「好的，这就为您打开。正在为您启动网易云音乐。」
—— 一个 tool_call 都没有（审计表零记录，`computer=true` 且 14 个 desk_* 都已下发）。
之后用户自然认为「它答应了却不动手」。

这类失败靠提示词只能压低概率，压不到零，所以加一层兜底：
**当「desk_* 可用 + 用户这句话确实是要动本机」而模型第一轮没发 tool_call 时，
追加一条系统提示再问一次**（只加一次，绝不递归）。

判错的代价很小：最坏是多花一次模型调用。所以这里故意偏"宽"，
只要句子里出现明确的动作动词就算命中。
"""

from __future__ import annotations

import re
from typing import Any, Iterable

# 一个动作动词就够（宁可多问一次，也别让角色只说不动）
_ACTION_VERBS: tuple[str, ...] = (
    # 应用 / 窗口
    "打开", "开启", "关闭", "关掉", "启动", "运行", "点开", "点一下", "点击",
    "切换窗口", "切到", "最大化", "最小化", "置顶",
    # 输入类
    "输入", "打字", "填入", "写上", "复制", "粘贴", "保存", "搜索", "帮我搜",
    # 媒体 / 系统
    "播放", "放首", "放个", "放点", "来首", "暂停", "下一首", "上一首", "切歌",
    "调音", "音量", "静音", "媒体", "勿扰",
    # 屏幕
    "截图", "截个图", "看一眼", "看一下当前", "看看屏幕",
)

# 有些请求没有动作动词，但明确是要看屏幕
_SCREEN_PATTERNS: tuple[re.Pattern[str], ...] = (
    re.compile(r"屏幕(上|里)?(有|显示|是)"),
    re.compile(r"看(一下|下|看).{0,6}(屏幕|桌面|窗口)"),
    re.compile(r"(当前|现在)(打开|显示)的?(窗口|界面|应用)"),
)

_MAX_LEN = 300


def looks_like_desktop_action(text: str | None) -> bool:
    """这句话是不是要我们「动本机」？只做粗筛，偏宽。"""
    value = (text or "").strip()
    if not value or len(value) > _MAX_LEN:
        return False
    if any(verb in value for verb in _ACTION_VERBS):
        return True
    return any(pattern.search(value) for pattern in _SCREEN_PATTERNS)


def _content_to_text(content: Any) -> str:
    """messages 里的 content 可能是 str，也可能是 [{type:text, text:...}] 分段。"""
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts: list[str] = []
        for item in content:
            if isinstance(item, dict) and isinstance(item.get("text"), str):
                parts.append(item["text"])
            elif isinstance(item, str):
                parts.append(item)
        return " ".join(parts)
    return ""


def last_user_text(messages: Iterable[dict[str, Any]] | None) -> str:
    """取最后一条 user 消息的纯文本（跳过 tool / assistant）。"""
    if not messages:
        return ""
    for message in reversed(list(messages)):
        if not isinstance(message, dict):
            continue
        if message.get("role") != "user":
            continue
        return _content_to_text(message.get("content"))
    return ""


DESKTOP_ACTION_NUDGE = (
    "（系统提示，用户看不见）你刚才只回复了文字，没有调用任何工具；但用户这句话是要在"
    "【本机电脑】上做事的。请立刻用合适的 desk_* 工具动手：看界面用 desk_screen_read，"
    "开应用用 desk_app_launch，点击用 desk_click，输入用 desk_type/desk_key，"
    "放歌调音量静音用 desk_system_control。\n"
    "拿不到工具结果之前，禁止说「已打开 / 已播放 / 正在为您启动 / 这就为您办」这类完成的话；"
    "如果确实做不到，就直说做不到以及卡在哪，不要假装已经做了。"
)
