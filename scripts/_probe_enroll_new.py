#!/usr/bin/env python3
"""用新 API 注册音色并验证合成（一次跑通再谈批量）。

背景（2026-10-21 查证）：
- 旧代码用 qwen-voice-enrollment + action:create + 读 output.voice，是旧版接口；
  新的音色接口是 voice-enrollment + action:create_voice + 读 output.voice_id，
  且必须轮询 query_voice 等 status=OK（中间会经历 DEPLOYING）。
- prefix 有 10 字符硬上限（旧实现的 preferred_name 允许 32）。
- qwen3-tts-* 不在「声音复刻支持模型」列表里，只能系统音色、不能复刻。
  要做角色音色必须用 qwen-audio-3.1-tts-flash（非实时）与
  qwen-audio-*-realtime-*（实时）。
- 一个音色只能绑定一个 target_model，HTTP 与实时要各注册一次。

用法: python scripts/_probe_enroll_new.py      （需要环境变量 DSK）
"""
from __future__ import annotations

import base64
import json
import os
import sys
import time
from pathlib import Path

import requests

ROOT = Path(__file__).resolve().parents[1]
SAMPLES = ROOT / "backend/lianyu-service/src/main/resources/pet-voice-samples"
BASE = "https://dashscope.aliyuncs.com/api/v1"
CUSTOMIZATION = f"{BASE}/services/audio/tts/customization"
SPEECH_SYNTH = f"{BASE}/services/audio/tts/SpeechSynthesizer"
LEGACY_GEN = f"{BASE}/services/aigc/multimodal-generation/generation"

KEY = (os.environ.get("DSK") or "").strip()
if not KEY:
    sys.exit("缺少环境变量 DSK")
HEADERS = {"Authorization": f"Bearer {KEY}", "Content-Type": "application/json"}

TEXT = "你好，我是林晚，今天也想和你说说话。"


def data_uri(path: Path) -> str:
    return f"data:audio/wav;base64,{base64.b64encode(path.read_bytes()).decode()}"


def enroll(target_model: str, prefix: str, wav: Path) -> str:
    resp = requests.post(
        CUSTOMIZATION,
        headers=HEADERS,
        json={
            "model": "voice-enrollment",
            "input": {
                "action": "create_voice",
                "target_model": target_model,
                "prefix": prefix,
                "audio": data_uri(wav),
            },
        },
        timeout=180,
    )
    if resp.status_code != 200:
        raise RuntimeError(f"create_voice {resp.status_code}: {resp.text[:300]}")
    voice_id = resp.json().get("output", {}).get("voice_id")
    if not voice_id:
        raise RuntimeError(f"create_voice 无 voice_id: {resp.text[:300]}")

    for _ in range(40):
        q = requests.post(
            CUSTOMIZATION,
            headers=HEADERS,
            json={
                "model": "voice-enrollment",
                "input": {"action": "query_voice", "voice_id": voice_id},
            },
            timeout=60,
        )
        status = (
            (q.json().get("output") or {}).get("status")
            if q.status_code == 200
            else f"HTTP{q.status_code}"
        )
        if status == "OK":
            return voice_id
        if status not in ("DEPLOYING", "PROCESSING"):
            raise RuntimeError(f"query_voice status={status}: {q.text[:300]}")
        time.sleep(1.5)
    raise TimeoutError("音色部署超时")


def synth(model: str, voice: str) -> tuple[bool, str]:
    last = ""
    for url, label in ((SPEECH_SYNTH, "SpeechSynthesizer"), (LEGACY_GEN, "legacy")):
        resp = requests.post(
            url,
            headers=HEADERS,
            json={
                "model": model,
                "input": {"text": TEXT, "voice": voice, "format": "wav", "sample_rate": 24000},
            },
            timeout=120,
        )
        if resp.status_code == 200:
            audio = (resp.json().get("output") or {}).get("audio") or {}
            if audio.get("url"):
                return True, f"{label} 200 有 audio.url"
            last = f"{label} 200 但无 audio.url"
        else:
            last = f"{label} {resp.status_code} {resp.text[:150]}".replace("\n", " ")
    return False, last


def main() -> None:
    wav = SAMPLES / "klee-clip.wav"
    print(f"样例音频: {wav.name} ({wav.stat().st_size / 1024:.0f} KB)")

    results: dict[str, object] = {}

    for target, prefix in (
        ("qwen-audio-3.1-tts-flash", "lklee"),
        ("qwen-audio-3.1-realtime-plus", "lkleert"),
    ):
        print(f"\n===== 注册到 {target} (prefix={prefix}) =====")
        try:
            voice = enroll(target, prefix, wav)
            print(f"  voice_id = {voice}")
            results[target] = voice
        except Exception as exc:  # noqa: BLE001
            print(f"  注册失败: {exc}")
            results[target] = None

    print("\n===== 用新音色做非实时合成 =====")
    voice = results.get("qwen-audio-3.1-tts-flash")
    if voice:
        ok, detail = synth("qwen-audio-3.1-tts-flash", str(voice))
        print(f"  {'OK  ' if ok else 'FAIL'} {detail}")
        results["http_synth_with_clone"] = ok
    else:
        print("  跳过（没有注册出的音色）")
        results["http_synth_with_clone"] = False

    print("\n===== 汇总 =====")
    print(json.dumps(results, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
