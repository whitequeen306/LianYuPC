#!/usr/bin/env python3
"""Deploy updater proxy: inject GH token + pull + rebuild backend."""
import os, sys, subprocess, pathlib

import paramiko

HOST = "154.219.111.30"
USER = "root"
ROOT = pathlib.Path(__file__).resolve().parents[1]

def load_dotenv(path):
    if not path.is_file(): return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line: continue
        k, v = line.split("=", 1)
        os.environ.setdefault(k.strip(), v.strip().strip('"'))

def get_github_token():
    """Retrieve GitHub token from Windows Credential Manager via git credential fill."""
    cred_input = "protocol=https\nhost=github.com\n\n"
    r = subprocess.run(["git", "credential", "fill"], input=cred_input, capture_output=True, text=True, timeout=30)
    for line in r.stdout.splitlines():
        if line.startswith("password="):
            return line[len("password="):].strip()
    return None

def run(client, cmd, timeout=900):
    print(f"$ {cmd}", flush=True)
    _, stdout, stderr = client.exec_command(cmd, timeout=timeout, get_pty=True)
    out = stdout.read().decode("utf-8", errors="replace")
    code = stdout.channel.recv_exit_status()
    if out.strip():
        sys.stdout.buffer.write(out[-5000:].encode("utf-8", errors="replace"))
        sys.stdout.buffer.write(b"\n")
        sys.stdout.buffer.flush()
    if code != 0:
        print(f"ERROR: exit code {code}", file=sys.stderr)
        raise SystemExit(code)
    return out

def main():
    load_dotenv(ROOT / ".env")
    password = os.environ.get("DEPLOY_SSH_PASSWORD")
    if not password:
        print("DEPLOY_SSH_PASSWORD not set in .env", file=sys.stderr); sys.exit(1)

    token = get_github_token()
    if not token:
        print("Could not retrieve GitHub token from credential manager", file=sys.stderr); sys.exit(1)
    print(f"GitHub token retrieved (length={len(token)})")

    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    print(f"Connecting to {USER}@{HOST} ...")
    client.connect(HOST, username=USER, password=password, timeout=30)
    print("Connected.")

    # 1. Inject LIANYU_GITHUB_TOKEN into /opt/lianyu/.env if not present
    check_cmd = "grep -q 'LIANYU_GITHUB_TOKEN=' /opt/lianyu/.env && echo EXISTS || echo MISSING"
    result = run(client, check_cmd)
    if "MISSING" in result:
        print("Injecting LIANYU_GITHUB_TOKEN into /opt/lianyu/.env ...")
        # Escape single quotes in token (shouldn't have any, but be safe)
        safe_token = token.replace("'", "'\\''")
        run(client, f"echo 'LIANYU_GITHUB_TOKEN={safe_token}' >> /opt/lianyu/.env")
        print("Token injected.")
    else:
        print("LIANYU_GITHUB_TOKEN already in .env, skipping.")

    # 2. Git pull
    run(client, "cd /opt/lianyu && git pull origin main")

    # 3. Rebuild backend
    run(client, "cd /opt/lianyu && docker compose up -d --build backend", timeout=1200)

    # 4. Health check
    import time
    for attempt in range(12):
        _, stdout, _ = client.exec_command(
            "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/api/auth/captcha", timeout=30)
        out = stdout.read().decode("utf-8", errors="replace").strip()
        print(f"health attempt {attempt+1}: {out}")
        if "200" in out: break
        if attempt < 11: time.sleep(15)
    else:
        raise SystemExit(56)

    # 5. Test the updater endpoint
    _, stdout, _ = client.exec_command(
        "curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/api/public/updater/latest.yml", timeout=30)
    out = stdout.read().decode("utf-8", errors="replace").strip()
    print(f"updater latest.yml endpoint: HTTP {out}")

    run(client, "docker ps --format 'table {{.Names}}\t{{.Status}}' | head -8")
    client.close()
    print("DEPLOY_DONE")

if __name__ == "__main__":
    main()
