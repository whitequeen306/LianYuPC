"""下载本地 rerank（cross-encoder）模型（ONNX，ModelScope 源）。

与 embedding 同一个源与同一套下载逻辑（HF 从服务器不可达），只是文件清单不同：
rerank 模型只需要 `config.json` + `tokenizer.json` + 一个量化图。

用法：
    python -m scripts.fetch_rerank_model --model gte-multilingual-reranker-base --dir models/gte-multilingual-reranker-base
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

from scripts.fetch_embedding_model import BASE, DEFAULT_REVISION, download

_COMMON_FILES: tuple[tuple[str, int, bool], ...] = (
    ("config.json", 200, True),
    ("tokenizer.json", 50_000, True),
)

MODELS: dict[str, dict] = {
    # 与 embedding 同族（gte-multilingual），306M 参数，int8 约 300MB
    "gte-multilingual-reranker-base": {
        "repo": "onnx-community/gte-multilingual-reranker-base",
        "files": (("onnx/model_quantized.onnx", 20_000, True),),
    },
    # 中文+英文 cross-encoder，278M，最小的一档
    "bge-reranker-base": {
        "repo": "Xenova/bge-reranker-base",
        "files": (("onnx/model_quantized.onnx", 20_000, True),),
    },
    # 多语言，278M，与 gte 同档但训练数据更偏检索
    "jina-reranker-v2-base-multilingual": {
        "repo": "jinaai/jina-reranker-v2-base-multilingual",
        "files": (("onnx/model_quantized.onnx", 20_000, True),),
    },
    # 568M 多语言，质量上限最高、CPU 最慢，仅作对照
    "bge-reranker-v2-m3": {
        "repo": "onnx-community/bge-reranker-v2-m3-ONNX",
        "files": (("onnx/model_quantized.onnx", 20_000, True),),
    },
}


def fetch(model_dir: Path, *, model: str, repo: str | None = None, revision: str = DEFAULT_REVISION) -> dict[str, int]:
    spec = MODELS.get(model)
    if spec is None:
        raise ValueError(f"unknown model {model!r}, expected one of {sorted(MODELS)}")
    repo = repo or spec["repo"]
    model_dir.mkdir(parents=True, exist_ok=True)
    written: dict[str, int] = {}
    for remote, min_bytes, required in _COMMON_FILES + spec["files"]:
        local = remote.rsplit("/", 1)[-1]
        target = model_dir / local
        if target.exists() and target.stat().st_size >= min_bytes:
            written[local] = target.stat().st_size
            continue
        url = BASE.format(repo=repo, revision=revision, path=remote)
        try:
            written[local] = download(url, target, min_bytes)
        except Exception as exc:  # noqa: BLE001
            if required:
                raise
            print(f"skip optional {local} ({exc})")
    return written


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="fetch rerank (cross-encoder) ONNX models")
    parser.add_argument("--model", default="gte-multilingual-reranker-base", choices=sorted(MODELS))
    parser.add_argument("--dir", default="", help="target directory (default: models/<model>)")
    parser.add_argument("--repo", default=None, help="override ModelScope repo id")
    parser.add_argument("--revision", default=DEFAULT_REVISION)
    args = parser.parse_args(argv)

    model_dir = Path(args.dir or f"models/{args.model}").resolve()
    written = fetch(model_dir, model=args.model, repo=args.repo, revision=args.revision)
    total = sum(written.values())
    for name, size in written.items():
        print(f"{name:32} {size:>12,} bytes")
    print(f"ok {model_dir} ({total / 1e6:.1f} MB, model={args.model})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
