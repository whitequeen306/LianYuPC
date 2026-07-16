#!/usr/bin/env python3
"""One-time DashScope VC enrollment for desktop pet voices."""
from __future__ import annotations

import base64
import json
import os
import sys
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[1]
SAMPLES = ROOT / "backend/lianyu-service/src/main/resources/pet-voice-samples"
OUT = ROOT / "backend/lianyu-service/src/main/resources/pet-voices.json"
TARGET_MODEL = "qwen3-tts-vc-2026-01-22"
ENROLL_URL = "https://dashscope.aliyuncs.com/api/v1/services/audio/tts/customization"

PETS = [
    ("klee", "klee-clip.wav", "klee"),
    ("ganyu", "ganyu-clip.wav", "ganyu"),
    ("ayaka", "ayaka-clip.wav", "ayaka"),
    ("raiden", "raiden-clip.wav", "raiden"),
]


def load_dotenv(path: Path) -> None:
    if not path.is_file():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip('"'))


def enroll(api_key: str, preferred_name: str, wav_path: Path) -> str:
    data_uri = f"data:audio/wav;base64,{base64.b64encode(wav_path.read_bytes()).decode()}"
    payload = {
        "model": "qwen-voice-enrollment",
        "input": {
            "action": "create",
            "target_model": TARGET_MODEL,
            "preferred_name": preferred_name,
            "audio": {"data": data_uri},
        },
    }
    resp = requests.post(
        ENROLL_URL,
        headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"},
        json=payload,
        timeout=120,
    )
    if resp.status_code != 200:
        raise RuntimeError(f"{preferred_name}: {resp.status_code} {resp.text[:500]}")
    body = resp.json()
    voice = body.get("output", {}).get("voice")
    if not voice:
        raise RuntimeError(f"{preferred_name}: missing voice in {body}")
    return voice


def main() -> None:
    load_dotenv(ROOT / ".env")
    api_key = os.environ.get("DASHSCOPE_API_KEY")
    if not api_key:
        print("Set DASHSCOPE_API_KEY in .env", file=sys.stderr)
        sys.exit(1)

    registry = {
        "model": TARGET_MODEL,
        "voices": {},
    }
    for pet_id, filename, preferred in PETS:
        wav = SAMPLES / filename
        if not wav.is_file():
            raise SystemExit(f"Missing sample: {wav}")
        print(f"Enrolling {pet_id} from {filename}...", flush=True)
        voice = enroll(api_key, preferred, wav)
        registry["voices"][pet_id] = voice
        print(f"  -> {voice}")

    OUT.write_text(json.dumps(registry, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Wrote {OUT}")


if __name__ == "__main__":
    main()
