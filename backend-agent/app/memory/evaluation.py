"""召回质量的离线评测：一组「这句话应该想起哪条记忆」的题，跑 recall@k / MRR。

为什么要单独一层：改权重、改 τ、开向量路，光看单元测试都"对"，但召回有没有
变好只能拿题集量。题集是 JSONL（一行一题），不塞进仓库的多语言语料里：

    {"query": "我上次说我想养什么", "userId": 7, "characterId": 3, "expected": ["42"]}

id 用字符串：episode 的 id 是 `ep-9` 这种带前缀的投影 id，和 memory_meta 的
自增 id 混在一张候选表里。
"""

from __future__ import annotations

import asyncio
import json
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Iterable, Sequence


@dataclass(frozen=True)
class Case:
    query: str
    user_id: int
    character_id: int
    expected: tuple[str, ...]


def load_cases(path: str | Path) -> list[Case]:
    """读 JSONL；坏行直接抛错——题集错一行，指标就整体不可信。"""
    cases: list[Case] = []
    for lineno, line in enumerate(Path(path).read_text(encoding="utf-8").splitlines(), 1):
        text = line.strip()
        if not text or text.startswith("//"):
            continue
        try:
            item = json.loads(text)
            cases.append(
                Case(
                    query=str(item["query"]).strip(),
                    user_id=int(item["userId"]),
                    character_id=int(item["characterId"]),
                    expected=tuple(str(x) for x in item["expected"]),
                )
            )
        except (KeyError, TypeError, ValueError) as exc:
            raise ValueError(f"{path}:{lineno} 不是合法评测题：{exc}") from exc
    return cases


def hit_at(ranked: Sequence[str], expected: Iterable[str], k: int = 5) -> bool:
    wanted = set(expected)
    return any(str(item) in wanted for item in ranked[:k])


def reciprocal_rank(ranked: Sequence[str], expected: Iterable[str]) -> float:
    wanted = set(expected)
    for index, item in enumerate(ranked, 1):
        if str(item) in wanted:
            return 1.0 / index
    return 0.0


def evaluate(
    cases: Sequence[Case],
    rank_fn: Callable[[Case], Sequence[str]],
    *,
    k: int = 5,
) -> dict[str, Any]:
    """`rank_fn` 返回按分数降序的 id；空题集返回 None 指标而不是 0，避免"没测过"被当成"很差"。"""
    if not cases:
        return {"n": 0, f"recall@{k}": None, "mrr": None, "misses": []}

    hits = 0
    rr = 0.0
    misses: list[dict[str, Any]] = []
    per_case: list[dict[str, Any]] = []
    for case in cases:
        ranked = [str(item) for item in rank_fn(case)]
        hit = hit_at(ranked, case.expected, k)
        rank = _rank_of(ranked, case.expected)
        per_case.append({"query": case.query, "rank": rank})
        if hit:
            hits += 1
        else:
            misses.append({"query": case.query, "expected": list(case.expected), "top": ranked[:k]})
        rr += reciprocal_rank(ranked, case.expected)

    return {
        "n": len(cases),
        f"recall@{k}": round(hits / len(cases), 4),
        "mrr": round(rr / len(cases), 4),
        "misses": misses,
        "cases": per_case,
    }


def _rank_of(ranked: Sequence[str], expected: Iterable[str]) -> int | None:
    wanted = set(expected)
    for index, item in enumerate(ranked, 1):
        if str(item) in wanted:
            return index
    return None


