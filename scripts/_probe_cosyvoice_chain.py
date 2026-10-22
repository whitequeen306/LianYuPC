#!/usr/bin/env python3
"""验证 CosyVoice 能否承担角色音色（用生产 key，不改任何配置）。

背景：生产账号用不了 qwen-audio-3.1-tts-flash（Engine error 411），但 cosyvoice-v3-flash
可用；而 CosyVoice 在「声音复刻支持模型」列表里。若这条链能跑通，就无需更换生产 key，
也无需在控制台开新模型。

验四步：注册 -> 轮询到 OK -> 整句合成 -> SSE 流式合成 -> 删除探针音色。
"""
from __future__ import annotations

import base64
import json
import sys
import time
from pathlib import Path

import paramiko
import requests

ROOT = Path(__file__).resolve().parents[1]
SAMPLE = ROOT / "backend/lianyu-service/src/main/resources/pet-voice-samples/klee-clip.wav"
BASE = "https://dashscope.aliyuncs.com/api/v1"
CUSTOM = f"{BASE}/services/audio/tts/customization"
SYNTH = f"{BASE}/services/audio/tts/SpeechSynthesizer"
MODEL = "cosyvoice-v3-flash"
PREFIX = "cvcchk"


def prod_key() -> str:
    env = {}
    for line in (ROOT / ".env").read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            env[k.strip()] = v.strip().strip('"')
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect("156.233.228.18", username="root", password=env["DEPLOY_SSH_PASSWORD"], timeout=30)
    _, out, _ = c.exec_command("grep -E '^DASHSCOPE_API_KEY=' /opt/lianyu/.env | cut -d= -f2-", timeout=60)
    key = out.read().decode("utf-8", errors="replace").strip()
    c.close()
    return key


def main() -> None:
    key = prod_key()
    if not key:
        sys.exit("生产 .env 没有 DASHSCOPE_API_KEY")
    H = {"Authorization": f"Bearer {key}", "Content-Type": "application/json"}
    print(f"key prefix={key[:10]}...  model={MODEL}")

    print("\n1) 注册音色")
    audio = base64.b64encode(SAMPLE.read_bytes()).decode()
    r = requests.post(CUSTOM, headers=H, timeout=300, json={
        "model": "voice-enrollment",
        "input": {"action": "create_voice", "target_model": MODEL,
                  "prefix": PREFIX, "audio": f"data:audio/wav;base64,{audio}"},
    })
    if r.status_code != 200:
        print(f"   FAIL http={r.status_code} {r.text[:250]}")
        return
    voice = (r.json().get("output") or {}).get("voice_id")
    print(f"   voice_id = {voice}")

    print("\n2) 轮询到 OK")
    for i in range(40):
        q = requests.post(CUSTOM, headers=H, timeout=60, json={
            "model": "voice-enrollment", "input": {"action": "query_voice", "voice_id": voice}})
        status = (q.json().get("output") or {}).get("status") if q.status_code == 200 else f"HTTP{q.status_code}"
        if status == "OK":
            print(f"   OK（第 {i + 1} 次）")
            break
        if status not in ("DEPLOYING", "PROCESSING"):
            print(f"   FAIL status={status} {q.text[:160]}")
            return
        time.sleep(1.5)
    else:
        print("   FAIL 超时")
        return

    print("\n3) 整句合成")
    r = requests.post(SYNTH, headers=H, timeout=120, json={
        "model": MODEL, "input": {"text": "你好，我是可莉，今天也要一起去冒险吗？",
                                  "voice": voice, "format": "wav", "sample_rate": 24000}})
    if r.status_code != 200:
        print(f"   FAIL http={r.status_code} {r.text[:200]}")
    else:
        url = ((r.json().get("output") or {}).get("audio") or {}).get("url")
        print(f"   {'OK  有 audio.url' if url else '200 但无 url'}")

    print("\n4) SSE 流式合成（语音通话要用的就是这条）")
    t0 = time.perf_counter()
    first = None
    chunks = 0
    total = 0
    try:
        with requests.post(SYNTH, timeout=120, stream=True,
                           headers={**H, "X-DashScope-SSE": "enable"},
                           json={"model": MODEL,
                                 "input": {"text": "你好，我是可莉。今天外面下雨了，你那边呢？",
                                           "voice": voice, "format": "pcm", "sample_rate": 24000}}) as resp:
            if resp.status_code != 200:
                print(f"   FAIL http={resp.status_code} {resp.text[:200]}")
            else:
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
                    data = ((obj.get("output") or {}).get("audio") or {}).get("data")
                    if not data:
                        continue
                    if first is None:
                        first = (time.perf_counter() - t0) * 1000
                    chunks += 1
                    total += len(base64.b64decode(data))
        print(f"   首包={None if first is None else round(first, 1)}ms  块={chunks}  字节={total}")
    except Exception as exc:  # noqa: BLE001
        print(f"   FAIL {exc}")

    print("\n5) 删除探针音色")
    d = requests.post(CUSTOM, headers=H, timeout=60, json={
        "model": "voice-enrollment", "input": {"action": "delete_voice", "voice_id": voice}})
    print(f"   http={d.status_code} {d.text[:120]}")


if __name__ == "__main__":
    main()
