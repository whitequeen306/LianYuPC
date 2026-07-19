#!/usr/bin/env python3
"""Upload Electron update artifacts to the cloud MinIO bucket via SSH.

The runtime updater reads assets from /api/public/files/updates/*. This script keeps
latest.yml as the final write so clients never see a manifest before its files exist.
"""
import argparse
import json
import os
import re
import shlex
import subprocess
import time
from pathlib import Path

import paramiko

HOST = "154.219.111.30"
USER = "root"
ROOT = Path(__file__).resolve().parents[1]
BUCKET = "lianyu"
REPO = "whitequeen306/LianYuPC"
RETENTION_RELEASES = 3
VERSION_RE = re.compile(r"^\d+\.\d+\.\d+$")
UPDATE_ASSET_RE = re.compile(r"^updates/LianYu-Setup-(\d+)\.(\d+)\.(\d+)\.exe(?:\.blockmap)?$")


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


def github_token() -> str:
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if token:
        return token
    gh_proc = subprocess.run(["gh", "auth", "token"], text=True, capture_output=True, check=False)
    if gh_proc.returncode == 0 and gh_proc.stdout.strip():
        return gh_proc.stdout.strip()
    proc = subprocess.run(
        ["git", "credential", "fill"],
        input="protocol=https\nhost=github.com\n\n",
        text=True,
        capture_output=True,
        check=False,
    )
    for line in proc.stdout.splitlines():
        if line.startswith("password="):
            return line.removeprefix("password=").strip()
    raise SystemExit("Set GH_TOKEN, or configure git credential manager for github.com")


def release_assets(version: str, token: str) -> dict[str, dict[str, int | str]]:
    # Prefer the canonical published-release-by-tag endpoint. It returns the single
    # release owning tag v{version} (drafts have no git tag, so a still-draft release
    # 404s here and falls through to the listing fallback below). Assets are inlined.
    proc = subprocess.run(
        ["gh", "api", f"repos/{REPO}/releases/tags/v{version}"],
        capture_output=True,
        check=False,
    )
    if proc.returncode == 0:
        rel = json.loads(proc.stdout.decode("utf-8"))
        assets = rel.get("assets", []) or []
    else:
        # Fallback: list releases (per_page=100 covers this repo in one page) and merge
        # assets across ALL releases matching tag v{version}. Handles the rare case where
        # electron-builder left assets split across a draft + a published release.
        # Asset IDs are repo-global, so downloads work regardless of owning release.
        list_proc = subprocess.run(
            ["gh", "api", f"repos/{REPO}/releases?per_page=100"],
            capture_output=True,
            check=False,
        )
        if list_proc.returncode != 0:
            err = (list_proc.stderr or list_proc.stdout or b"gh api releases failed").decode(
                "utf-8", errors="replace"
            )
            raise SystemExit(err.strip())
        releases = json.loads(list_proc.stdout.decode("utf-8"))
        matches = [r for r in releases if r.get("tag_name") == f"v{version}"]
        if not matches:
            raise SystemExit(f"missing GitHub release: v{version}")
        # Prefer non-draft, then most assets — but still merge across all matches.
        matches.sort(key=lambda r: (r.get("draft", True), -len(r.get("assets", []))))
        assets = []
        seen: set[str] = set()
        for r in matches:
            for a in r.get("assets", []) or []:
                if a.get("name") and a["name"] not in seen:
                    seen.add(a["name"])
                    assets.append(a)
    return {asset["name"]: {"id": asset["id"], "size": asset["size"]} for asset in assets}


def download_release_asset(client: paramiko.SSHClient, token: str, asset_id: int | str, size: int | str, remote_path: str) -> None:
    quoted_token = shlex.quote(token)
    quoted_path = shlex.quote(remote_path)
    cmd = (
        "curl -fL --retry 5 --retry-delay 3 "
        f"-H 'Authorization: Bearer {quoted_token}' "
        "-H 'Accept: application/octet-stream' "
        f"https://api.github.com/repos/{REPO}/releases/assets/{asset_id} "
        f"-o {quoted_path} && test $(stat -c %s {quoted_path}) -eq {int(size)}"
    )
    run(client, cmd, timeout=900, label=f"download GitHub asset {asset_id} -> {remote_path}")


def update_asset_version(object_name: str) -> tuple[int, int, int] | None:
    match = UPDATE_ASSET_RE.match(object_name)
    if not match:
        return None
    return tuple(int(part) for part in match.groups())


def stale_update_objects(object_names: list[str], keep: int = RETENTION_RELEASES) -> list[str]:
    versions = {version for name in object_names if (version := update_asset_version(name)) is not None}
    stale_versions = set(sorted(versions, reverse=True)[keep:])
    return [name for name in object_names if update_asset_version(name) in stale_versions]


def cleanup_old_update_assets(client: paramiko.SSHClient, keep: int = RETENTION_RELEASES) -> None:
    list_cmd = (
        "docker exec lianyu-minio sh -lc '"
        "mc alias set local http://127.0.0.1:9000 \"$MINIO_ROOT_USER\" \"$MINIO_ROOT_PASSWORD\" >/dev/null && "
        f"mc find local/{BUCKET}/updates --name \"LianYu-Setup-*\""
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
    object_names = [line.strip().removeprefix(prefix) for line in out.splitlines() if line.strip().startswith(prefix)]
    for object_name in stale_update_objects(object_names, keep=keep):
        target = shlex.quote(f"local/{BUCKET}/{object_name}")
        run(
            client,
            "docker exec lianyu-minio sh -lc '"
            "mc alias set local http://127.0.0.1:9000 \"$MINIO_ROOT_USER\" \"$MINIO_ROOT_PASSWORD\" >/dev/null && "
            f"mc rm {target}"
            "'",
            timeout=120,
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
    if not VERSION_RE.fullmatch(args.version):
        raise SystemExit("--version must use x.y.z numeric semver format")

    load_dotenv(ROOT / ".env")
    password = os.environ.get("DEPLOY_SSH_PASSWORD")
    if not password:
        raise SystemExit("Set DEPLOY_SSH_PASSWORD in .env or environment")
    token = github_token()
    expected_targets = {target for _src, target in artifact_paths(args.version)}
    # GitHub asset listing is eventually consistent right after upload/publish;
    # retry a few times so a momentarily-unlisted asset doesn't fail the sync.
    assets = {}
    for attempt in range(4):
        assets = release_assets(args.version, token)
        if expected_targets <= set(assets):
            break
        missing = expected_targets - set(assets)
        print(f"waiting for GitHub assets to propagate (attempt {attempt + 1}/4, missing: {', '.join(sorted(missing))})...", flush=True)
        time.sleep(10)
    missing = expected_targets - set(assets)
    if missing:
        raise SystemExit(f"missing GitHub release asset(s): {', '.join(sorted(missing))}")

    client = connect(password)

    remote_tmp = f"/tmp/lianyu-update-assets-{args.version}"
    run(client, f"rm -rf {remote_tmp} && mkdir -p {remote_tmp}")
    for _src, target in artifact_paths(args.version):
        asset = assets[target]
        download_release_asset(client, token, asset["id"], asset["size"], f"{remote_tmp}/{target}")

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
    configure_update_assets_public_read(client)
    cleanup_old_update_assets(client)
    run(client, "curl -k -fsS -o /dev/null -w 'latest=%{http_code}' https://154.219.111.30/api/public/files/updates/latest.yml")
    client.close()
    print("UPDATE_ASSETS_UPLOADED")


if __name__ == "__main__":
    main()
