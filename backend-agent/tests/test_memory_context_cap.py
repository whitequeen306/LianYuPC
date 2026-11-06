"""记忆块总预算：宁可少说两件旧事，也不能把最近对话挤出去。"""

from __future__ import annotations

from app.prompt_pipeline import _cap_memory_context


def test_short_context_passes_through():
    text = "【当前场景】话题：加班"

    assert _cap_memory_context(text, 100) == text


def test_long_context_is_trimmed_to_budget():
    text = "【长期记忆/姓名】她叫阿澈。" * 200

    capped = _cap_memory_context(text, 120)

    assert len(capped) < len(text)
    assert capped.endswith("…")


def test_zero_limit_disables_the_cap():
    text = "很久以前的事。" * 100

    assert _cap_memory_context(text, 0) == text


def test_empty_context_stays_empty():
    assert _cap_memory_context("", 50) == ""


def test_cap_reads_default_from_settings(monkeypatch):
    from app.context.tokens import estimate_tokens
    from app.prompt_pipeline import _cap_memory_context as cap

    class _Settings:
        memory_context_max_tokens = 60

    monkeypatch.setattr("app.config.get_settings", lambda: _Settings())

    text = "她说想去看海。" * 100
    capped = cap(text)

    assert len(capped) < len(text)
    assert estimate_tokens(capped) <= 60
