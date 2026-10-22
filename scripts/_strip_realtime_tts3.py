#!/usr/bin/env python3
"""第三轮：删掉剩余的实时调用点（prewarm / releaseTurnTts / ttsReadyAtMs 复位）。"""
from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "backend/lianyu-service/src/main/java/com/lianyu/service/voice/VoiceCallDuplexSession.java"

REMOVALS = [
    ('        prewarmRealtimeTts("call-start");\n', ""),
    ('        prewarmRealtimeTts("barge-in");\n', ""),
    ("        ttsReadyAtMs = 0L;\n", ""),
    ("        releaseTurnTts();\n", ""),
]

def main() -> None:
    text = TARGET.read_text(encoding="utf-8")
    for old, new in REMOVALS:
        count = text.count(old)
        if count != 1:
            sys.exit(f"替换失败（命中 {count} 次）：{old.strip()!r}")
        text = text.replace(old, new)
    TARGET.write_text(text, encoding="utf-8")
    print("已清理剩余调用点")
    for token in ("prewarmRealtimeTts", "releaseTurnTts", "ttsReadyAtMs", "markTtsReady"):
        print(f"  残留 {token} = {text.count(token)}")


if __name__ == "__main__":
    main()
