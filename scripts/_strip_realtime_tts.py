#!/usr/bin/env python3
"""从 VoiceCallDuplexSession 里精确删除实时 TTS 遗留（方法级，按花括号配对）。

为什么要脚本：这个文件 697 行，实时逻辑散落在字段/构造器/回合流程/私有方法四类位置。
手工整份重写风险太高；按名字删方法 + 定点替换字段与调用点，再靠编译器和测试兜底。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "backend/lianyu-service/src/main/java/com/lianyu/service/voice/VoiceCallDuplexSession.java"

# 只被实时 WS 使用的方法，整块删除
METHODS = [
    "connectRealtimeTts",
    "prewarmRealtimeTts",
    "awaitRealtimeBriefly",
    "flushRealtimeTts",
    "feedRealtimeTts",
    "releaseTurnTts",
    "markTtsReady",
    "noRealtimeVoice",
]


def cut_method(text: str, name: str) -> tuple[str, bool]:
    """删掉 `private ... name(...) { ... }` 整个方法（含前导注释行）。"""
    match = re.search(rf"\n([ \t]*)(?:private|protected|public)[^\n]*\b{name}\s*\(", text)
    if not match:
        return text, False
    start = match.start() + 1  # 跳过换行，保留文件结构
    # 向前吞掉紧邻的注释块（/** ... */ 或 // 行），它们只描述这个方法
    prefix = text[:start]
    prefix_lines = prefix.splitlines(keepends=True)
    removed = 0
    while prefix_lines:
        last = prefix_lines[-1].strip()
        if last.startswith("*") or last.startswith("/*") or last.startswith("//"):
            prefix_lines.pop()
            removed += 1
        else:
            break
    prefix = "".join(prefix_lines)
    start = len(prefix)

    # 从方法签名的左花括号开始做配对
    brace = text.index("{", match.end())
    depth = 0
    i = brace
    while i < len(text):
        ch = text[i]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                break
        i += 1
    if depth != 0:
        sys.exit(f"方法 {name} 花括号不配对")
    end = i + 1
    # 吃掉方法后的空行
    while end < len(text) and text[end] == "\n":
        end += 1
    return prefix + text[end:], True


def main() -> None:
    text = TARGET.read_text(encoding="utf-8")
    original_len = len(text)

    for name in METHODS:
        text, ok = cut_method(text, name)
        print(f"{'删除' if ok else '未找到'} 方法 {name}")
        if not ok:
            sys.exit(f"预期存在的方法 {name} 没找到，中止")

    # 字段与构造器
    removals = [
        ("import com.lianyu.service.ai.DashScopeTtsRealtimeService;\n", ""),
        ("    private final DashScopeTtsRealtimeService ttsRealtimeService;\n", ""),
        ("    private final AtomicBoolean ttsFailed = new AtomicBoolean(false);\n", ""),
        ("    private final AtomicReference<DashScopeTtsRealtimeService.Session> ttsSession = new AtomicReference<>();\n", ""),
        ("    private final AtomicReference<CompletableFuture<DashScopeTtsRealtimeService.Session>> ttsConnectFuture =\n            new AtomicReference<>();\n", ""),
        ("    private final AtomicLong ttsGeneration = new AtomicLong();\n", ""),
        ("    private volatile long ttsReadyAtMs;\n", ""),
        ("            DashScopeTtsRealtimeService ttsRealtimeService,\n", ""),
        ("        this.ttsRealtimeService = ttsRealtimeService;\n", ""),
    ]
    for old, new in removals:
        if text.count(old) != 1:
            sys.exit(f"字段替换失败（命中 {text.count(old)} 次）：{old[:70]!r}")
        text = text.replace(old, new)

    # 调用点
    calls = [
        ('                if (!target.isLocal()) {\n'
         '                    DashScopeTtsRealtimeService.Session ready = ttsSession.get();\n'
         '                    if (ready != null && ready.isClosed()) {\n'
         '                        ttsSession.compareAndSet(ready, null);\n'
         '                        ready = null;\n'
         '                    }\n'
         '                    if (ready == null && ttsConnectFuture.get() == null) {\n'
         '                        prewarmRealtimeTts("turn-start");\n'
         '                    } else if (ready != null && ready.isReady()) {\n'
         '                        markTtsReady();\n'
         '                    }\n'
         '                }\n'
         '\n', ""),
        ('                ttsFailed.set(false);\n', ""),
        ('                    if (!target.isLocal()) {\n'
         '                        feedRealtimeTts(delta);\n'
         '                    }\n', ""),
        ('                releaseTurnTts();\n', ""),
        ('                if (!target.isLocal()) {\n'
         '                    prewarmRealtimeTts("after-turn");\n'
         '                }\n', ""),
    ]
    for old, new in calls:
        if text.count(old) != 1:
            sys.exit(f"调用点替换失败（命中 {text.count(old)} 次）：{old[:80]!r}")
        text = text.replace(old, new)

    # 回合结束的 TTS 分支：实时分支删掉，只留流式
    branch_old = (
        '                } else {\n'
        '                    DashScopeTtsRealtimeService.Session tts = ttsSession.get();\n'
        '                    if ((tts == null || !tts.isReady()) && !audioSent.get() && !noRealtimeVoice()) {\n'
        '                        awaitRealtimeBriefly();\n'
        '                        tts = ttsSession.get();\n'
        '                    }\n'
        '                    if (tts != null && !ttsFailed.get()) {\n'
        '                        flushRealtimeTts();\n'
        '                        tts.finish();\n'
        '                        tts.awaitFinished(45_000);\n'
        '                    } else if (!audioSent.get()) {\n'
        '                        ttsFailed.set(true);\n'
        '                        synchronized (sentenceLock) {\n'
        '                            sentenceBuf.setLength(0);\n'
        '                        }\n'
        '                        log.info("Voice duplex TTS HTTP streaming mode={} pet={} conv={} elapsedMs={}",\n'
        '                                target.mode(), target.petId(), conversationId,\n'
        '                                System.currentTimeMillis() - turnStartedAtMs);\n'
        '                        streamHttpFallback(spoken);\n'
        '                    } else {\n'
        '                        log.warn("Voice duplex realtime TTS failed after audio started mode={} pet={} conv={}",\n'
        '                                target.mode(), target.petId(), conversationId);\n'
        '                    }\n'
        '                }\n'
    )
    branch_new = (
        '                } else {\n'
        '                    // 语音通话只有一条合成路径：按块流式（cosyvoice-v3-flash + 复刻音色）。\n'
        '                    // 实时 WS 那条路已于 2026-10-22 随 DashScopeTtsRealtimeService 一起删除：\n'
        '                    // 平台不再提供「支持复刻音色的实时 TTS 模型」。\n'
        '                    log.info("Voice duplex TTS streaming mode={} pet={} conv={} elapsedMs={}",\n'
        '                            target.mode(), target.petId(), conversationId,\n'
        '                            System.currentTimeMillis() - turnStartedAtMs);\n'
        '                    streamHttpFallback(spoken);\n'
        '                }\n'
    )
    if text.count(branch_old) != 1:
        sys.exit(f"回合 TTS 分支替换失败（命中 {text.count(branch_old)} 次）")
    text = text.replace(branch_old, branch_new)

    TARGET.write_text(text, encoding="utf-8")
    print(f"\n{original_len} 字符 -> {len(text)} 字符（减少 {original_len - len(text)}）")
    print("残留 DashScopeTtsRealtimeService =", text.count("DashScopeTtsRealtimeService"))
    print("残留 ttsSession =", text.count("ttsSession"))
    print("残留 ttsFailed =", text.count("ttsFailed"))


if __name__ == "__main__":
    main()
