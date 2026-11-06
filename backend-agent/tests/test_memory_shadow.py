"""影子对比的语义：以旧路径为基准，看 v3 丢没丢记忆。"""

from app.memory import shadow


def test_top_ids_keeps_order_and_normalises_types():
    rows = [{"id": 9}, {"id": None}, {"id": "ep-4"}, {"id": 11}]

    assert shadow.top_ids(rows, k=3) == ["9", "ep-4", "11"]


def test_overlap_is_zero_when_nothing_matches():
    legacy = [{"id": 1}, {"id": 2}]
    v3 = [{"id": 3}, {"id": 4}]

    assert shadow.overlap_at(legacy, v3) == 0.0


def test_overlap_counts_shared_ids_against_legacy_baseline():
    legacy = [{"id": 1}, {"id": 2}, {"id": 3}, {"id": 4}]
    v3 = [{"id": 4}, {"id": 1}, {"id": 99}]

    assert shadow.overlap_at(legacy, v3, k=4) == 0.5
    assert shadow.compare(legacy, v3, k=4)["missing"] == ["2", "3"]


def test_empty_legacy_is_not_a_regression():
    """旧路径本来没记忆，就没有"丢"这回事——返回 1.0，避免日志天天报警。"""
    assert shadow.overlap_at([], [{"id": 1}]) == 1.0
    assert shadow.compare([], []) == {"k": 5, "legacy": [], "v3": [], "overlap": 1.0, "missing": []}
