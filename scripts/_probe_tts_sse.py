#!/usr/bin/env python3
"""量 qwen-audio-3.1-tts-flash + 复刻音色的 SSE 流式首包延迟。

语音通话要"流畅"，真正的指标是**首包延迟（TTFA）**与分块连续性，
不是端到端总时长。这里逐块打时间戳，输出：
  首包 ms / 总时长 ms / 块数 / 总字节 / 块间隔统计

用法: python scripts/_probe_tts_sse.py
需要环境变量 DSK，可选 DSVOICE（默认用探针注册出来的 klee 复刻音色）。
"""
from __future__ import annotations

import json
import os
import statistics
import sys
import time

import requests

BASE = "https://dashscope.aliyuncs.com/api/v1"
SPEECH_SYNTH = f"{BASE}/services/audio/tts/SpeechSynthesizer"

KEY = (os.environ.get("DSK") or "").strip()
VOICE = (os.environ.get("DSVOICE") or "qwen-audio-3.1-tts-flash-lklee-d1f9b79d44104dffbb1138da8bdf49a1").strip()
MODEL = os.environ.get("DSMODEL") or "qwen-audio-3.1-tts-flash"
TEXT = os.environ.get("DSTEXT") or "你好，我是林晚。今天外面下雨了，你那边呢？想听你多说两句。"

if not KEY:
    sys.exit("缺少环境变量 DSK")


def run(label: str, model: str, voice: str) -> dict:
    headers = {
        "Authorization": f"Bearer {KEY}",
        "Content-Type": "application/json",
        "X-DashScope-SSE": "enable",
    }
    payload = {
        "model": model,
        "input": {"text": TEXT, "voice": voice, "format": "pcm", "sample_rate": 24000},
    }
    t0 = time.perf_counter()
    chunks: list[tuple[float, int]] = []

    with requests.post(SPEECH_SYNTH, headers=headers, json=payload, stream=True, timeout=120) as resp:
        if resp.status_code != 200:
            print(f"  [{label}] http={resp.status_code} {resp.text[:200]}")
            return {"label": label, "ok": False, "status": resp.status_code}
        for line in resp.iter_lines():
            if not line:
                continue
            text = line.decode("utf-8", errors="replace")
            if not text.startswith("data:"):
                continue
            body = text[5:].strip()
            if body in ("", "[DONE]"):
                continue
            try:
                obj = json.loads(body)
            except json.JSONDecodeError:
                continue
            audio = obj.get("output", {}).get("audio") or {}
            data = audio.get("data")
            if not data:
                continue
            import base64
            nbytes = len(base64.b64decode(data))
            chunks.append((time.perf_counter() - t0, nbytes))

    total_ms = (time.perf_counter() - t0) * 1000
    if not chunks:
        print(f"  [{label}] 没有任何音频块")
        return {"label": label, "ok": False, "status": 200, "chunks": 0}

    gaps = []
    for i in range(1, len(chunks)):
        gaps.append((chunks[i][0] - chunks[i - 1][0]) * 1000)

    result = {
        "label": label,
        "ok": True,
        "model": model,
        "voice": voice[:50],
        "firstChunkMs": round(chunks[0][0] * 1000, 1),
        "totalMs": round(total_ms, 1),
        "chunks": len(chunks),
        "totalBytes": sum(c[1] for c in chunks),
        "maxGapMs": round(max(gaps), 1) if gaps else 0,
        "medianGapMs": round(statistics.median(gaps), 1) if gaps else 0,
    }
    print(f"  [{label}] {json.dumps(result, ensure_ascii=False)}")
    return result


def main() -> None:
    print(f"模型={MODEL}\n音色={VOICE[:60]}\n文本={TEXT} ({len(TEXT)} 字)\n")
    results = []
    # 跑三次看稳定性（首包延迟抖动很影响体感）
    for i in range(3):
        results.append(run(f"第{i + 1}次", MODEL, VOICE))
        time.sleep(1)

    ok = [r for r in results if r.get("ok")]
    if ok:
        print("\n===== 汇总 =====")
        print(f"  首包 ms: {[r['firstChunkMs'] for r in ok]}")
        print(f"  总时长 ms: {[r['totalMs'] for r in ok]}")
        print(f"  音频总字节: {[r['totalBytes'] for r in ok]}  块数: {[r['chunks'] for r in ok]}")
    else:
        print("\n全部失败")


if __name__ == "__main__":
    main()