def compare(previous: dict[str, Any], current: dict[str, Any], *, k: int = 5) -> dict[str, Any]:
    """同一个题集两次跑的差异：谁被新链路救回来、谁被弄丢了。

    只看整体 recall 会掩盖"换了两条"的情况；逐题对比才知道该不该开开关。
    """
    before = {item["query"]: item.get("rank") for item in previous.get("cases", [])}
    after = {item["query"]: item.get("rank") for item in current.get("cases", [])}
    shared = sorted(set(before) & set(after))

    wins = [q for q in shared if _is_hit(before[q], k) is False and _is_hit(after[q], k) is True]
    losses = [q for q in shared if _is_hit(before[q], k) is True and _is_hit(after[q], k) is False]

    def _delta(key: str) -> float | None:
        old, new = previous.get(key), current.get(key)
        if old is None or new is None:
            return None
        return round(new - old, 4)

    return {
        "n": len(shared),
        "recall_delta": _delta(f"recall@{k}"),
        "mrr_delta": _delta("mrr"),
        "wins": wins,
        "losses": losses,
    }


def _is_hit(rank: int | None, k: int) -> bool | None:
    if rank is None:
        return False
    return rank <= k


def render_comparison(diff: dict[str, Any], *, k: int = 5) -> str:
    if not diff["n"]:
        return "两次结果没有可对比的题（查询文本必须完全一致）。"
    recall_delta = diff.get("recall_delta")
    mrr_delta = diff.get("mrr_delta")
    recall_text = "n/a" if recall_delta is None else f"{recall_delta:+.2%}"
    mrr_text = "n/a" if mrr_delta is None else f"{mrr_delta:+.3f}"
    lines = [
        f"对比 {diff['n']} 道题：recall@{k} {recall_text}，MRR {mrr_text}",
        f"救回 {len(diff['wins'])} 条，弄丢 {len(diff['losses'])} 条",
    ]
    for query in diff["wins"][:5]:
        lines.append(f"  + {query}")
    for query in diff["losses"][:5]:
        lines.append(f"  - {query}")
    return "\n".join(lines)


def render_report(report: dict[str, Any], *, k: int = 5) -> str:
    if not report["n"]:
        return "没有可评测的题（0 条）。"
    lines = [
        f"题数 {report['n']}",
        f"recall@{k} {report[f'recall@{k}']:.2%}",
        f"MRR {report['mrr']:.3f}",
    ]
    if report["misses"]:
        lines.append(f"未命中 {len(report['misses'])} 条：")
        for miss in report["misses"][:10]:
            lines.append(f"  - {miss['query']} → 期望 {miss['expected']}，实际 {miss['top']}")
    return "\n".join(lines)


async def run_live(cases: Sequence[Case], *, limit: int = 5) -> dict[str, Any]:
    """对真实库跑一遍；开关（v3 / 向量路）决定它测的是哪条链路。"""
    from app.memory.recall import recall

    async def ranking(case: Case) -> list[str]:
        rows = await recall(case.character_id, case.user_id, case.query, limit=limit)
        return [str(row.get("id")) for row in rows]

    results = []
    for case in cases:
        results.append(await ranking(case))
    by_query = {case.query: ranked for case, ranked in zip(cases, results)}
    return evaluate(cases, lambda case: by_query[case.query], k=limit)


def main(argv: Sequence[str] | None = None) -> int:
    args = list(sys.argv[1:] if argv is None else argv)
    if not args or args[0].startswith("-"):
        print(
            "usage: python -m app.memory.evaluation <cases.jsonl> "
            "[--k 5] [--json out.json] [--compare prev.json]"
        )
        return 2

    cases = load_cases(args[0])
    k = 5
    json_out: str | None = None
    compare_with: str | None = None
    rest = args[1:]
    while rest:
        flag = rest.pop(0)
        if flag == "--k" and rest:
            k = int(rest.pop(0))
        elif flag == "--json" and rest:
            json_out = rest.pop(0)
        elif flag == "--compare" and rest:
            compare_with = rest.pop(0)
        else:
            print(f"unknown option: {flag}")
            return 2

    report = asyncio.run(run_live(cases, limit=k))
    print(render_report(report, k=k))
    if json_out:
        Path(json_out).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    if compare_with:
        previous = json.loads(Path(compare_with).read_text(encoding="utf-8"))
        print(render_comparison(compare(previous, report, k=k), k=k))
    return 0 if report["n"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
