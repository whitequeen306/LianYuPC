"""token 估算（本地、无网络、可单测）。

为什么不直接调 tokenizer：一轮对话要按槽位反复试算，tokenizer 引入
（tiktoken/Qwen 词表）会把「预算」变成同步依赖和额外内存；而预算只需要
±15% 的精度来决定「砍哪一段」，真正计费仍以网关返回的 usage 为准。

校准：中文按 1 字 ≈ 1 token（DeepSeek/Qwen 系实测 1.0–1.3），
ASCII 按 4 字符 ≈ 1 token（BPE 英文经验值），混排相加。
`CALIBRATION` 记录这个假设，等线上拿到 usage 后用 §11 的脚本回归修正。
"""

from __future__ import annotations

import re

# 中日韩统一表意文字 + 假名 + 谚文：这些字符在主流 BPE 词表里基本 1 字 1 token。
_CJK = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\u3040-\u30ff\uac00-\ud7af]")

# 每条消息的固定开销（role 分隔符、模板标记），与 OpenAI 的 3–4 token/条一致。
_MESSAGE_OVERHEAD = 4
_REQUEST_OVERHEAD = 3

CALIBRATION = {
    "cjk_chars_per_token": 1.0,
    "ascii_chars_per_token": 4.0,
    "message_overhead": _MESSAGE_OVERHEAD,
}


def estimate_tokens(text: str | None) -> int:
    """估算一段文本的 token 数；空文本返回 0。"""
    if not text:
        return 0
    cjk = len(_CJK.findall(text))
    rest = len(text) - cjk
    tokens = cjk / CALIBRATION["cjk_chars_per_token"] + rest / CALIBRATION["ascii_chars_per_token"]
    return max(1, int(tokens + 0.5))


def estimate_messages(messages: list[dict[str, str]] | None) -> int:
    """估算一组 messages 的 token 数（含每条固定开销）。"""
    if not messages:
        return _REQUEST_OVERHEAD
    total = _REQUEST_OVERHEAD
    for message in messages:
        total += _MESSAGE_OVERHEAD
        total += estimate_tokens(str(message.get("content") or ""))
    return total


def estimate_chars_per_token(text: str | None) -> float:
    """反推「这一轮实际多少字符换一个 token」，用于校准与日志。"""
    if not text:
        return 0.0
    tokens = estimate_tokens(text)
    return len(text) / tokens if tokens else 0.0
