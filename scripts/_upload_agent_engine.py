#!/usr/bin/env python3
"""Upload the hosted AgentEngine zip + agent-latest.yml to MinIO updates/.

Unlike Electron installers, the engine is NOT published to GitHub Releases.
The LianYu client reads /api/public/files/updates/agent-latest.yml, downloads
the zip from the same prefix, verifies sha256, and extracts it locally.

Manifest is written last so clients never see a pointer before the zip exists.
"""
from __future__ import annotations

import argparse
import hashlib
import os
import re
import shlex
import tempfile
from pathlib import Path

import paramiko

HOST = "156.233.228.18"
USER = "root"
ROOT = Path(__file__).resolve().parents[1]
BUCKET = "lianyu"
VERSION_RE = re.compile(r"^\d+\.\d+\.\d+$")
ENGINE_ZIP_RE = re.compile(r"^AgentEngine-hosted-win-x64-(\d+\.\d+\.\d+)\.zip$")
RETENTION_RELEASES = 2


def load_dotenv(path: Path) -> None:
    if not path.is_file():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip('"'))


def run(client: paramiko.SSHClient, cmd: str, timeout: int = 120, *, label: str | None = None) -> None:
    print(f"$ {label or cmd}", flush=True)
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


def connect(password: str) -> paramiko.SSHClient:
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(HOST, username=USER, password=password, timeout=30)
    return client


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_manifest(path: Path, *, version: str, filename: str, sha256: str, size: int) -> None:
    path.write_text(
        (
            f"version: {version}\n"
            f"url: {filename}\n"
            f"sha256: {sha256}\n"
            f"size: {size}\n"
        ),
        encoding="utf-8",
    )


def sftp_put(client: paramiko.SSHClient, local: Path, remote: str) -> None:
    print(f"sftp {local} -> {remote} ({local.stat().st_size} bytes)", flush=True)
    sftp = client.open_sftp()
    try:
        sftp.put(str(local), remote)
    finally:
        sftp.close()


def mc_cp(client: paramiko.SSHClient, container_src: str, object_name: str, timeout: int = 600) -> None:
    run(
        client,
        "docker exec lianyu-minio sh -lc '"
        "mc alias set local http://127.0.0.1:9000 \"$MINIO_ROOT_USER\" \"$MINIO_ROOT_PASSWORD\" >/dev/null && "
        f"mc cp {shlex.quote(container_src)} local/{BUCKET}/updates/{object_name}"
        "'",
        timeout=timeout,
        label=f"mc cp {object_name}",
    )


def configure_update_assets_public_read(client: paramiko.SSHClient) -> None:
    run(
        client,
        "docker exec lianyu-minio sh -lc '"
        "mc alias set local http://127.0.0.1:9000 \"$MINIO_ROOT_USER\" \"$MINIO_ROOT_PASSWORD\" >/dev/null && "
        f"mc anonymous set download local/{BUCKET}/updates"
        "'",
        timeout=120,
    )


