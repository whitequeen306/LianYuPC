#!/usr/bin/env python3
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
        k, v = line.split("=", 1)
        os.environ.setdefault(k.strip(), v.strip().strip('"'))


def main() -> None:
    load_dotenv(ROOT / ".env")
    password = os.environ.get("DEPLOY_SSH_PASSWORD")
    if not password:
        sys.exit("missing DEPLOY_SSH_PASSWORD")

    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect("154.219.111.30", username="root", password=password, timeout=30)

    cmds = [
        "docker logs lianyu-backend 2>&1 | grep -iE 'WebSocket|STOMP|CONNECT rejected|SUBSCRIBE' | tail -30",
        "docker logs lianyu-api-gateway 2>&1 | tail -20",
        "curl -sk -o /dev/null -w 'gateway_ws=%{http_code}\\n' -H 'Connection: Upgrade' -H 'Upgrade: websocket' -H 'Sec-WebSocket-Version: 13' -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' -H 'Origin: https://154.219.111.30' https://127.0.0.1/ws",
        "curl -s -o /dev/null -w 'backend_ws=%{http_code}\\n' -H 'Connection: Upgrade' -H 'Upgrade: websocket' -H 'Sec-WebSocket-Version: 13' -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' -H 'Origin: https://154.219.111.30' http://127.0.0.1:8080/ws",
        "curl -s -o /dev/null -w 'backend_ws_no_origin=%{http_code}\\n' -H 'Connection: Upgrade' -H 'Upgrade: websocket' -H 'Sec-WebSocket-Version: 13' -H 'Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==' http://127.0.0.1:8080/ws",
    ]
    for cmd in cmds:
        print(">>>", cmd[:100])
        _, o, e = c.exec_command(cmd, timeout=120)
        out = o.read().decode("utf-8", errors="replace")
        err = e.read().decode("utf-8", errors="replace")
        if out.strip():
            print(out[-4000:])
        if err.strip():
            print(err[-1000:], file=sys.stderr)
        print()

    c.close()


if __name__ == "__main__":
    main()
