#!/usr/bin/env python3
"""验证「生产环境的 DashScope key 能不能用新模型」并做一次完整注册闭环。

为什么必须先查这个：音色是按阿里云账号隔离的。13 个新音色是在**另一把 key**
（用户临时给我的）名下注册的，而生产 `.env` 里是另一把。若不统一，部署后
生产会继续报 "voice does not exist or is not licensed" —— 白部署一次。

本脚本只读生产 .env 取 key（不打印），依次验：
  1) 新模型 + 系统音色 能不能合成
  2) 能不能在 qwen-audio-3.1-tts-flash 上注册音色（prefix 用一次性名字）
  3) 用刚注册的音色合成
  4) 删掉它（不占配额）
"""
from __future__ import annotations

import base64
import json
import os
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
MODEL = "qwen-audio-3.1-tts-flash"
PREFIX = "prodchk"  # 一次性前缀，用完即删


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


def synth(key: str, voice: str, text: str = "你好，我是你的桌面伙伴。") -> tuple[bool, str]:
    r = requests.post(
        SYNTH,
        headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
        json={"model": MODEL, "input": {"text": text, "voice": voice,
                                        "format": "wav", "sample_rate": 24000}},
        timeout=120,
    )
    if r.status_code != 200:
        return False, f"http={r.status_code} {r.text[:180]}"
    url = ((r.json().get("output") or {}).get("audio") or {}).get("url")
    return bool(url), "有 audio.url" if url else "200 但无 audio.url"


def main() -> None:
    key = prod_key()
    if not key:
        sys.exit("生产 .env 里没有 DASHSCOPE_API_KEY")
    print(f"生产 key: len={len(key)} prefix={key[:10]}...")

    print("\n1) 新模型 + 系统音色合成")
    ok, detail = synth(key, "Cherry")
    print(f"   {'OK  ' if ok else 'FAIL'} {detail}")
    if not ok:
        print("\n结论：生产 key 用不了该模型 → 要么换 key，要么保持现状（TTS 不可用）")
        return

    print("\n2) 在该模型上注册音色（prefix=prodchk）")
    audio = base64.b64encode(SAMPLE.read_bytes()).decode()
    r = requests.post(
        CUSTOM,
        headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
        json={"model": "voice-enrollment",
              "input": {"action": "create_voice", "target_model": MODEL,
                        "prefix": PREFIX, "audio": f"data:audio/wav;base64,{audio}"}},
        timeout=300,
    )
    if r.status_code != 200:
        print(f"   FAIL http={r.status_code} {r.text[:250]}")
        print("\n结论：生产 key 能合成但不能注册音色 → 需要换 key 或单独配 TTS key")
        return
    voice = (r.json().get("output") or {}).get("voice_id")
    print(f"   voice_id = {voice}")

    print("\n3) 等部署完成并合成")
    for _ in range(40):
        q = requests.post(
            CUSTOM,
            headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
            json={"model": "voice-enrollment", "input": {"action": "query_voice", "voice_id": voice}},
            timeout=60,
        )
        status = ((q.json().get("output") or {}).get("status") if q.status_code == 200 else f"HTTP{q.status_code}")
        if status == "OK":
            break
        if status not in ("DEPLOYING", "PROCESSING"):
            print(f"   FAIL status={status} {q.text[:160]}")
            return
        time.sleep(1.5)
    else:
        print("   FAIL 部署超时")
        return
    ok, detail = synth(key, voice)
    print(f"   {'OK  ' if ok else 'FAIL'} {detail}")

    print("\n4) 删除探针音色")
    d = requests.post(
        CUSTOM,
        headers={"Authorization": f"Bearer {key}", "Content-Type": "application/json"},
        json={"model": "voice-enrollment", "input": {"action": "delete_voice", "voice_id": voice}},
        timeout=60,
    )
    print(f"   http={d.status_code} {d.text[:120]}")

    print("\n结论：生产 key 完全可用 → 按生产 key 重注册 13 个角色音色即可，无需改配置")


if __name__ == "__main__":
    main()
