"""Java prompt 片段加载器（P5.5 第一步）。

`fragments.json` 由 `scripts/extract-java-prompt-fragments.py` 从 Java 源码生成，
不是手抄的 —— 中文长文本手抄必然出现标点/空格差异，而差异不会报错，
只会让 Python 与 Java 的 system prompt 悄悄不一致。

用法：
    from app.prompt_fragments import fragment
    fragment("ChatTurnPromptAssembler.java", 165)   # 按来源文件 + 起始行定位

**为什么按行号定位**：行号会随 Java 改动漂移，因此
`tests/test_prompt_fragments.py` 会校验「重新生成的结果与仓库里的 JSON 一致」，
Java 一改而没重新生成，测试立刻红。
"""

from __future__ import annotations

import json
from functools import lru_cache
from pathlib import Path
from typing import Any

FRAGMENTS_PATH = Path(__file__).resolve().parent / "prompts" / "fragments.json"


@lru_cache(maxsize=1)
def _load() -> dict[tuple[str, int], str]:
    if not FRAGMENTS_PATH.exists():
        raise FileNotFoundError(
            f"{FRAGMENTS_PATH} missing; run: uv run python scripts/extract-java-prompt-fragments.py"
        )
    payload: dict[str, Any] = json.loads(FRAGMENTS_PATH.read_text(encoding="utf-8"))
    return {
        (block["file"], int(block["line"])): block["text"]
        for block in payload.get("blocks") or []
    }


def fragment(source_file: str, line: int) -> str:
    """取某个 Java 文本块（已按 Java 规则去缩进）。"""
    key = (source_file, int(line))
    try:
        return _load()[key]
    except KeyError as exc:
        available = sorted(_load().keys())
        raise KeyError(f"fragment {key} not found; available: {available}") from exc


def all_fragments() -> dict[tuple[str, int], str]:
    return dict(_load())


def reload_fragments() -> None:
    """测试用：丢弃缓存。"""
    _load.cache_clear()


__all__ = ["FRAGMENTS_PATH", "all_fragments", "fragment", "reload_fragments"]
