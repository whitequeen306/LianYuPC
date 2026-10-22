#!/usr/bin/env python3
"""删掉 realtimeWsUrl（花括号配对法）及其测试断言。"""
from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VALIDATOR = ROOT / "backend/lianyu-service/src/main/java/com/lianyu/service/voice/DashScopeCloudEndpointValidator.java"
TEST = ROOT / "backend/lianyu-service/src/test/java/com/lianyu/service/voice/DashScopeCloudEndpointValidatorTest.java"


def cut_method(text: str, name: str) -> tuple[str, bool]:
    match = re.search(rf"\n([ \t]*)(?:private|protected|public)[^\n]*\b{name}\s*\(", text)
    if not match:
        return text, False
    prefix = text[: match.start() + 1]
    brace = text.index("{", match.end())
    depth, i = 0, brace
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
    lines = prefix.splitlines(keepends=True)
    while lines and lines[-1].strip().startswith(("*", "/*", "//")):
        lines.pop()
    return "".join(lines) + text[end:], True


def main() -> None:
    text = VALIDATOR.read_text(encoding="utf-8")
    text, ok = cut_method(text, "realtimeWsUrl")
    if not ok:
        sys.exit("没找到 realtimeWsUrl")
    VALIDATOR.write_text(text, encoding="utf-8")
    print("已删除 realtimeWsUrl()；api-ws 残留 =", text.count("api-ws"))

    # 测试：删掉含 realtimeWsUrl 的语句（含跨行到右括号），再清掉变空的 @Test 方法
    ttext = TEST.read_text(encoding="utf-8")
    ttext = re.sub(
        r"\n[ \t]*assertEquals\(\s*\"wss://[^\"]*\",\s*\n?[ \t]*DashScopeCloudEndpointValidator\.realtimeWsUrl\([^)]*\)\);\n",
        "\n", ttext,
    )
    ttext = re.sub(r"\n[ \t]*@Test\s*\n[ \t]*void [A-Za-z0-9_]+\(\)\s*\{\s*\n\s*\}\n", "\n", ttext)
    TEST.write_text(ttext, encoding="utf-8")
    print("测试残留 realtimeWsUrl =", ttext.count("realtimeWsUrl"))
    print("测试残留 api-ws  =", ttext.count("api-ws"))


if __name__ == "__main__":
    main()
