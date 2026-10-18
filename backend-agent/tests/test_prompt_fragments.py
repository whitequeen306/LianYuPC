"""prompt 片段一致性测试（P5.5 防漂移闸门）。

三个断言：
1. 仓库里的 fragments.json 与「现在重新从 Java 源码生成」的结果**完全一致**
   —— Java 改了 prompt 但忘记重新生成时立刻失败
2. 关键片段的内容正确（四语种晚安模式、时间锚点、城市权威段）
3. 片段文本保留 Java 的去缩进结果（无行首多余空格、无结尾换行）
"""

import json
import subprocess
import sys
from pathlib import Path

import pytest

from app.prompt_fragments import FRAGMENTS_PATH, all_fragments, fragment, reload_fragments

ROOT = Path(__file__).resolve().parent.parent.parent
GENERATOR = ROOT / "scripts" / "extract-java-prompt-fragments.py"


@pytest.fixture(autouse=True)
def _clear_cache():
    reload_fragments()
    yield
    reload_fragments()


def test_fragments_file_exists():
    assert FRAGMENTS_PATH.exists(), "先运行 scripts/extract-java-prompt-fragments.py"


def test_regenerating_produces_identical_output(tmp_path):
    """防漂移：Java prompt 改动后未重新生成 → 这里失败。"""
    committed = json.loads(FRAGMENTS_PATH.read_text(encoding="utf-8"))

    before = FRAGMENTS_PATH.read_text(encoding="utf-8")
    try:
        subprocess.run(
            [sys.executable, str(GENERATOR)],
            cwd=str(ROOT),
            check=True,
            capture_output=True,
        )
        regenerated = FRAGMENTS_PATH.read_text(encoding="utf-8")
    finally:
        FRAGMENTS_PATH.write_text(before, encoding="utf-8")

    assert regenerated == before, (
        "fragments.json 与 Java 源码不一致：请重新运行 "
        "scripts/extract-java-prompt-fragments.py 并提交"
    )
    assert json.loads(regenerated) == committed


def test_goodnight_variants_present_for_four_languages():
    fragments = all_fragments()
    texts = list(fragments.values())

    assert any("おやすみモード" in t for t in texts), "缺 JA 晚安模式"
    assert any("Goodnight Mode" in t for t in texts), "缺 EN 晚安模式"
    assert any("晚安模式（最高優先）" in t for t in texts), "缺 ZH_TW 晚安模式"
    assert any("晚安模式（最高优先级）" in t for t in texts), "缺 ZH 晚安模式"


def test_time_anchor_fragment_present():
    texts = list(all_fragments().values())
    joined = "\n".join(texts)
    assert "当前真实环境（最高优先级 · 时间锚点）" in joined
    assert "禁止用对话历史推断" in joined


def test_city_authority_fragment_present():
    joined = "\n".join(all_fragments().values())
    assert "用户当前所在现实城市（权威）" in joined
    assert "不得沿用历史里的旧城市" in joined or "不要沿用历史里的旧城市" in joined


def test_fragments_have_no_leading_indent_and_no_trailing_newline():
    for (source, line), text in all_fragments().items():
        assert not text.endswith("\n"), f"{source}:{line} 不应以换行结尾（Java text block 规则）"
        for row in text.split("\n"):
            if row:
                assert not row.startswith("    "), f"{source}:{line} 仍有未去缩进的行"


def test_unknown_fragment_raises_with_hint():
    with pytest.raises(KeyError) as exc:
        fragment("Nope.java", 1)
    assert "available" in str(exc.value)


def test_fragment_lookup_by_source_and_line():
    text = fragment("ChatTurnPromptAssembler.java", 165)
    assert "硬性要求" in text
