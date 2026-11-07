"""下载本地 embedding 模型（ONNX，ModelScope 源）。

为什么不在仓库里放权重：100MB 级二进制进 git 会让 clone 变慢，换模型就得重写历史。
模型放宿主机 `models/`（已 gitignore），compose 只读挂进容器。

为什么走 ModelScope：生产服务器到 huggingface.co 不可达（hf-mirror 也会 308 跳回去），
ModelScope 直连 200。

跑法：

    python -m scripts.fetch_embedding_model --model bge-small-zh-v1.5 --dir models/bge-small-zh-v1.5
    python -m scripts.fetch_embedding_model --model bge-base-zh-v1.5  --dir models/bge-base-zh-v1.5

两者都是 bge 中文 v1.5 家族、CLS 池化，只是宽度不同：
- small：512 维 / 24MB int8 / 2 线程单条约 15ms
- base ：768 维 / 102MB int8 / 2 线程单条约 45ms（质量约高 2.5 分 C-MTEB）
"""

from __future__ import annotations

import argparse
import sys
import urllib.error
import urllib.request
from pathlib import Path

BASE = "https://www.modelscope.cn/models/{repo}/resolve/{revision}/{path}"

# (远端路径, 最小字节数, 是否必需)
# .onnx_data 是 ONNX 的外部权重：大模型才有，小模型缺它也能跑，所以标成可选
_COMMON_FILES: tuple[tuple[str, int, bool], ...] = (
    ("config.json", 200, True),
    ("tokenizer.json", 50_000, True),
)

MODELS: dict[str, dict] = {
    "bge-small-zh-v1.5": {
        "repo": "Xenova/bge-small-zh-v1.5",
        "dim": 512,
        "files": (
            ("onnx/model_quantized.onnx", 20_000, True),
            ("onnx/model_quantized.onnx_data", 1_000_000, False),
        ),
    },
    "bge-base-zh-v1.5": {
        "repo": "Xenova/bge-base-zh-v1.5",
        "dim": 768,
        "files": (
            ("onnx/model_quantized.onnx", 20_000, True),
            ("onnx/model_quantized.onnx_data", 1_000_000, False),
        ),
    },
    # 默认模型：中文质量与 bge-base 持平，但跨语言（英/日问、中文记忆）4/4 命中。
    # ModelScope 上 Xenova 没有这个仓库，用 onnx-community。
    "gte-multilingual-base": {
        "repo": "onnx-community/gte-multilingual-base",
        "dim": 768,
        "files": (
            ("onnx/model_int8.onnx", 20_000, True),
            ("config.json", 200, True),
            ("tokenizer.json", 50_000, True),
        ),
    },
}

DEFAULT_REVISION = "master"


def download(url: str, target: Path, min_bytes: int) -> int:
    """流式下载到临时文件再改名，避免中断留下半个文件被当成有效模型。"""
    tmp = target.with_suffix(target.suffix + ".part")
    with urllib.request.urlopen(url, timeout=180) as response:  # noqa: S310 - 固定域名
        total = 0
        with tmp.open("wb") as handle:
            while True:
                chunk = response.read(1 << 20)
                if not chunk:
                    break
                total += len(chunk)
                handle.write(chunk)
    if total < min_bytes:
        tmp.unlink(missing_ok=True)
        raise RuntimeError(f"{target.name}: got {total} bytes, expected >= {min_bytes}")
    tmp.replace(target)
    return total


def fetch(
    model_dir: Path,
    *,
    model: str = "bge-small-zh-v1.5",
    repo: str | None = None,
    revision: str = DEFAULT_REVISION,
) -> dict[str, int]:
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
        except urllib.error.HTTPError as exc:
            if required:
                raise
            print(f"skip optional {local} (http {exc.code})")
        except RuntimeError as exc:
            if required:
                raise
            print(f"skip optional {local} ({exc})")
    return written


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="fetch bge-*-zh-v1.5 ONNX")
    parser.add_argument("--model", default="bge-small-zh-v1.5", choices=sorted(MODELS))
    parser.add_argument("--dir", default="", help="target directory (default: models/<model>)")
    parser.add_argument("--repo", default=None, help="override ModelScope repo id")
    parser.add_argument("--revision", default=DEFAULT_REVISION)
    args = parser.parse_args(argv)

    model_dir = Path(args.dir or f"models/{args.model}").resolve()
    try:
        written = fetch(model_dir, model=args.model, repo=args.repo, revision=args.revision)
    except Exception as exc:  # noqa: BLE001 - 运维脚本，直白报错即可
        print(f"fetch failed: {exc}", file=sys.stderr)
        return 1
    total = sum(written.values())
    for name, size in written.items():
        print(f"{name:32} {size:>12,} bytes")
    print(f"ok {model_dir} ({total / 1e6:.1f} MB, model={args.model}, dim={MODELS[args.model]['dim']})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
