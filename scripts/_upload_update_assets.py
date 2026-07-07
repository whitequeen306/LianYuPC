#!/usr/bin/env python3
"""Upload Electron update artifacts to the cloud MinIO bucket via SSH.

The runtime updater reads assets from /api/public/files/updates/*. This script keeps
latest.yml as the final write so clients never see a manifest before its files exist.
"""
import argparse
import os
from pathlib import Path

import paramiko

HOST = "154.219.111.30"
USER = "root"
ROOT = Path(__file__).resolve().parents[1]
BUCKET = "lianyu"


def load_dotenv(path: Path) -> None:
    if not path.is_file():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip('"'))


def run(client: paramiko.SSHClient, cmd: str, timeout: int = 120) -> None:
    print(f"$ {cmd}", flush=True)
    _, stdout, stderr = client.exec_command(cmd, timeout=timeout, get_pty=True)
    out = stdout.read().decode("utf-8", errors="replace")
    err = stderr.read().decode("utf-8", errors="replace")
    code = stdout.channel.recv_exit_status()
    if out.strip():
        print(out[-3000:])
    if code != 0:
        if err.strip():
            print(err[-3000:])
        raise SystemExit(code)


def artifact_paths(version: str) -> list[tuple[Path, str]]:
    release_dir = ROOT / "frontend" / "release" / f"v{version}"
    installer = release_dir / f"LianYu Setup {version}.exe"
    blockmap = release_dir / f"LianYu Setup {version}.exe.blockmap"
    latest = release_dir / "latest.yml"
    targets = [
        (installer, f"LianYu-Setup-{version}.exe"),
        (blockmap, f"LianYu-Setup-{version}.exe.blockmap"),
        (latest, "latest.yml"),
    ]
    missing = [str(src) for src, _ in targets if not src.is_file()]
    if missing:
        raise SystemExit("missing update artifact(s): " + ", ".join(missing))
    return targets


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", required=True)
    args = parser.parse_args()

    load_dotenv(ROOT / ".env")
    password = os.environ.get("DEPLOY_SSH_PASSWORD")
    if not password:
        raise SystemExit("Set DEPLOY_SSH_PASSWORD in .env or environment")

    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(HOST, username=USER, password=password, timeout=30)

    remote_tmp = f"/tmp/lianyu-update-assets-{args.version}"
    run(client, f"rm -rf {remote_tmp} && mkdir -p {remote_tmp}")
    sftp = client.open_sftp()
    try:
        for src, target in artifact_paths(args.version):
            remote_path = f"{remote_tmp}/{target}"
            print(f"upload {src.name} -> {remote_path}", flush=True)
            sftp.put(str(src), remote_path)
    finally:
        sftp.close()

    run(client, "docker exec lianyu-minio sh -lc 'rm -rf /tmp/lianyu-update-assets && mkdir -p /tmp/lianyu-update-assets'")
    # latest.yml is copied last; order matters for clients polling latest.
    for _src, target in artifact_paths(args.version):
        run(client, f"docker cp {remote_tmp}/{target} lianyu-minio:/tmp/lianyu-update-assets/{target}", timeout=600)
        run(
            client,
            "docker exec lianyu-minio sh -lc '"
            "mc alias set local http://127.0.0.1:9000 \"$MINIO_ROOT_USER\" \"$MINIO_ROOT_PASSWORD\" >/dev/null && "
            f"mc cp /tmp/lianyu-update-assets/{target} local/{BUCKET}/updates/{target}"
            "'",
            timeout=600,
        )
    run(client, "docker exec lianyu-minio sh -lc 'rm -rf /tmp/lianyu-update-assets'")
    run(client, f"rm -rf {remote_tmp}")
    run(client, "curl -k -s -o /dev/null -w 'latest=%{http_code}' https://154.219.111.30/api/public/files/updates/latest.yml")
    client.close()
    print("UPDATE_ASSETS_UPLOADED")


if __name__ == "__main__":
    main()
