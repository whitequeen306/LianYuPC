"""评测层：题集读错一行，指标就整体不可信——所以坏行要直接抛。"""

import json

import pytest

from app.memory import evaluation as ev


def _write(tmp_path, lines):
    path = tmp_path / "cases.jsonl"
    path.write_text("\n".join(json.dumps(line, ensure_ascii=False) for line in lines), encoding="utf-8")
    return path


def test_load_cases_reads_jsonl(tmp_path):
    path = _write(tmp_path, [
        {"query": "我上次说想养什么", "userId": 7, "characterId": 3, "expected": ["42"]},
        {"query": "她生日是哪天", "userId": 7, "characterId": 3, "expected": [9, "ep-2"]},
    ])

    cases = ev.load_cases(path)

    assert [case.query for case in cases] == ["我上次说想养什么", "她生日是哪天"]
    assert cases[1].expected == ("9", "ep-2")


def test_load_cases_rejects_broken_line(tmp_path):
    path = tmp_path / "bad.jsonl"
    path.write_text('{"query": "缺字段"}\n', encoding="utf-8")

    with pytest.raises(ValueError, match="不是合法评测题"):
        ev.load_cases(path)


def test_hit_at_and_reciprocal_rank():
    ranked = ["a", "b", "c"]

    assert ev.hit_at(ranked, ["b"], k=2)
    assert not ev.hit_at(ranked, ["c"], k=2)
    assert ev.reciprocal_rank(ranked, ["b"]) == 0.5
    assert ev.reciprocal_rank(ranked, ["z"]) == 0.0


def test_evaluate_aggregates_and_lists_misses():
    cases = [
        ev.Case("q1", 7, 3, ("a",)),
        ev.Case("q2", 7, 3, ("b",)),
        ev.Case("q3", 7, 3, ("z",)),
    ]
    ranked = {"q1": ["a", "x"], "q2": ["x", "b"], "q3": ["x", "y"]}

    report = ev.evaluate(cases, lambda case: ranked[case.query], k=2)

    assert report["n"] == 3
    assert report["recall@2"] == pytest.approx(2 / 3, abs=1e-4)
    assert report["mrr"] == pytest.approx((1 + 0.5 + 0) / 3, abs=1e-4)
    assert report["misses"] == [{"query": "q3", "expected": ["z"], "top": ["x", "y"]}]


def test_empty_case_set_reports_none_not_zero():
    report = ev.evaluate([], lambda case: [])

    assert report["n"] == 0
    assert report["recall@5"] is None
    assert "没有可评测的题" in ev.render_report(report)


def test_render_report_shows_headline_numbers():
    cases = [ev.Case("q1", 7, 3, ("a",))]
    report = ev.evaluate(cases, lambda case: ["a"], k=5)

    text = ev.render_report(report, k=5)

    assert "题数 1" in text
    assert "recall@5 100.00%" in text
    assert "MRR 1.000" in text


def test_compare_reports_wins_and_losses_per_case():
    from app.memory import evaluation as ev

    cases = [ev.Case("a", 7, 3, ("1",)), ev.Case("b", 7, 3, ("2",))]

    before = ev.evaluate(cases, lambda c: ["9"] if c.query == "a" else ["2"])
    after = ev.evaluate(cases, lambda c: ["1"] if c.query == "a" else ["9"])

    diff = ev.compare(before, after)

    assert diff["n"] == 2
    assert diff["recall_delta"] == 0.0
    assert diff["wins"] == ["a"]
    assert diff["losses"] == ["b"]


def test_compare_requires_identical_queries():
    from app.memory import evaluation as ev

    before = ev.evaluate([ev.Case("a", 7, 3, ("1",))], lambda c: ["1"])
    after = ev.evaluate([ev.Case("a 改了个字", 7, 3, ("1",))], lambda c: ["1"])

    diff = ev.compare(before, after)

    assert diff["n"] == 0
    assert "没有可对比" in ev.render_comparison(diff)


def test_compare_renders_delta_and_examples():
    from app.memory import evaluation as ev

    before = ev.evaluate([ev.Case("a", 7, 3, ("1",))], lambda c: ["9"])
    after = ev.evaluate([ev.Case("a", 7, 3, ("1",))], lambda c: ["1"])

    text = ev.render_comparison(ev.compare(before, after))

    assert "救回 1 条" in text
    assert "+ a" in text
