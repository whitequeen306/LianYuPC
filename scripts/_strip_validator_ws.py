#!/usr/bin/env python3
"""删掉 DashScopeCloudEndpointValidator 里的 Realtime WS 端点拼装。

实时语音已整体退役：这个 `realtimeWsUrl()` 是 Java 里**最后一处**能拼出
`wss://…/api-ws/v1/realtime` 的代码，留着它就是留一条能重新接上 AI WS 的路。
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VALIDATOR = ROOT / "backend/lianyu-service/src/main/java/com/lianyu/service/voice/DashScopeCloudEndpointValidator.java"
TEST = ROOT / "backend/lianyu-service/src/test/java/com/lianyu/service/voice/DashScopeCloudEndpointValidatorTest.java"

METHOD = '''    /**
     * Realtime TTS 的 WebSocket 端点。
     *
     * <p>2026-10-22 起<b>已随实时语音一起删除</b> —— 平台不再提供「支持复刻音色的
     * 实时 TTS 模型」，语音通话改走按句 SSE 流式合成（见 VoiceCallDuplexSession）。
     */
    public static String realtimeWsUrl(String base) {
        String normalized = normalizeBaseUrl(base);
        String host = URI.create(normalized).getHost();
        return "wss://" + host + "/api-ws/v1/realtime";
    }
'''

def main() -> None:
    text = VALIDATOR.read_text(encoding="utf-8")

    # 用正则删方法（注释块 + 方法体），容忍注释措辞
    pattern = re.compile(
        r"\n[ \t]*/\*\*(?:(?!\*/).)*?\*/\s*\n[ \t]*public static String realtimeWsUrl\(String base\)\s*\{"
        r"(?:(?!\n[ \t]*\}).)*?\n[ \t]*\}\n",
        re.S,
    )
    new_text, n = pattern.subn("\n", text)
    if n != 1:
        sys.exit(f"realtimeWsUrl 删除失败（命中 {n} 次）")
    VALIDATOR.write_text(new_text, encoding="utf-8")
    print("已删除 realtimeWsUrl()")

    # 测试里对应的断言也删掉
    ttext = TEST.read_text(encoding="utf-8")
    lines = ttext.splitlines(keepends=True)
    out, skipped = [], 0
    skip_until = None
    for i, line in enumerate(lines):
        if "realtimeWsUrl" in line:
            # 断言通常是 @Test 方法 2-3 行；连同上面的 @Test 一起删
            out.append("__DROP__")
            skipped += 1
            continue
        out.append(line)
    # 简化：整行含 realtimeWsUrl 的断言删掉后，可能留下空的 @Test 方法 —— 用正则清理
    joined = "".join(out)
    joined = re.sub(
        r"\n[ \t]*@Test\s*\n[ \t]*void [A-Za-z0-9_]+\(\)\s*\{\s*\n(?:\s*__DROP__\s*\n)+\s*\}\n",
        "\n", joined,
    )
    joined = re.sub(r"\n[ \t]*__DROP__[ \t]*\n", "\n", joined)
    if "__DROP__" in joined:
        sys.exit("测试文件清理不干净，请手工检查")
    TEST.write_text(joined, encoding="utf-8")
    print(f"测试里删掉 {skipped} 行 realtimeWsUrl 断言")
    print("  validator 残留 api-ws =", VALIDATOR.read_text(encoding='utf-8').count("api-ws"))


if __name__ == "__main__":
    main()
