#!/usr/bin/env python3
"""Smoke-test DashScope vision (VL) + pet TTS via curl."""
import json
import os
import subprocess
import sys
import urllib.request
from pathlib import Path

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


def make_png(w: int, h: int, rgb=(255, 128, 64)) -> bytes:
    import struct
    import zlib

    def chunk(tag: bytes, data: bytes) -> bytes:
        return (
            struct.pack(">I", len(data))
            + tag
            + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        )

    raw = b"".join(b"\x00" + bytes([rgb[0], rgb[1], rgb[2]] * w) for _ in range(h))
    ihdr = struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )


def curl_json(url: str, payload: dict, key: str) -> tuple[int, dict, str]:
    import tempfile

    body = json.dumps(payload, ensure_ascii=False)
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False, encoding="utf-8") as tmp:
        tmp.write(body)
        tmp_path = tmp.name
    cmd = [
        "curl", "-sS", "-w", "\nHTTP_CODE:%{http_code}\n",
        "-H", f"Authorization: Bearer {key}",
        "-H", "Content-Type: application/json",
        "-d", f"@{tmp_path}",
        url,
    ]
    proc = subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", errors="replace")
    raw = proc.stdout
    if "HTTP_CODE:" in raw:
        text, code_line = raw.rsplit("HTTP_CODE:", 1)
        http_code = int(code_line.strip().splitlines()[0])
    else:
        text, http_code = raw, 0
    try:
        data = json.loads(text) if text.strip() else {}
    except json.JSONDecodeError:
        data = {"_raw": text[:500]}
    return http_code, data, proc.stderr


def main() -> int:
    load_dotenv(ROOT / ".env")
    key = os.environ.get("DASHSCOPE_API_KEY", "")
    if not key:
        print("FAIL: DASHSCOPE_API_KEY missing in .env")
        return 1
    print(f"KEY: present (len={len(key)})")

    ok = True

    # --- TTS ---
    reg = json.loads((ROOT / "backend/lianyu-service/src/main/resources/pet-voices.json").read_text(encoding="utf-8"))
    tts_payload = {
        "model": reg["model"],
        "input": {
            "text": "你好，这是恋语语音测试。",
            "voice": reg["voices"]["raiden"],
            "language_type": "Chinese",
        },
    }
    print("\n=== TTS (model=%s, voice=raiden) ===" % reg["model"])
    http, data, err = curl_json(
        "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation",
        tts_payload,
        key,
    )
    print("HTTP:", http)
    if err.strip():
        print("stderr:", err.strip()[:300])
    url = (data.get("output") or {}).get("audio", {}).get("url")
    if url:
        print("TTS: OK — API returned audio url")
        print("audio_url:", url[:120] + ("..." if len(url) > 120 else ""))
        try:
            with urllib.request.urlopen(url, timeout=60) as resp:
                audio = resp.read()
            print("TTS audio download: OK bytes=%d" % len(audio))
        except Exception as e:
            print("TTS audio download from local PC: skipped (%s)" % e)
            print("(API synthesis succeeded; OSS url may only resolve from cloud server)")
    else:
        ok = False
        print("TTS: FAIL —", data.get("code") or data.get("message") or data)

    # --- Vision VL --- (16x16 PNG base64 — DashScope 要求 >10px，且不宜用境外 URL)
    import base64

    img_b64 = base64.b64encode(make_png(16, 16)).decode("ascii")
    img_note = "inline 16x16 PNG (base64)"
    vision_model = os.environ.get("LIANYU_VISION_MODEL") or "qwen3-vl-plus"
    vl_payload = {
        "model": vision_model,
        "messages": [{
            "role": "user",
            "content": [
                {"type": "text", "text": "用一句话客观描述这张图片的颜色。"},
                {"type": "image_url", "image_url": {"url": "data:image/png;base64," + img_b64}},
            ],
        }],
        "max_tokens": 80,
    }
    print("\n=== Vision (model=%s, image=%s) ===" % (vision_model, img_note))
    http, data, err = curl_json(
        "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
        vl_payload,
        key,
    )
    print("HTTP:", http)
    if err.strip():
        print("stderr:", err.strip()[:300])
    if data.get("choices"):
        reply = data["choices"][0]["message"]["content"]
        print("Vision: OK — reply:", reply[:200])
    else:
        ok = False
        err_obj = data.get("error") or {}
        print("Vision: FAIL —", err_obj.get("code") or data.get("code"), err_obj.get("message") or data.get("message") or data)

    print("\n=== SUMMARY ===")
    print("ALL OK" if ok else "SOME CHECKS FAILED")
    return 0 if ok else 2


if __name__ == "__main__":
    sys.exit(main())
