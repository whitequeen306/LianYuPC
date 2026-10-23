"""工具提示文案（原 `ToolManager.buildToolsPromptHint`）。

## 为什么用「按开关键基线」而不是拼接字符串

Java 侧曾是叠加式拼接（base 两行 + 4 个可选行 + 电脑操控行），直接手写拼接
极容易在换行/顺序上漂移。这里用 32 种开关组合作为唯一事实来源：Python 只做**查表**。

`tool_hints_golden.json` 现在是**手维护的数据文件**：Java 的 `buildToolsPromptHint`
与 `ToolHintGoldenTest` 已随「Java 零 AI 逻辑」一起删除，没有第二个生成器。
改文案直接改 JSON，`tests/test_tool_hints.py` 会校验查表结果与基线一致。

第五位 `computer` 的语义已随能力层重构改变：
**从「桥上有 computer_task」改为「该用户桌面能力在线」**，
对应文案教模型使用 `desk_*` 一组原子能力（粗粒度 `computer_task` 已退役）。
"""

from __future__ import annotations

import json
from functools import lru_cache
from pathlib import Path
from typing import Any

GOLDEN_PATH = Path(__file__).resolve().parent / "prompts" / "tool_hints_golden.json"


@lru_cache(maxsize=1)
def _cases() -> dict[str, str]:
    payload = json.loads(GOLDEN_PATH.read_text(encoding="utf-8"))
    return {key: value.get("hint", "") for key, value in payload["cases"].items()}


def flag_key(
    *,
    chat: bool,
    memory: bool,
    recent: bool,
    diary: bool,
    computer: bool,
) -> str:
    """与 Java golden 的 key 顺序一致：chat,memory,recent,diary,computer。"""
    return "".join("1" if flag else "0" for flag in (chat, memory, recent, diary, computer))


def build_tool_hint(
    *,
    chat: bool,
    memory: bool,
    recent: bool,
    diary: bool,
    computer: bool,
) -> str:
    """返回与 Java `buildToolsPromptHint` 逐字节一致的文案。"""
    return _cases().get(flag_key(chat=chat, memory=memory, recent=recent,
                                 diary=diary, computer=computer), "")


def build_from_flags(flags: dict[str, Any] | None) -> str:
    """从请求里的 flags 对象构建；缺字段按 false 处理。"""
    if not flags:
        return ""
    return build_tool_hint(
        chat=bool(flags.get("chat")),
        memory=bool(flags.get("memory")),
        recent=bool(flags.get("recent")),
        diary=bool(flags.get("diary")),
        computer=bool(flags.get("computer")),
    )


__all__ = ["build_from_flags", "build_tool_hint", "flag_key"]
