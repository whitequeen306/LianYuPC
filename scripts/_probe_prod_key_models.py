#!/usr/bin/env python3
"""分清「生产 key 无效」还是「账号没开通 qwen-audio-3.1-tts-flash」。

对照三组（都用生产 .env 里的 key，不打印）：
  A. qwen3-tts-flash        + 系统音色 Cherry    → key 本身是否还能合成
  B. cosyvoice-v3-flash     + 系统音色 longanyang → 另一个支持复刻的家族是否可用
  C. qwen-audio-3.1-tts-flash + Cherry           → 已知 411 的那个（复测）

结论决定走哪条路：
  · A 通 + C 不通 → 账号没开通 qwen-audio，需在控制台开通，或改用 CosyVoice（若 B 通）
  · B 通 → 可以用 cosyvoice-v3-flash 做复刻音色，不必换 key
  · 全不通 → 生产 key 已失效，必须换 key
"""
from __future__ import annotations

import sys
from pathlib import Path

import paramiko
import requests

ROOT = Path(__file__).resolve().parents[1]
SYNTH = "https://dashscope.aliyuncs.com/api/v1/services/audio/tts/SpeechSynthesizer"
LEGACY = "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation"


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


def try_synth(key: str, label: str, url: str, model: str, voice: str, payload_style: str) -> None:
    if payload_style == "new":
        body = {"model": model, "input": {"text": "你好，测试一句话。", "voice": voice,
                                          "format": "wav", "sample_rate": 24000}}
    else:
        body = {"model": model, "input": {"text": "你好，测试一句话。", "voice": voice,
                                          "language_type": "Chinese"}}
    try:
        r = requests.post(url, headers={"Authorization": f"Bearer {key}",
                                        "Content-Type": "application/json"},
                          json=body, timeout=120)
    except Exception as exc:  # noqa: BLE001
        print(f"[{label}] 传输失败 {exc}")
        return
    if r.status_code == 200:
        url_out = ((r.json().get("output") or {}).get("audio") or {}).get("url")
        print(f"[{label}] OK  {'有 audio.url' if url_out else '200 但无 url'}")
    else:
        print(f"[{label}] FAIL http={r.status_code} {r.text[:170]}")


def main() -> None:
    key = prod_key()
    if not key:
        sys.exit("生产 .env 没有 DASHSCOPE_API_KEY")
    print(f"生产 key: prefix={key[:10]}... len={len(key)}\n")

    try_synth(key, "A. qwen3-tts-flash / Cherry（旧端点）", LEGACY, "qwen3-tts-flash", "Cherry", "old")
    try_synth(key, "B. cosyvoice-v3-flash / longanyang（新端点）", SYNTH, "cosyvoice-v3-flash", "longanyang", "new")
    try_synth(key, "C. qwen-audio-3.1-tts-flash / Cherry（新端点）", SYNTH, "qwen-audio-3.1-tts-flash", "Cherry", "new")


if __name__ == "__main__":
    main()
