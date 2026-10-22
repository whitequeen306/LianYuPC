#!/usr/bin/env python3
"""用**生产那把 key** 把 13 个角色音色注册到 cosyvoice-v3-flash。

为什么不是 qwen-audio-3.1-tts-flash：
生产账号调它报 `[cosyvoice:]Engine error [411]: TTS speak operation failed`（该模型未开通），
而 `cosyvoice-v3-flash` 在生产账号上已验证可用（整句 + SSE 流式首包 536ms），且支持声音复刻。
音色按账号隔离，所以必须用生产 key 注册，否则部署后仍会报 voice does not exist。

Key 从服务器 /opt/lianyu/.env 读取，只放进当前进程环境变量，不落盘、不打印。
"""
from __future__ import annotations

import os
import runpy
import sys
from pathlib import Path

import paramiko

ROOT = Path(__file__).resolve().parents[1]
MODEL = "cosyvoice-v3-flash"
OUT_NAME = "re-enroll-cosyvoice-v3-flash.json"


def load_dotenv() -> dict:
    env = {}
    for line in (ROOT / ".env").read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            env[k.strip()] = v.strip().strip('"')
    return env


def prod_key(env: dict) -> str:
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect("156.233.228.18", username="root", password=env["DEPLOY_SSH_PASSWORD"], timeout=30)
    _, out, _ = c.exec_command("grep -E '^DASHSCOPE_API_KEY=' /opt/lianyu/.env | cut -d= -f2-", timeout=60)
    key = out.read().decode("utf-8", errors="replace").strip()
    c.close()
    return key


def main() -> None:
    env = load_dotenv()
    key = prod_key(env)
    if not key:
        sys.exit("生产 .env 没有 DASHSCOPE_API_KEY")
    os.environ["DSK"] = key
    os.environ["DS_MODEL"] = MODEL
    os.environ["DS_OUT_NAME"] = OUT_NAME
    print(f"用生产 key（prefix={key[:10]}...）注册到 {MODEL}")
    runpy.run_path(str(ROOT / "scripts/re_enroll_pet_voices.py"), run_name="__main__")


if __name__ == "__main__":
    main()
