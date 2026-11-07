"""本地 cross-encoder 精排：纯函数与降级路径。"""

from __future__ import annotations

import asyncio

import pytest

from app.config import get_settings
from app.memory import local_rerank


def _rows(scores: list[float]) -> list[dict]:
    return [
        {"id": str(index), "summary": f"第 {index} 条记忆", "score": score}
        for index, score in enumerate(scores)
    ]


@pytest.fixture(autouse=True)
def _reset(monkeypatch, tmp_path):
    local_rerank.reset()
    monkeypatch.setenv("LIANYU_AGENT_LIKE_UNUSED", "1")
    get_settings.cache_clear()
    settings = get_settings()
    # 默认把「模型装了 + 开关开」当作前提，用例要关时自己再改
    monkeypatch.setattr(settings, "memory_rerank_local_enabled", True, raising=False)
    monkeypatch.setattr(settings, "local_rerank_model_dir", str(tmp_path), raising=False)
    monkeypatch.setattr(settings, "local_rerank_max_candidates", 6, raising=False)
    monkeypatch.setattr(settings, "local_rerank_weight", 0.5, raising=False)
    monkeypatch.setattr(settings, "local_rerank_timeout_seconds", 0.12, raising=False)
    monkeypatch.setattr("app.memory.local_rerank.get_settings", lambda: settings)
    yield
    local_rerank.reset()


def _install_model(tmp_path, *, graph: bool = True) -> None:
    if graph:
        (tmp_path / "model_quantized.onnx").write_bytes(b"\x00" * 32)
    (tmp_path / "tokenizer.json").write_text("{}", encoding="utf-8")


def test_blend_is_monotonic_and_bounded():
    # 各归一后按 (1-w)*base + w*ce 混合；w=0.25 时 base 占主导但仍被精排拉开
    mixed = local_rerank.blend([0.9, 0.5, 0.1], [-2.0, 0.0, 2.0], 0.25)
    assert mixed == [0.75, 0.5, 0.25]


def test_blend_keeps_base_order_when_weight_is_zero():
    base = [0.9, 0.5, 0.1]
    mixed = local_rerank.blend(base, [2.0, 0.0, -2.0], 0.0)
    assert mixed[0] > mixed[1] > mixed[2]


def test_blend_handles_flat_scores():
    assert local_rerank.blend([0.4, 0.4], [1.0, 2.0], 0.5) == [0.25, 0.75]
    assert local_rerank.blend([0.4, 0.4, 0.4], [1.0, 1.0, 1.0], 0.5) == [0.5, 0.5, 0.5]


def test_is_configured_needs_flag_and_files(tmp_path):
    _install_model(tmp_path)
    assert local_rerank.is_configured() is True
    (tmp_path / "model_quantized.onnx").unlink()
    assert local_rerank.is_configured() is False


def test_is_configured_is_false_when_switch_off(tmp_path, monkeypatch):
    _install_model(tmp_path)
    monkeypatch.setattr(get_settings(), "memory_rerank_local_enabled", False, raising=False)
    assert local_rerank.is_configured() is False


def test_rows_are_untouched_when_switch_off(tmp_path, monkeypatch):
    _install_model(tmp_path)
    monkeypatch.setattr(get_settings(), "memory_rerank_local_enabled", False, raising=False)
    rows = _rows([0.9, 0.5, 0.1])
    out = asyncio.run(local_rerank.rerank_rows("猫叫什么", rows))
    assert out == rows
    assert all("_ce" not in row for row in out)


def test_cross_encoder_order_wins_and_score_is_written_back(tmp_path, monkeypatch):
    _install_model(tmp_path)
    monkeypatch.setattr(local_rerank, "score_sync", lambda query, docs: [2.0, -2.0, 0.0])
    rows = _rows([0.1, 0.5, 0.9])
    out = asyncio.run(local_rerank.rerank_rows("猫叫什么", rows, weight=0.75))
    assert [row["id"] for row in out] == ["0", "2", "1"]
    # 混合分必须映回原量纲：MMR 只看 row["score"]，不改分等于白排。
    assert out[0]["score"] > out[1]["score"] > out[2]["score"]
    assert [round(row["_ce"], 1) for row in out] == [2.0, 0.0, -2.0]


def test_only_head_is_reranked_and_tail_keeps_order(tmp_path, monkeypatch):
    _install_model(tmp_path)
    monkeypatch.setattr(get_settings(), "local_rerank_max_candidates", 2, raising=False)
    monkeypatch.setattr(local_rerank, "score_sync", lambda query, docs: [-1.0, 1.0])
    rows = _rows([0.9, 0.5, 0.4, 0.3])
    out = asyncio.run(local_rerank.rerank_rows("猫叫什么", rows, weight=0.75))
    assert [row["id"] for row in out] == ["1", "0", "2", "3"]


def test_scoring_failure_keeps_fused_order(tmp_path, monkeypatch):
    _install_model(tmp_path)
    monkeypatch.setattr(local_rerank, "score_sync", lambda query, docs: None)
    rows = _rows([0.9, 0.5, 0.1])
    assert asyncio.run(local_rerank.rerank_rows("猫叫什么", rows)) == rows


def test_timeout_keeps_fused_order(tmp_path, monkeypatch):
    _install_model(tmp_path)

    def slow(query, docs):
        import time

        time.sleep(0.2)
        return [1.0, 0.0, -1.0]

    monkeypatch.setattr(local_rerank, "score_sync", slow)
    rows = _rows([0.9, 0.5, 0.1])
    out = asyncio.run(local_rerank.rerank_rows("猫叫什么", rows, timeout=0.02))
    assert [row["id"] for row in out] == ["0", "1", "2"]


def test_empty_query_and_single_row_are_no_ops(tmp_path, monkeypatch):
    _install_model(tmp_path)
    monkeypatch.setattr(local_rerank, "score_sync", lambda query, docs: [1.0])
    single = _rows([0.9])
    assert asyncio.run(local_rerank.rerank_rows("猫叫什么", single)) == single
    rows = _rows([0.9, 0.5])
    assert asyncio.run(local_rerank.rerank_rows("   ", rows)) == rows


def test_missing_model_directory_degrades_quietly(tmp_path):
    assert local_rerank.is_configured() is False
    rows = _rows([0.9, 0.5])
    assert asyncio.run(local_rerank.rerank_rows("猫叫什么", rows)) == rows
