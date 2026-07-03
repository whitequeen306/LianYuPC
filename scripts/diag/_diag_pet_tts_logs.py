#!/usr/bin/env python3
"""Fetch backend logs related to desktop observe / pet TTS."""
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


def run(client: paramiko.SSHClient, cmd: str, timeout: int = 120) -> str:
    print(f"$ {cmd}", flush=True)
    _, stdout, stderr = client.exec_command(cmd, timeout=timeout)
    out = stdout.read().decode("utf-8", errors="replace")
    err = stderr.read().decode("utf-8", errors="replace")
    text = (out + err).strip()
    print(text or "(empty)")
    print()
    return text


def main() -> None:
    load_dotenv(ROOT / ".env")
    password = os.environ.get("DEPLOY_SSH_PASSWORD")
    if not password:
        print("Set DEPLOY_SSH_PASSWORD in .env", file=sys.stderr)
        sys.exit(1)

    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(HOST, username=USER, password=password, timeout=30)

    run(client, "docker logs lianyu-backend 2>&1 | grep -iE 'Pet TTS|pet voice|pet-voices' | tail -50")
    run(client, "docker logs lianyu-backend --since 12h 2>&1 | grep -i WARN | grep -iE 'TTS|tts|Pet' | tail -50")
    run(client, "docker logs lianyu-backend --since 12h 2>&1 | grep -iE 'Proactive chat|Cold open|notifyProactive' | tail -50")
    run(
        client,
        "docker exec lianyu-backend sh -c \"printenv | grep -iE 'DASHSCOPE|LIANYU_AI_TTS|VISION' | sed 's/=.*/=***/'\"",
    )
    run(client, "docker logs lianyu-backend --tail 200 2>&1")

    client.close()


if __name__ == "__main__":
    main()
