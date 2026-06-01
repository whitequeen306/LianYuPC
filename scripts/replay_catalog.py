import json
from pathlib import Path


def apply_str(content: str, old: str, new: str) -> tuple[str, bool]:
    if old not in content:
        return content, False
    return content.replace(old, new, 1), True


def main():
    transcript = Path(
        r"C:\Users\hp\.cursor\projects\c-Users-hp-Desktop-LianYu-PC\agent-transcripts"
        r"\265c33bf-49b8-40f4-9918-62e4022bb33f\265c33bf-49b8-40f4-9918-62e4022bb33f.jsonl"
    )
    target_suffix = "CharacterSquareCatalog.java"
    content = None
    ops = []
    for line in transcript.open(encoding="utf-8"):
        try:
            o = json.loads(line)
        except Exception:
            continue
        for part in (o.get("message") or {}).get("content") or []:
            if not isinstance(part, dict):
                continue
            inp = part.get("input")
            if not isinstance(inp, dict):
                continue
            p = inp.get("path", "")
            if not p.endswith(target_suffix):
                continue
            name = part.get("name")
            if name == "Write" and "contents" in inp:
                content = inp["contents"]
                ops.append("Write")
            elif name == "StrReplace":
                ops.append(("StrReplace", inp.get("old_string"), inp.get("new_string")))

    if content is None:
        raise SystemExit("NO BASE WRITE")
    applied = skipped = 0
    for op in ops:
        if op == "Write":
            continue
        _, old, new = op
        content, ok = apply_str(content, old, new)
        if ok:
            applied += 1
        else:
            skipped += 1
    out = Path(
        r"c:\Users\hp\Desktop\LianYu-PC\backend\lianyu-service\src\main\java"
        r"\com\lianyu\service\CharacterSquareCatalog.java"
    )
    out.write_text(content, encoding="utf-8", newline="\n")
    print("applied", applied, "skipped", skipped, "lines", content.count("\n") + 1)
    print("genshin", "CharacterSquareCatalogGenshin" in content)
    print("white_queen", "white_queen" in content)


if __name__ == "__main__":
    main()
