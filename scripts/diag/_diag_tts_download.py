#!/usr/bin/env python3
"""Check if backend container can download DashScope TTS OSS audio URL."""
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
        sys.exit(1)

    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(HOST, username=USER, password=password, timeout=30)

    # synth from host, then wget from inside backend container
    cmd = r"""cd /opt/lianyu && set -a && . ./.env && set +a
URL=$(curl -sS -H "Authorization: Bearer ${DASHSCOPE_API_KEY}" -H "Content-Type: application/json" \
  -d '{"model":"qwen3-tts-vc-2026-01-22","input":{"text":"你好","voice":"qwen-tts-vc-raiden-voice-20260616133012759-26cf","language_type":"Chinese"}}' \
  https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['output']['audio']['url'])")
echo "AUDIO_URL=${URL:0:80}..."
echo "--- host curl download ---"
curl -sS -o /tmp/tts-host.wav -w 'host_bytes=%{size_download} code=%{http_code}\n' "$URL"
echo "--- backend container wget ---"
docker exec lianyu-backend sh -c "wget -qO /tmp/tts.wav '$URL' && wc -c /tmp/tts.wav || echo wget_failed"
"""
    _, stdout, _ = client.exec_command(cmd, timeout=120)
    print(stdout.read().decode("utf-8", errors="replace"))
    client.close()


if __name__ == "__main__":
    main()
