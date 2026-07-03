#!/usr/bin/env python3
"""Run DashScope pet TTS smoke test on cloud server via curl."""
import os
import sys
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


def main() -> None:
    load_dotenv(ROOT / ".env")
    password = os.environ.get("DEPLOY_SSH_PASSWORD")
    if not password:
        print("Set DEPLOY_SSH_PASSWORD", file=sys.stderr)
        sys.exit(1)

    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(HOST, username=USER, password=password, timeout=30)

    cmd = r"""cd /opt/lianyu && set -a && . ./.env && set +a && curl -sS -w '\nHTTP_CODE:%{http_code}\n' \
  -H "Authorization: Bearer ${DASHSCOPE_API_KEY}" \
  -H "Content-Type: application/json" \
  -d '{"model":"qwen3-tts-vc-2026-01-22","input":{"text":"测试语音","voice":"qwen-tts-vc-raiden-voice-20260616133012759-26cf","language_type":"Chinese"}}' \
  https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation | tail -c 1200"""

    print("$ cloud TTS smoke (raiden)")
    _, stdout, stderr = client.exec_command(cmd, timeout=90)
    out = stdout.read().decode("utf-8", errors="replace")
    err = stderr.read().decode("utf-8", errors="replace")
    print(out or err)
    client.close()


if __name__ == "__main__":
    main()
