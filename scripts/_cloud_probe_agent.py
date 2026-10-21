#!/usr/bin/env python3
"""agent 层补验：新端点存在性（容器内无 curl，用 python 探）+ 启动后日志异常。

探针语义：端点存在 → 空 body 应回 4xx（422 缺字段），不存在 → 404。
"""
import sys
from pathlib import Path

import paramiko

ROOT = Path(__file__).resolve().parents[1]

PROBE = (
    "import json,urllib.request,urllib.error,sys\n"
    "def probe(path):\n"
    "    req=urllib.request.Request('http://127.0.0.1:8001'+path,data=b'{}',"
    "headers={'Content-Type':'application/json'},method='POST')\n"
    "    try:\n"
    "        r=urllib.request.urlopen(req,timeout=10); return r.status\n"
    "    except urllib.error.HTTPError as e: return e.code\n"
    "    except Exception as e: return 'ERR:'+type(e).__name__\n"
    "for p in ['/agent/voice/enroll','/agent/tts/synthesize','/agent/chat-turn/inspect',"
    "'/agent/chat-turn/stream','/agent/does-not-exist']:\n"
    "    print(p, '->', probe(p))\n"
)


def load_env() -> dict:
    env = {}
    for line in (ROOT / ".env").read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            env[k.strip()] = v.strip().strip('"')
    return env


def run(c, cmd, timeout=300, must=False):
    print(f"\n$ {cmd[:160]}", flush=True)
    _, stdout, _ = c.exec_command(cmd, timeout=timeout, get_pty=True)
    out = stdout.read().decode("utf-8", errors="replace")
    code = stdout.channel.recv_exit_status()
    sys.stdout.buffer.write(out[-5000:].encode("utf-8", errors="replace"))
    if code != 0 and must:
        c.close()
        sys.exit(code)
    return out


def main() -> None:
    env = load_env()
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect("156.233.228.18", username="root",
              password=env["DEPLOY_SSH_PASSWORD"], timeout=30)

    sftp = c.open_sftp()
    with sftp.file("/tmp/agent_probe.py", "w") as f:
        f.write(PROBE)
    sftp.close()

    print("=" * 70)
    print("1. 端点存在性（agent / agent2）")
    print("=" * 70)
    for ctr in ("lianyu-agent", "lianyu-agent-2"):
        run(c, f"docker cp /tmp/agent_probe.py {ctr}:/tmp/probe.py && "
               f"docker exec {ctr} python /tmp/probe.py")

    print("\n" + "=" * 70)
    print("2. 启动以来日志异常")
    print("=" * 70)
    for ctr in ("lianyu-agent", "lianyu-agent-2"):
        run(c, f"docker logs {ctr} --since 10m 2>&1 | grep -icE 'traceback' || echo {ctr}_traceback=0")
        run(c, f"docker logs {ctr} --since 10m 2>&1 | grep -iE 'error|exception' | tail -8 || echo {ctr}_no_error")

    print("\n" + "=" * 70)
    print("3. 镜像 revision / 启动时间")
    print("=" * 70)
    run(c, "docker inspect lianyu-agent --format 'agent  img={{.Image}} started={{.State.StartedAt}}'")
    run(c, "docker inspect lianyu-agent-2 --format 'agent2 img={{.Image}} started={{.State.StartedAt}}'")
    run(c, "docker compose -f /opt/lianyu/docker-compose.yml ps --format 'table {{.Name}}\\t{{.Status}}' 2>/dev/null | head -15")

    c.close()
    print("\nAGENT_PROBE_DONE")


if __name__ == "__main__":
    main()
