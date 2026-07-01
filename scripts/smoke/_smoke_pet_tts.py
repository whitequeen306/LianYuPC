#!/usr/bin/env python3
"""Smoke-test pet TTS synthesis."""
import json
import os
import sys
from pathlib import Path

import requests

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


def main() -> None:
    load_dotenv(ROOT / ".env")
    key = os.environ.get("DASHSCOPE_API_KEY")
    if not key:
        print("missing DASHSCOPE_API_KEY", file=sys.stderr)
        sys.exit(1)
    reg = json.loads((ROOT / "backend/lianyu-service/src/main/resources/pet-voices.json").read_text())
    voice = reg["voices"]["klee"]
    model = reg["model"]
    payload = {
        "model": model,
        "input": {
            "text": "你好呀，今天也在认真玩游戏吗？",
            "voice": voice,
            "language_type": "Chinese",
        },
    }
    resp = requests.post(
        "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation",
        headers={"Authorization": f"Bearer {key}"},
        json=payload,
        timeout=60,
    )
    print("status", resp.status_code)
    body = resp.json()
    url = body.get("output", {}).get("audio", {}).get("url")
    print("url", url)
    if url:
        audio = requests.get(url, timeout=60).content
        print("bytes", len(audio))


if __name__ == "__main__":
    main()
