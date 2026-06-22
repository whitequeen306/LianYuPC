"""Remove broken leftover pack/tags helpers from franchise catalog files."""
import re
from pathlib import Path

root = Path(__file__).resolve().parents[1] / "backend/lianyu-service/src/main/java/com/lianyu/service/square"
pattern = re.compile(
    r"\n    private static List<CharacterSquareCatalog\.Tag> tags\(String lang, String personalityKey\) \{.*?"
    r"\n    private static CharacterSquareCatalog\.LocalePack CharacterSquareCatalog\.localePack\(\n"
    r"            String name, String summary, List<CharacterSquareCatalog\.Tag> tags, String prompt\) \{\n"
    r"        return new CharacterSquareCatalog\.LocalePack\(name, summary, tags, prompt\);\n"
    r"    \}\n",
    re.S,
)

for path in sorted(root.glob("CharacterSquareCatalog*.java")):
    if path.name == "CharacterSquareCatalog.java":
        continue
    text = path.read_text(encoding="utf-8")
    new_text, n = pattern.subn("\n", text)
    if n:
        path.write_text(new_text, encoding="utf-8")
        print(f"fixed {path.name} ({n} block(s))")
    elif "CharacterSquareCatalog.localePack(" in text and "private static CharacterSquareCatalog.LocalePack CharacterSquareCatalog" in text:
        print(f"still broken: {path.name}")
