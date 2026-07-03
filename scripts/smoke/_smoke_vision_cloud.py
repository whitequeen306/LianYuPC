#!/usr/bin/env python3
"""Run DashScope VL smoke on cloud server via curl + server .env key."""
import base64
import json
import os
import struct
import sys
import zlib
from pathlib import Path

import paramiko

HOST = "154.219.111.30"
USER = "root"
ROOT = Path(__file__).resolve().parents[1]


def load_dotenv(path: Path) -> None:
    if not path.is_file():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip('"'))


def make_png(w: int, h: int) -> bytes:
    def chunk(tag: bytes, data: bytes) -> bytes:
        return (
            struct.pack(">I", len(data))
            + tag
            + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        )

    raw = b"".join(b"\x00" + bytes([255, 128, 64] * w) for _ in range(h))
    ihdr = struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )


def main() -> None:
    load_dotenv(ROOT / ".env")
    password = os.environ.get("DEPLOY_SSH_PASSWORD")
    if not password:
        print("Set DEPLOY_SSH_PASSWORD", file=sys.stderr)
        sys.exit(1)

    img = base64.b64encode(make_png(16, 16)).decode("ascii")
    payload = json.dumps({
        "model": os.environ.get("LIANYU_VISION_MODEL") or "qwen3-vl-plus",
        "messages": [{
            "role": "user",
            "content": [
                {"type": "text", "text": "用一句话描述图片颜色"},
                {"type": "image_url", "image_url": {"url": "data:image/png;base64," + img}},
            ],
        }],
        "max_tokens": 60,
    }, ensure_ascii=False)

    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(HOST, username=USER, password=password, timeout=30)
    sftp = client.open_sftp()
    with sftp.file("/tmp/vl-smoke.json", "w") as remote:
        remote.write(payload)
    sftp.close()

    cmd = (
        "cd /opt/lianyu && set -a && . ./.env && set +a && "
        "curl -sS -w '\\nHTTP:%{http_code}' "
        "-H \"Authorization: Bearer ${DASHSCOPE_API_KEY}\" "
        "-H \"Content-Type: application/json\" "
        "-d @/tmp/vl-smoke.json "
        "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions | tail -c 900"
    )
    print("$ cloud VL smoke")
    _, stdout, stderr = client.exec_command(cmd, timeout=90)
    print(stdout.read().decode("utf-8", errors="replace") or stderr.read().decode("utf-8", errors="replace"))
    client.close()


if __name__ == "__main__":
    main()
