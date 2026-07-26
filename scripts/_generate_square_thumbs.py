#!/usr/bin/env python3
"""�?MinIO 中已�?square-avatars 原图补生�?square-avatars-thumb 缩略图�?

依赖后端 CharacterSquareAvatarSync 启动回填；本脚本通过重启 backend 触发同步�?
或直接调�?MinIO + �?Java 相同的裁切逻辑（需 Pillow）�?

用法:
  python scripts/_generate_square_thumbs.py
  python scripts/_generate_square_thumbs.py --api https://156.233.228.18 --token <lianyu-token>
"""
from __future__ import annotations

import argparse
import io
import os
import sys
from pathlib import Path

try:
    import requests
except ImportError:
    requests = None

try:
    from PIL import Image
except ImportError:
    Image = None

ROOT = Path(__file__).resolve().parents[1]
THUMB_SIZE = 296
THUMB_PREFIX = "square-avatars-thumb/"
ORIGIN_PREFIX = "square-avatars/"


def crop_square_thumb(source: bytes, size: int = THUMB_SIZE) -> bytes:
    if Image is None:
        raise RuntimeError("Pillow required: pip install Pillow")
    img = Image.open(io.BytesIO(source)).convert("RGB")
    side = min(img.width, img.height)
    left = (img.width - side) // 2
    top = (img.height - side) // 2
    cropped = img.crop((left, top, left + side, top + side))
    scaled = cropped.resize((size, size), Image.Resampling.LANCZOS)
    out = io.BytesIO()
    scaled.save(out, format="JPEG", quality=82, optimize=True)
    return out.getvalue()


def backfill_via_minio_env() -> int:
    """使用 MINIO_* 环境变量直连 MinIO（与 docker-compose 一致）�?""
    try:
        from minio import Minio
    except ImportError as exc:
        raise RuntimeError("minio package required: pip install minio") from exc

    endpoint = os.environ.get("MINIO_ENDPOINT", "localhost:9000").replace("http://", "").replace("https://", "")
    access = os.environ.get("MINIO_ACCESS_KEY") or os.environ.get("MINIO_ROOT_USER", "minioadmin")
    secret = os.environ.get("MINIO_SECRET_KEY") or os.environ.get("MINIO_ROOT_PASSWORD", "minioadmin")
    bucket = os.environ.get("MINIO_BUCKET", "lianyu")
    secure = os.environ.get("MINIO_SECURE", "0") == "1"

    client = Minio(endpoint, access_key=access, secret_key=secret, secure=secure)
    generated = 0
    for obj in client.list_objects(bucket, prefix=ORIGIN_PREFIX, recursive=True):
        key = obj.object_name
        if not key or key.endswith("/"):
            continue
        slug = Path(key).stem
        thumb_key = f"{THUMB_PREFIX}{slug}.jpg"
        try:
            client.stat_object(bucket, thumb_key)
            continue
        except Exception:
            pass
        response = client.get_object(bucket, key)
        try:
            source = response.read()
        finally:
            response.close()
            response.release_conn()
        thumb_bytes = crop_square_thumb(source)
        client.put_object(
            bucket,
            thumb_key,
            io.BytesIO(thumb_bytes),
            length=len(thumb_bytes),
            content_type="image/jpeg",
        )
        print(f"generated {thumb_key} from {key}")
        generated += 1
    return generated


def trigger_backend_restart_hint() -> None:
    print("Tip: deploy backend to run CharacterSquareAvatarSync backfill on startup.")


def main() -> int:
    parser = argparse.ArgumentParser(description="Backfill square avatar thumbnails")
    parser.add_argument("--api", help="Optional API origin (unused unless extended)")
    parser.add_argument("--token", help="Optional auth token (unused unless extended)")
    args = parser.parse_args()

    if args.api:
        print("Note: API mode not implemented; using MinIO direct backfill.")

    try:
        count = backfill_via_minio_env()
        print(f"Done. generated={count}")
        return 0
    except Exception as exc:
        print(f"MinIO backfill failed: {exc}", file=sys.stderr)
        trigger_backend_restart_hint()
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
