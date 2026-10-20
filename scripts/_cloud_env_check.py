#!/usr/bin/env python3
"""生产 .env 键核对（单行命令，不打印值）。"""
import os
import sys
from pathlib import Path

import paramiko

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


KEYS = ["DASHSCOPE_API_KEY", "MYSQL_USER", "MYSQL_PASSWORD", "REDIS_PASSWORD",
        "EMBEDDING_API_KEY", "RERANKER_API_KEY", "LIANYU_AGENT_BASE_URL",
        "MULTIMODAL_BASE_URL", "MULTIMODAL_MODEL", "LIANYU_MASTER_KEY"]

CHECK = ("cd /opt/lianyu && " + " && ".join(
    f'grep -qE "^{k}=" .env && printf "  {k}: SET\\n" || printf "  {k}: MISSING\\n"'
    for k in KEYS)
    + ' ; grep -qE "^LIANYU_AGENT_MODE=" .env && printf "  LIANYU_AGENT_MODE: PRESENT (obsolete)\\n" || printf "  LIANYU_AGENT_MODE: absent (good)\\n"')


def main() -> None:
    load_dotenv(ROOT / ".env")
    password = os.environ.get("DEPLOY_SSH_PASSWORD")
    if not password:
        sys.exit("missing DEPLOY_SSH_PASSWORD")
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect("156.233.228.18", username="root", password=password, timeout=30)
    _, stdout, _ = c.exec_command(CHECK, timeout=60)
    print(stdout.read().decode("utf-8", errors="replace"))
    code = stdout.channel.recv_exit_status()
    c.close()
    sys.exit(code)


if __name__ == "__main__":
    main()
