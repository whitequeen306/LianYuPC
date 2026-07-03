#!/usr/bin/env python3
"""Diagnose slow square avatar image loading (read-only)."""
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


def run(client, cmd, timeout=120):
    print(f"\n$ {cmd}", flush=True)
    _, stdout, _ = client.exec_command(cmd, timeout=timeout, get_pty=True)
    out = stdout.read().decode("utf-8", errors="replace")
    sys.stdout.write(out)
    stdout.channel.recv_exit_status()
    return out


def main() -> None:
    load_dotenv(ROOT / ".env")
    password = os.environ.get("DEPLOY_SSH_PASSWORD")
    if not password:
        print("Set DEPLOY_SSH_PASSWORD in .env", file=sys.stderr)
        sys.exit(1)

    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(HOST, username=USER, password=password, timeout=30)

    run(client, "free -h")
    run(client, "uptime")
    run(client, "docker stats --no-stream --format 'table {{.Name}}\\t{{.CPUPerc}}\\t{{.MemUsage}}\\t{{.MemPerc}}'")

    # backend JVM 线程总数（含 Tomcat worker），观察是否被打满
    run(client, "echo backend_threads=$(docker exec lianyu-backend sh -c 'ls /proc/1/task 2>/dev/null | wc -l')")

    # 取一个真实广场头像 key
    listing = run(
        client,
        "docker exec lianyu-minio sh -c 'ls /data/lianyu/square-avatars 2>/dev/null | head -3'",
    )
    files = [ln.strip() for ln in listing.splitlines() if ln.strip() and "." in ln]
    key = files[-1] if files else ""
    print(f"\n[picked] square-avatars/{key}")

    if key:
        gw = f"https://127.0.0.1/api/public/files/square-avatars/{key}"
        run(
            client,
            "for i in 1 2 3; do curl -k -s -o /dev/null -w "
            "'gateway code=%{http_code} ttfb=%{time_starttransfer}s total=%{time_total}s "
            "size=%{size_download}B\\n' "
            f"'{gw}'; done",
        )
        be = f"http://127.0.0.1:8080/api/public/files/square-avatars/{key}"
        run(
            client,
            "for i in 1 2 3; do curl -s -o /dev/null -w "
            "'backend code=%{http_code} ttfb=%{time_starttransfer}s total=%{time_total}s\\n' "
            f"'{be}'; done",
        )

    # 并发 12 张（模拟首屏）总耗时
    if key:
        gw = f"https://127.0.0.1/api/public/files/square-avatars/{key}"
        run(
            client,
            "start=$(date +%s.%N); "
            "for i in $(seq 1 12); do curl -k -s -o /dev/null "
            f"'{gw}' & done; wait; "
            "end=$(date +%s.%N); echo concurrent12_total=$(echo \"$end - $start\" | bc)s",
        )

    run(client, "docker logs --since 30m lianyu-backend 2>&1 | grep -iE 'public file|stream object|OutOfMemory|GC overhead|timeout' | tail -20 || true")
    run(client, "docker exec lianyu-minio sh -c 'ls /data/lianyu/square-avatars 2>/dev/null | wc -l'")

    # MySQL 模板数 vs MinIO 对象数；列出 DB 有 avatar_url 但 MinIO 缺文件的 slug
    run(
        client,
        "cd /opt/lianyu && MYSQL_PWD=$(grep '^MYSQL_PASSWORD=' .env | cut -d= -f2-) && "
        "docker exec -e MYSQL_PWD=\"$MYSQL_PWD\" lianyu-mysql mysql -ulianyu lianyu -N -e "
        "\"SELECT COUNT(*) FROM character_square_template WHERE is_enabled=1;\"",
    )
    run(
        client,
        "cd /opt/lianyu && MYSQL_PWD=$(grep '^MYSQL_PASSWORD=' .env | cut -d= -f2-) && "
        "docker exec -e MYSQL_PWD=\"$MYSQL_PWD\" lianyu-mysql mysql -ulianyu lianyu -N -e "
        "\"SELECT slug, avatar_url FROM character_square_template WHERE is_enabled=1 AND avatar_url IS NOT NULL ORDER BY sort_order;\" "
        "> /tmp/sq_avatars.txt && "
        "missing=0; total=0; "
        "while IFS=$'\\t' read -r slug url; do "
        "  total=$((total+1)); "
        "  key=\"${url#square-avatars/}\"; "
        "  if [ -z \"$key\" ]; then continue; fi; "
        "  if ! docker exec lianyu-minio sh -c \"test -f /data/lianyu/square-avatars/$key\" 2>/dev/null; then "
        "    echo MISSING $slug -> $url; missing=$((missing+1)); "
        "  fi; "
        "done < /tmp/sq_avatars.txt; "
        "echo summary total=$total missing=$missing",
        timeout=180,
    )

    run(client, "df -h / | tail -2")
    client.close()
    print("\nDIAG_DONE")


if __name__ == "__main__":
    main()
