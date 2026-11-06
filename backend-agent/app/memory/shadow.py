"""影子对比：开关没真正切换前，先看 v3 会不会把旧路径的记忆弄丢。

只比较 id 集合、不比较渲染文本——文本差异大多是排序噪声，真正致命的
是"旧路径记得、新路径彻底没召回"。以旧路径为基准算覆盖率，低于 0.8
就说明这次翻转会掉记忆。
"""

from __future__ import annotations

from typing import Any, Iterable

DEFAULT_K = 5


def top_ids(rows: Iterable[dict[str, Any]], k: int = DEFAULT_K) -> list[str]:
    """取前 k 条的 id（统一成字符串：episode 是 `ep-9`，事实是 `17`）。"""
    ids: list[str] = []
    for row in rows:
        if len(ids) >= k:
            break
        value = row.get("id")
        if value is None:
            continue
        ids.append(str(value))
    return ids


def overlap_at(legacy_rows: Iterable[dict[str, Any]], v3_rows: Iterable[dict[str, Any]],
               k: int = DEFAULT_K) -> float:
    """旧路径 top-k 里，有多少仍出现在 v3 top-k 中（0..1）。

    旧路径本来就是空的时候返回 1.0：没有可丢的记忆，不该被算成回退。
    """
    legacy = top_ids(legacy_rows, k)
    if not legacy:
        return 1.0
    fresh = set(top_ids(v3_rows, k))
    return sum(1 for item in legacy if item in fresh) / len(legacy)


def compare(legacy_rows: Iterable[dict[str, Any]], v3_rows: Iterable[dict[str, Any]],
            k: int = DEFAULT_K) -> dict[str, Any]:
    """给日志用的最小快照：覆盖率 + 丢了哪些 id。"""
    legacy = top_ids(legacy_rows, k)
    fresh = top_ids(v3_rows, k)
    fresh_set = set(fresh)
    return {
        "k": k,
        "legacy": legacy,
        "v3": fresh,
        "overlap": overlap_at(legacy_rows, v3_rows, k),
        "missing": [item for item in legacy if item not in fresh_set],
    }
