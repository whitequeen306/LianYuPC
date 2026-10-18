"""从 Java 源码抽取 prompt 文本块，生成 Python 侧可直接使用的 JSON。

为什么用生成器而不是手抄：这些是中文长文本，手抄必然出现全角/半角、
空格、标点差异；一旦有差异，Python 与 Java 的 system prompt 就不一致，
而这类不一致不会报错，只会让「AI 人设悄悄变了」。

Java text block 规则（本脚本实现的部分）：
1. 开头的 `\"\"\"` 之后必须是换行，内容从下一行开始
2. 行尾的 `\"\"\"` 若独占一行，最后一个换行不计入内容
3. 去掉所有非空行与结尾定界行的**公共缩进**

未实现也不需要的部分：`\\` 续行、`\\s` 转义（本仓库的 prompt 块里没有用到；
脚本检测到会直接报错，避免静默漏转义）。

用法：
    uv run python scripts/extract-java-prompt-fragments.py
输出：
    backend-agent/app/prompts/fragments.json
"""

from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JAVA_ROOT = ROOT / "backend"
OUT = ROOT / "backend-agent" / "app" / "prompts" / "fragments.json"

# 这些文件里含 prompt 文本块
TARGETS = [
    "lianyu-service/src/main/java/com/lianyu/service/graph/ChatTurnPromptAssembler.java",
    "lianyu-service/src/main/java/com/lianyu/service/ai/CharacterPromptBuilder.java",
]

_BLOCK_OPEN = '"""'


def strip_java_text_block(raw: str) -> str:
    """按 Java 规则去掉公共缩进与结尾换行。"""
    if "\\s" in raw or re.search(r"\\\s*$", raw, re.M):
        raise ValueError("unsupported escape (\\s or line continuation) in prompt block")

    lines = raw.split("\n")
    # 结尾定界行独占一行时：只去掉它的缩进，**保留其前面的换行**
    # （JLS 3.10.6：内容到定界行开头为止，换行属于内容；行首空白由缩进处理去掉）
    if lines and lines[-1].strip() == "":
        lines[-1] = ""

    indents = [
        len(line) - len(line.lstrip())
        for line in lines
        if line.strip()
    ]
    if not indents:
        return ""
    common = min(indents)
    return "\n".join(line[common:] if line.strip() else "" for line in lines)


def extract(path: Path) -> list[dict]:
    text = path.read_text(encoding="utf-8")
    blocks: list[dict] = []
    idx = 0
    while True:
        start = text.find(_BLOCK_OPEN, idx)
        if start < 0:
            break
        body_start = text.find("\n", start)
        if body_start < 0:
            break
        end = text.find(_BLOCK_OPEN, body_start)
        if end < 0:
            break
        raw = text[body_start + 1:end]
        line_no = text[:start].count("\n") + 1
        blocks.append({
            "file": path.name,
            "line": line_no,
            "text": strip_java_text_block(raw),
        })
        idx = end + len(_BLOCK_OPEN)
    return blocks


def main() -> int:
    collected: list[dict] = []
    for rel in TARGETS:
        path = JAVA_ROOT / rel
        if not path.exists():
            print(f"skip (missing): {rel}")
            continue
        for block in extract(path):
            block["source"] = rel
            collected.append(block)

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(
        json.dumps({"blocks": collected}, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(f"wrote {len(collected)} blocks -> {OUT.relative_to(ROOT)}")
    for block in collected:
        head = block["text"].split("\n")[0][:40]
        print(f"  {block['file']}:{block['line']}  {len(block['text']):4d} chars  {head}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
