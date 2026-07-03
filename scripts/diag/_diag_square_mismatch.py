#!/usr/bin/env python3
"""Compare character_square_template.avatar_url with MinIO square-avatars objects."""
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
    _, stdout, _ = client.exec_command(cmd, timeout=timeout, get_pty=True)
    return stdout.read().decode("utf-8", errors="replace")


def main() -> None:
    load_dotenv(ROOT / ".env")
    password = os.environ.get("DEPLOY_SSH_PASSWORD")
    if not password:
        sys.exit("Set DEPLOY_SSH_PASSWORD")

    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(HOST, username=USER, password=password, timeout=30)

    rows_raw = run(
        client,
        "cd /opt/lianyu && MYSQL_PWD=$(grep '^MYSQL_PASSWORD=' .env | cut -d= -f2-) && "
        'docker exec -e MYSQL_PWD="$MYSQL_PWD" lianyu-mysql mysql -ulianyu lianyu -N -e '
        '"SELECT slug, IFNULL(avatar_url,\'NULL\') FROM character_square_template '
        'WHERE is_enabled=1 ORDER BY sort_order;"',
    )
    minio_raw = run(
        client,
        "docker exec lianyu-minio sh -c 'ls /data/lianyu/square-avatars 2>/dev/null'",
    )
    minio_files = {ln.strip() for ln in minio_raw.splitlines() if ln.strip()}

    missing = []
    mismatch_ext = []
    ok = []
    null_url = []
    for line in rows_raw.splitlines():
        parts = line.split("\t")
        if len(parts) < 2:
            continue
        slug, url = parts[0].strip(), parts[1].strip()
        if url in ("NULL", ""):
            null_url.append(slug)
            continue
        key = url.replace("square-avatars/", "").strip()
        if key in minio_files:
            ok.append(slug)
            continue
        # same slug, different extension?
        stem = key.rsplit(".", 1)[0] if "." in key else key
        alt = next((f for f in minio_files if f.rsplit(".", 1)[0] == stem), None)
        if alt:
            mismatch_ext.append((slug, url, alt))
        else:
            missing.append((slug, url))

    print(f"enabled_templates={len(ok)+len(missing)+len(mismatch_ext)+len(null_url)}")
    print(f"minio_objects={len(minio_files)}")
    print(f"ok={len(ok)} mismatch_ext={len(mismatch_ext)} missing_file={len(missing)} null_url={len(null_url)}")
    if mismatch_ext:
        print("\n-- extension mismatch (DB vs MinIO) --")
        for slug, url, alt in mismatch_ext[:20]:
            print(f"  {slug}: db={url} minio={alt}")
        if len(mismatch_ext) > 20:
            print(f"  ... and {len(mismatch_ext)-20} more")
    if missing:
        print("\n-- completely missing in MinIO --")
        for slug, url in missing[:25]:
            print(f"  {slug}: {url}")
        if len(missing) > 25:
            print(f"  ... and {len(missing)-25} more")

    sizes_raw = run(
        client,
        "docker exec lianyu-minio sh -c 'ls -laS /data/lianyu/square-avatars | head -12'",
    )
    print("\n-- largest files --")
    print(sizes_raw.strip())
    client.close()


if __name__ == "__main__":
    main()
