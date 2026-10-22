#!/usr/bin/env python3
"""第二轮：删掉只服务于实时 WS 的句子切分逻辑与相关字段。

上一轮删了方法级实时逻辑，但 `drainSentenceBuf*`（把 LLM delta 切成句子喂给 WS）
以及它用的 commitSeq / sentenceBuf / firstCommitSent 还在。它们现在没有任何调用者，
留着就是"看不见的死代码"——正是这次清理要消灭的东西。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "backend/lianyu-service/src/main/java/com/lianyu/service/voice/VoiceCallDuplexSession.java"

METHODS = [
    "drainSentenceBuf",
    "drainSentenceBufLocked",
    "nextCommitEnd",
    "indexOfAnyFrom",
]


def cut_method(text: str, name: str) -> tuple[str, bool]:
    match = re.search(rf"\n([ \t]*)(?:private|protected|public|static)[^\n]*\b{name}\s*\(", text)
    if not match:
        return text, False
    prefix = text[: match.start() + 1]
    brace = text.index("{", match.end())
    depth = 0
    i = brace
    while i < len(text):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                break
        i += 1
    if depth != 0:
        sys.exit(f"{name} 花括号不配对")
    end = i + 1
    while end < len(text) and text[end] == "\n":
        end += 1
    # 连带前置的注释块
    lines = prefix.splitlines(keepends=True)
    while lines and lines[-1].strip().startswith(("*", "/*", "//")):
        lines.pop()
    return "".join(lines) + text[end:], True


def main() -> None:
    text = TARGET.read_text(encoding="utf-8")
    before = len(text)

    for name in METHODS:
        text, ok = cut_method(text, name)
        print(f"{'删除' if ok else '不存在'} 方法 {name}")

    removals = [
        # 线程池只给实时连接用
        ("    private static final ExecutorService TTS_CONNECT_EXECUTOR = Executors.newCachedThreadPool(r -> {\n"
         "        Thread thread = new Thread(r, \"voice-tts-connect\");\n"
         "        thread.setDaemon(true);\n"
         "        return thread;\n"
         "    });\n\n", ""),
        ("    private final AtomicLong commitSeq = new AtomicLong();\n", ""),
        ("    private final StringBuilder sentenceBuf = new StringBuilder();\n", ""),
        ("    /** guarded by {@link #sentenceLock} */\n    private boolean firstCommitSent;\n", ""),
        # 回合开始时的复位（只与实时句子缓冲有关）
        ("                synchronized (sentenceLock) {\n"
         "                    sentenceBuf.setLength(0);\n"
         "                    llmEmitBuf.setLength(0);\n"
         "                    firstCommitSent = false;\n"
         "                }\n"
         "                commitSeq.set(0);\n",
         "                synchronized (sentenceLock) {\n"
         "                    llmEmitBuf.setLength(0);\n"
         "                }\n"),
    ]
    for old, new in removals:
        count = text.count(old)
        if count != 1:
            sys.exit(f"替换失败（命中 {count} 次）：{old.splitlines()[0][:70]!r}")
        text = text.replace(old, new)

    TARGET.write_text(text, encoding="utf-8")
    print(f"\n{before} -> {len(text)} 字符（减少 {before - len(text)}）")
    for token in ("DashScopeTtsRealtimeService", "ttsSession", "ttsFailed",
                  "sentenceBuf", "firstCommitSent", "commitSeq", "TTS_CONNECT_EXECUTOR"):
        print(f"  残留 {token} = {text.count(token)}")


if __name__ == "__main__":
    main()
