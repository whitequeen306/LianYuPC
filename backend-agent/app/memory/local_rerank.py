"""本地 cross-encoder 精排（rerank），进程内 ONNX，无外网。

为什么需要它：向量召回只比「这句话大意像不像」，遇到绕词的问法（「每天早上不喝点什么
就睁不开眼」）会把「她喝咖啡会心慌」排在真答案前面——第一段召回本来就该宽，精排看的是
query 与这一条记忆**放在一起**读起来对不对。

为什么是本地：服务器到 DashScope 的往返 0.6–1.5s，召回预算只有 0.45s，外网精排等于没有；
本地 gte-multilingual-reranker-base（int8，341MB）单条约 16ms，8 条一档约 110ms 跑得下。

为什么是混合而不是替换：在 49 题中文语料上纯精排是负收益（recall@5 43→39、MRR 0.799→0.726），
因为 CE 偏爱字面更像的那条；与融合分按比例混合才是净赚（44/49、MRR 0.845）。
所以默认开但要能一键关（`memory_rerank_local_enabled`），且缺模型/超时/异常一律退回融合顺序。
"""

from __future__ import annotations

import asyncio
import logging
import threading
from pathlib import Path
from typing import Any

from app.config import get_settings

logger = logging.getLogger(__name__)

GRAPH_CANDIDATES = ("model_quantized.onnx", "model_int8.onnx", "model_fp16.onnx", "model.onnx")
TOKENIZER_FILE = "tokenizer.json"

_session: Any = None
_tokenizer: Any = None
_load_failed = False
_load_lock = threading.Lock()
_run_lock = threading.Lock()


def resolve_graph(model_dir: str | Path) -> Path | None:
    root = Path(model_dir)
    for name in GRAPH_CANDIDATES:
        candidate = root / name
        if candidate.exists():
            return candidate
    return None


def is_configured() -> bool:
    settings = get_settings()
    if not getattr(settings, "memory_rerank_local_enabled", False):
        return False
    model_dir = getattr(settings, "local_rerank_model_dir", "")
    if not model_dir:
        return False
    root = Path(model_dir)
    return resolve_graph(root) is not None and (root / TOKENIZER_FILE).exists()


def _load() -> tuple[Any, Any] | None:
    """惰性加载 ONNX 会话与分词器；缺文件只 warning 一次，不抛。"""
    global _session, _tokenizer, _load_failed
    if _session is not None and _tokenizer is not None:
        return _session, _tokenizer
    if _load_failed:
        return None
    with _load_lock:
        if _session is not None and _tokenizer is not None:
            return _session, _tokenizer
        settings = get_settings()
        model_dir = Path(getattr(settings, "local_rerank_model_dir", ""))
        graph = resolve_graph(model_dir)
        tokenizer_file = model_dir / TOKENIZER_FILE
        if graph is None or not tokenizer_file.exists():
            logger.warning("local rerank model missing under %s; rerank stays off", model_dir)
            _load_failed = True
            return None
        try:
            import onnxruntime as ort  # noqa: PLC0415
            from tokenizers import Tokenizer  # noqa: PLC0415

            options = ort.SessionOptions()
            options.intra_op_num_threads = int(getattr(settings, "local_rerank_threads", 2) or 2)
            options.inter_op_num_threads = 1
            options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
            session = ort.InferenceSession(str(graph), options, providers=["CPUExecutionProvider"])
            tokenizer = Tokenizer.from_file(str(tokenizer_file))
            tokenizer.enable_truncation(max_length=int(getattr(settings, "local_rerank_max_length", 256) or 256))
            tokenizer.enable_padding(pad_id=0, pad_token="[PAD]")
        except Exception as exc:  # noqa: BLE001 - 精排失败不能影响回复
            logger.warning("local rerank load failed: %s", exc)
            _load_failed = True
            return None
        _session, _tokenizer = session, tokenizer
        logger.info("local rerank ready: %s (threads=%s)", graph, options.intra_op_num_threads)
        return _session, _tokenizer


def reset() -> None:
    """测试用：清掉已加载的会话与失败标记。"""
    global _session, _tokenizer, _load_failed
    _session = None
    _tokenizer = None
    _load_failed = False


def warm() -> bool:
    """启动时把会话加载 + 空跑一次。

    342MB 的 ONNX 首次建会话要几百毫秒，超过精排 0.12s 的预算；不预热的话
    「上线后第一轮真实提问」必然因为没有会话而丢弃精排（或更糟：把这一轮的召回
    拖过总预算）。放在 lifespan 里预热，代价只是一次启动时间。
    """
    if not is_configured():
        return False
    return score_sync("预热", ["预热"]) is not None


