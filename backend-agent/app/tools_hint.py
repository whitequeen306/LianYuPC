"""工具提示文案（原 `ToolManager.buildToolsPromptHint`）。

## 为什么用「按开关键基线」而不是拼接字符串

Java 侧的分支是叠加式拼接（base 两行 + 4 个可选行 + computer_task 行），
直接手写拼接逻辑极容易在换行/顺序上漂移。这里用 Java 真实执行生成的
32 种开关组合作为唯一事实来源：Python 只做**查表**，字节必然一致。

`tool_hints_golden.json` 由 `ToolHintGoldenTest` 生成，改 Java 文案后必须重新生成，
测试 `test_tool_hints.py` 会校验基线与 Python 加载结果一致。
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
