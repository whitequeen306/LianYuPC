"""装配器：预算内先截段落，再按优先级整段丢。"""

from app.context import assembler
from app.context.tokens import estimate_tokens


def test_truncate_keeps_short_text_untouched():
    assert assembler.truncate_to_tokens("她叫阿岚", 10) == "她叫阿岚"
    assert assembler.truncate_to_tokens("", 10) == ""
    assert assembler.truncate_to_tokens("abc", 0) == ""


def test_truncate_respects_token_budget_for_cjk():
    text = "记" * 50

    clipped = assembler.truncate_to_tokens(text, 20)

    assert clipped.endswith("…")
    assert estimate_tokens(clipped) <= 20


def test_fit_sections_truncates_only_the_offending_slot():
    sections = {
        "product": "安全边界",
        "persona": "她叫阿岚",
        "recall": "记" * 900,
    }

    fitted, report = assembler.fit_sections(sections, total_tokens=3460)

    assert fitted["product"] == "安全边界"
    assert fitted["persona"] == "她叫阿岚"
    assert report["truncated"] == ["recall"]
    assert estimate_tokens(fitted["recall"]) <= 400
    assert report["dropped"] == []


def test_fit_sections_drops_by_priority_until_it_fits():
    sections = {
        "product": "产" * 180,
        "persona": "人" * 500,
        "recent": "近" * 1400,
        "archive": "档" * 300,
    }

    fitted, report = assembler.fit_sections(sections, total_tokens=1500)

    assert "archive" in report["dropped"]
    assert "recent" in report["dropped"]   # 丢掉导航还不够，才轮到最近原文
    assert "product" in fitted            # 产品设定永远最后丢
    assert report["tokens_after"] <= 1500


def test_fit_sections_ignores_empty_blocks():
    fitted, report = assembler.fit_sections({"product": "", "persona": "她"})

    assert fitted == {"persona": "她"}
    assert report["truncated"] == [] and report["dropped"] == []