def score_sync(query: str, documents: list[str]) -> list[float] | None:
    """同步打分；返回与 documents 等长的 logits（越大越相关）。"""
    if not query or not documents:
        return []
    loaded = _load()
    if loaded is None:
        return None
    session, tokenizer = loaded
    try:
        import numpy as np  # noqa: PLC0415

        encodings = tokenizer.encode_batch([(query, doc) for doc in documents])
        feeds = {
            "input_ids": np.array([item.ids for item in encodings], dtype=np.int64),
            "attention_mask": np.array([item.attention_mask for item in encodings], dtype=np.int64),
        }
        names = {item.name for item in session.get_inputs()}
        if "token_type_ids" in names:
            feeds["token_type_ids"] = np.array([item.type_ids for item in encodings], dtype=np.int64)
        with _run_lock:  # onnxruntime 会话可重入但 CPU 线程数固定，串行更稳
            raw = session.run([session.get_outputs()[0].name], feeds)[0]
        matrix = np.asarray(raw, dtype=np.float32).reshape(len(documents), -1)
        return [float(value) for value in matrix[:, 0]]
    except Exception as exc:  # noqa: BLE001 - 打分失败退回原顺序
        logger.warning("local rerank scoring failed: %s", exc)
        return None


def _minmax(values: list[float]) -> list[float]:
    if not values:
        return []
    low, high = min(values), max(values)
    if high - low < 1e-9:
        return [0.5 for _ in values]
    return [(value - low) / (high - low) for value in values]


def blend(base: list[float], cross: list[float], weight: float) -> list[float]:
    """把「原本的分数」与 cross-encoder 分数各自 min-max 后按比例混合。

    不能直接拿 CE 分替掉原分：在中文长尾问法上 CE 偏爱字面更像的那条，
    纯替换会让真答案掉出前五（实测 43/49 → 39/49）。混一点才是净收益。
    """
    left, right = _minmax(base), _minmax(cross)
    return [(1.0 - weight) * a + weight * b for a, b in zip(left, right)]


def _row_text(row: dict[str, Any]) -> str:
    summary = str(row.get("summary") or "").strip()
    if summary:
        return summary
    return str(row.get("quote") or "").strip()


async def rerank_rows(
    query: str,
    rows: list[dict[str, Any]],
    *,
    weight: float | None = None,
    top_k: int | None = None,
    timeout: float | None = None,
) -> list[dict[str, Any]]:
    """对前 top_k 条做精排，其余保持原顺序（不会因为精排把候选弄丢）。

    任何异常/超时都返回原列表——精排是加分项，不是必需品。
    """
    settings = get_settings()
    if len(rows) < 2 or not is_configured():
        return rows
    text = (query or "").strip()
    if not text:
        return rows
    limit = int(top_k if top_k is not None else getattr(settings, "local_rerank_max_candidates", 12))
    head, tail = rows[:max(2, limit)], rows[max(2, limit):]
    documents = [_row_text(row) for row in head]
    if any(not doc for doc in documents):
        return rows
    budget = float(timeout if timeout is not None else getattr(settings, "local_rerank_timeout_seconds", 0.12))
    try:
        scores = await asyncio.wait_for(asyncio.to_thread(score_sync, text, documents), timeout=max(0.02, budget))
    except asyncio.TimeoutError:
        logger.debug("local rerank timed out after %.2fs; keeping fused order", budget)
        return rows
    if not scores or len(scores) != len(head):
        return rows
    base = [float(row.get("score") or 0.0) for row in head]
    mixed = blend(
        base,
        scores,
        float(weight if weight is not None else getattr(settings, "local_rerank_weight", 0.35)),
    )
    # 混合分只在前 top_k 内部做了 min-max，必须映回原来的分数量纲：MMR 是按
    # row["score"] 排序的，只调整列表顺序等于白干（实测 MRR 一点不变就是这个原因）。
    low, high = min(base), max(base)
    if high - low < 1e-9:
        return rows
    for row, raw, value in zip(head, scores, mixed):
        row["score"] = low + value * (high - low)
        row["_ce"] = raw
    ordered = sorted(head, key=lambda row: float(row.get("score") or 0.0), reverse=True)
    return ordered + tail
