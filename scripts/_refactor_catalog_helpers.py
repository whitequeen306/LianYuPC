import re
from pathlib import Path

root = Path(__file__).resolve().parents[1] / "backend/lianyu-service/src/main/java/com/lianyu/service/square"
configs = {
    "CharacterSquareCatalogDal.java": "dal",
    "CharacterSquareCatalogGenshin.java": "genshin",
    "CharacterSquareCatalogBlueArchive.java": "bluearchive",
    "CharacterSquareCatalogReZero.java": "rezero",
    "CharacterSquareCatalogShizhong.java": "shizhong",
    "CharacterSquareCatalogDanganronpa.java": "danganronpa",
}
helper_block = re.compile(
    r"\n    private static List<CharacterSquareCatalog\.Tag> tags\(String lang, String personalityKey\) \{.*?"
    r"\n    private static CharacterSquareCatalog\.LocalePack pack\(\n"
    r"            String name, String summary, List<CharacterSquareCatalog\.Tag> tags, String prompt\) \{\n"
    r"        return new CharacterSquareCatalog\.LocalePack\(name, summary, tags, prompt\);\n"
    r"    \}\n",
    re.S,
)

for fname, franchise in configs.items():
    p = root / fname
    text = p.read_text(encoding="utf-8")
    text = text.replace("pack(", "CharacterSquareCatalog.localePack(")
    text = re.sub(
        r'tags\(("(?:\\.|[^"\\])*"),\s*',
        rf'CharacterSquareCatalog.franchiseTags(\1, "{franchise}", ',
        text,
    )
    text = helper_block.sub("\n", text)
    p.write_text(text, encoding="utf-8")
    print("updated", fname)