def cleanup_old_engine_zips(client: paramiko.SSHClient, keep: int = RETENTION_RELEASES) -> None:
    list_cmd = (
        "docker exec lianyu-minio sh -lc '"
        "mc alias set local http://127.0.0.1:9000 \"$MINIO_ROOT_USER\" \"$MINIO_ROOT_PASSWORD\" >/dev/null && "
        f"mc find local/{BUCKET}/updates --name \"AgentEngine-hosted-win-x64-*.zip\""
        "'"
    )
    _, stdout, stderr = client.exec_command(list_cmd, timeout=120, get_pty=True)
    out = stdout.read().decode("utf-8", errors="replace")
    err = stderr.read().decode("utf-8", errors="replace")
    code = stdout.channel.recv_exit_status()
    if code != 0:
        if err.strip():
            print(err[-3000:])
        raise SystemExit(code)

    prefix = f"local/{BUCKET}/"
    names: list[str] = []
    versions: list[tuple[tuple[int, int, int], str]] = []
    for line in out.splitlines():
        line = line.strip()
        if not line.startswith(prefix):
            continue
        object_name = line.removeprefix(prefix)
        filename = object_name.rsplit("/", 1)[-1]
        match = ENGINE_ZIP_RE.fullmatch(filename)
        if not match:
            continue
        names.append(object_name)
        ver = tuple(int(part) for part in match.group(1).split("."))
        versions.append((ver, object_name))

    keep_set = {name for _, name in sorted(versions, reverse=True)[:keep]}
    for object_name in names:
        if object_name in keep_set:
            continue
        target = shlex.quote(f"local/{BUCKET}/{object_name}")
        run(
            client,
            "docker exec lianyu-minio sh -lc '"
            "mc alias set local http://127.0.0.1:9000 \"$MINIO_ROOT_USER\" \"$MINIO_ROOT_PASSWORD\" >/dev/null && "
            f"mc rm {target}"
            "'",
            timeout=120,
            label=f"mc rm {object_name}",
        )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--zip", required=True, help="Local AgentEngine onedir zip")
    parser.add_argument("--version", required=True, help="Engine semver x.y.z (independent of Electron)")
    args = parser.parse_args()
    if not VERSION_RE.fullmatch(args.version):
        raise SystemExit("--version must use x.y.z numeric semver format")

    zip_path = Path(args.zip).expanduser().resolve()
    if not zip_path.is_file():
        raise SystemExit(f"missing engine zip: {zip_path}")

    load_dotenv(ROOT / ".env")
    password = os.environ.get("DEPLOY_SSH_PASSWORD")
    if not password:
        raise SystemExit("Set DEPLOY_SSH_PASSWORD in .env or environment")

    filename = f"AgentEngine-hosted-win-x64-{args.version}.zip"
    size = zip_path.stat().st_size
    digest = sha256_file(zip_path)
    print(f"engine zip sha256={digest} size={size} version={args.version}", flush=True)

    with tempfile.TemporaryDirectory(prefix="lianyu-agent-engine-") as tmp:
        manifest_path = Path(tmp) / "agent-latest.yml"
        write_manifest(manifest_path, version=args.version, filename=filename, sha256=digest, size=size)

        client = connect(password)
        remote_tmp = f"/tmp/lianyu-agent-engine-{args.version}"
        run(client, f"rm -rf {remote_tmp} && mkdir -p {remote_tmp}")
        sftp_put(client, zip_path, f"{remote_tmp}/{filename}")
        sftp_put(client, manifest_path, f"{remote_tmp}/agent-latest.yml")

        run(client, "docker exec lianyu-minio sh -lc 'rm -rf /tmp/lianyu-agent-engine && mkdir -p /tmp/lianyu-agent-engine'")
        run(client, f"docker cp {remote_tmp}/{filename} lianyu-minio:/tmp/lianyu-agent-engine/{filename}", timeout=600)
        mc_cp(client, f"/tmp/lianyu-agent-engine/{filename}", filename)
        # Manifest last: clients polling agent-latest.yml only see a complete pair.
        run(client, f"docker cp {remote_tmp}/agent-latest.yml lianyu-minio:/tmp/lianyu-agent-engine/agent-latest.yml", timeout=120)
        mc_cp(client, "/tmp/lianyu-agent-engine/agent-latest.yml", "agent-latest.yml", timeout=120)

        run(client, "docker exec lianyu-minio sh -lc 'rm -rf /tmp/lianyu-agent-engine'")
        run(client, f"rm -rf {remote_tmp}")
        configure_update_assets_public_read(client)
        cleanup_old_engine_zips(client)
        run(
            client,
            "curl -k -fsS -o /dev/null -w 'agent-latest=%{http_code}' "
            "https://156.233.228.18/api/public/files/updates/agent-latest.yml",
        )
        client.close()

    print("AGENT_ENGINE_UPLOADED")


if __name__ == "__main__":
    main()
