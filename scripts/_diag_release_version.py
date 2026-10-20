#!/usr/bin/env python3
"""线上只读：看当前发布的客户端版本（MinIO updates/latest.yml）。"""
import os
import sys
from pathlib import Path

import paramiko

ROOT = Path(__file__).resolve().parents[1]


def load_dotenv(path: Path) -> None:
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        os.environ.setdefault(k.strip(), v.strip().strip('"'))


def run(c, cmd: str, timeout: int = 60) -> str:
    _in, out, err = c.exec_command(cmd, timeout=timeout)
    return out.read().decode("utf-8", "replace") + err.read().decode("utf-8", "replace")


def main() -> None:
    load_dotenv(ROOT / ".env")
    password = os.environ.get("DEPLOY_SSH_PASSWORD")
    if not password:
        sys.exit("missing DEPLOY_SSH_PASSWORD")
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(os.environ.get("DEPLOY_SSH_HOST") or "156.233.228.18",
              username=os.environ.get("DEPLOY_SSH_USER") or "root",
              password=password, timeout=30)

    print("=== 线上最新发布版本（经网关取 latest.yml）===")
    print(run(c, "curl -sk --max-time 20 https://127.0.0.1/api/public/files/updates/latest.yml | head -12"))

    print("\n=== MinIO updates/ 前缀下的安装包 ===")
    print(run(c, "docker exec lianyu-minio sh -lc "
                 "'ls -1 /data/updates 2>/dev/null | tail -12' 2>&1 | head -14"))

    c.close()


if __name__ == "__main__":
    main()
