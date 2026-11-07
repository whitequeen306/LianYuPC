"""本地向量化（onnxruntime，进程内）。

为什么要有它：线上一直用 DashScope `text-embedding-v3`，往返 0.6–1.5 秒，而召回
只等 0.18 秒，结果是**向量路每轮都超时被丢掉**，实际干活的只剩 ngram 与原话两路。
换成本地模型后单条 10 毫秒级，向量路才真的在补偿"说法不同、词面不同"的召回。

模型选择（2026-11-07 定）：默认 `gte-multilingual-base`，768 维，int8 ONNX 约 340MB。
51 题中文集 MRR 0.898（bge-base-zh 0.896、bge-small 0.885、DashScope 0.939），
但**跨语言召回 4/4**（中文记忆 + 英文/日文提问），bge-*-zh 只有 2–3/4——产品支持
zh/en/ja，用户完全可能换语言问，所以多语言这一条压过了体积。服务器无 GPU，
0.6B 级模型（bge-m3、Qwen3-Embedding）单条 200ms 以上，会把预算吃光。

池化方式：这类编码器用 CLS（第 0 个 token）而不是 mean pooling，取完做 L2 归一，
这样余弦相似度可以直接当相似度用。
"""

from __future__ import annotations

import logging
import threading
from pathlib import Path
from typing import Any

from app.config import get_settings

logger = logging.getLogger(__name__)

GRAPH_FILE = "model_quantized.onnx"
TOKENIZER_FILE = "tokenizer.json"
# 不同导出用的文件名不一样（Xenova 用 model_quantized，onnx-community 有的用 model_int8）。
# 按"量化优先"的顺序挑第一个存在的，运维换模型就不用改代码。
GRAPH_CANDIDATES = ("model_quantized.onnx", "model_int8.onnx", "model_uint8.onnx", "model_fp16.onnx", "model.onnx")

_session: Any = None
_tokenizer: Any = None
_load_failed = False
_load_lock = threading.Lock()
# ONNX 会话本身是多线程的；再叠并发只会互相抢核，序列化反而更稳（单条约 15ms）。
_run_lock = threading.Semaphore(1)


def pool_and_normalize(hidden: Any) -> Any:
    """CLS 池化 + L2 归一：`hidden` 形如 [batch, seq, dim]，返回 [batch, dim]。"""
    import numpy as np

    if hidden.ndim == 2:
        # 某些导出会把 batch 维压掉，这里补回来，免得下游拿到错形状
        hidden = hidden[None, :, :]
    pooled = hidden[:, 0, :].astype(np.float32)
    norms = np.linalg.norm(pooled, axis=1, keepdims=True)
    return pooled / np.clip(norms, 1e-12, None)


def resolve_graph(model_dir: Path) -> Path | None:
    """挑模型文件：先按候选名，找不到再兜底 glob（导出命名五花八门）。"""
    for name in GRAPH_CANDIDATES:
        candidate = model_dir / name
        if candidate.exists():
            return candidate
    found = sorted(model_dir.glob("*.onnx"))
    return found[0] if found else None


def _load() -> tuple[Any, Any]:
    """惰性加载会话与分词器；缺文件时只记一次 warning，之后直接快速失败。"""
    global _session, _tokenizer, _load_failed
    if _session is not None and _tokenizer is not None:
        return _session, _tokenizer
    with _load_lock:
        if _session is not None and _tokenizer is not None:
            return _session, _tokenizer
        if _load_failed:
            raise RuntimeError("local embedding unavailable")
        settings = get_settings()
        model_dir = Path(settings.local_embedding_model_dir)
        graph = resolve_graph(model_dir)
        tokenizer_file = model_dir / TOKENIZER_FILE
        try:
            missing = [str(p) for p in (graph, tokenizer_file) if p is None or not p.exists()]
            if missing:
                raise FileNotFoundError(
                    "missing " + ", ".join(missing) + " (run scripts/fetch_embedding_model.py)"
                )
            import onnxruntime as ort
            from tokenizers import Tokenizer

            options = ort.SessionOptions()
            options.intra_op_num_threads = max(1, int(settings.local_embedding_threads))
            options.inter_op_num_threads = 1
            # 不在启动时抢核：图优化耗 CPU，交给第一次真正调用
            options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
            session = ort.InferenceSession(
                str(graph), sess_options=options, providers=["CPUExecutionProvider"]
            )
            tokenizer = Tokenizer.from_file(str(tokenizer_file))
            tokenizer.enable_truncation(max_length=int(settings.local_embedding_max_length))
            tokenizer.enable_padding(pad_id=0, pad_token="[PAD]")
            _session, _tokenizer = session, tokenizer
            logger.info(
                "local embedding ready: %s (threads=%s, max_len=%s)",
                model_dir,
                options.intra_op_num_threads,
                settings.local_embedding_max_length,
            )
        except Exception as exc:  # noqa: BLE001 - 缺模型不能打断对话
            _load_failed = True
            logger.warning("local embedding unavailable: %s", exc)
            raise
    return _session, _tokenizer


def _build_feeds(session: Any, ids: Any, mask: Any) -> dict[str, Any] | None:
    """按模型声明组装输入：只喂它要的，未知输入直接放弃（返回 None）。"""
    import numpy as np

    feeds: dict[str, Any] = {}
    for item in session.get_inputs():
        name = item.name
        if name == "input_ids":
            feeds[name] = ids
        elif name == "attention_mask":
            feeds[name] = mask
        elif name in ("token_type_ids", "segment_ids"):
            feeds[name] = np.zeros_like(ids)
        else:
            logger.warning("local embedding: unsupported input %r", name)
            return None
    return feeds


def embed_sync(texts: list[str]) -> list[list[float]] | None:
    """同步批量向量化；失败返回 None（调用方降级成文本路）。"""
    if not texts:
        return []
    try:
        import numpy as np

        session, tokenizer = _load()
    except Exception:  # noqa: BLE001
        return None

    encodings = tokenizer.encode_batch([text or " " for text in texts])
    ids = np.asarray([enc.ids for enc in encodings], dtype=np.int64)
    mask = np.asarray([enc.attention_mask for enc in encodings], dtype=np.int64)
    feeds = _build_feeds(session, ids, mask)
    if feeds is None:
        return None
    try:
        with _run_lock:
            hidden = session.run(None, feeds)[0]
    except Exception as exc:  # noqa: BLE001
        logger.warning("local embedding inference failed: %s", exc)
        return None
    return pool_and_normalize(hidden).tolist()


async def embed(texts: list[str]) -> list[list[float]] | None:
    """异步入口：放到线程池跑，别阻塞事件循环。"""
    import asyncio

    if not texts:
        return []
    loop = asyncio.get_running_loop()
    return await loop.run_in_executor(None, embed_sync, texts)


async def warm() -> None:
    """启动预热：把会话与分词器读进内存，避免第一轮聊天多等 100+ 毫秒。"""
    await embed(["预热"])


__all__ = ["embed", "embed_sync", "pool_and_normalize", "warm"]
