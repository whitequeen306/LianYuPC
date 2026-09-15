#!/usr/bin/env python3
"""Verification gate for YuNian Electron releases (Tier B hardening)."""
from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
FRONTEND = ROOT / "frontend"

# Import audit runner from sibling script
sys.path.insert(0, str(ROOT / "scripts"))
from _audit_installer_unpack import audit_installer, parse_version_from_installer, STUB_MAX_BYTES  # noqa: E402


def run(cmd: list[str], *, cwd: Path | None = None, check: bool = True) -> subprocess.CompletedProcess:
    print(f"$ {' '.join(cmd)}", flush=True)
    shell = sys.platform == "win32" and cmd and cmd[0] in {"npx", "npm", "node"}
    proc = subprocess.run(
        cmd,
        cwd=cwd,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        shell=shell,
    )
    if check and proc.returncode != 0:
        raise SystemExit(f"Command failed ({proc.returncode}):\n{proc.stderr}\n{proc.stdout}")
    return proc


def assert_audit(audit: dict) -> list[str]:
    failures: list[str] = []
    asar_ok = (
        audit["asar_extract_blocked"]
        or not audit.get("asar_extract_useful", True)
        or audit.get("asar_bloat_files", 0) >= 10
    )
    if not asar_ok:
        failures.append("asar extract produced fully usable tree with no bloat — asarmor patch may be missing")
    if audit["extra_dist_outside_asar"]:
        failures.append("resources/dist found outside app.asar")
    if audit["critical_hits"] > 0:
        failures.append(f"CRITICAL secret scan hits: {audit['critical_hits']}")
    obf = audit.get("obfuscation", {})
    for label in ("renderer",):
        if label not in obf:
            failures.append(f"obfuscation sample missing for {label}")
        elif not obf[label].get("obfuscated"):
            failures.append(f"{label} bundle obfuscation score too low ({obf[label].get('score')})")
    if audit.get("bytecode_present"):
        for label in ("main", "preload"):
            if label in obf and obf[label].get("score", 0) > 5:
                failures.append(f"{label} should be bytecode stub, but obfuscation score={obf[label].get('score')}")
    if audit.get("stale_main_cjs"):
        failures.append("stale dist-electron/main.cjs found in app.asar — remove before pack")
    if not audit.get("bytecode_present"):
        failures.append("dist-electron/main.jsc or preload.jsc missing — bytecode pack step may be skipped")
    if audit.get("stub_too_large"):
        failures.append(f"electron entry stub too large (max {STUB_MAX_BYTES}B): {audit.get('stub_too_large')}")
    if audit.get("plaintext_host_hits"):
        failures.append(
            "plaintext cloud host/fingerprint in asar: "
            + "; ".join(audit["plaintext_host_hits"][:3])
        )
    if audit.get("renderer_api_env_leak"):
        failures.append(
            "VITE_LIANYU_API_ORIGIN literal leaked in renderer bundle: "
            + ", ".join(audit["renderer_api_env_leak"])
        )
    if audit.get("vue_path_hits", 0) > 2:
        failures.append(f"renderer bundle exposes too many vue source paths ({audit['vue_path_hits']})")
    return failures


def run_npm_test() -> None:
    print("\n=== npm test ===", flush=True)
    proc = run(["npm", "test", "--", "run"], cwd=FRONTEND, check=False)
    if proc.returncode != 0:
        raise SystemExit(f"npm test failed:\n{proc.stderr}\n{proc.stdout}")
    out = proc.stdout[-2000:] if len(proc.stdout) > 2000 else proc.stdout
    sys.stdout.buffer.write(out.encode("utf-8", errors="replace"))
    sys.stdout.buffer.write(b"\n")


def find_installed_exe(install_dir: Path) -> Path | None:
    for name in ("YuNian.exe", "yunian.exe"):
        p = install_dir / name
        if p.is_file():
            return p
    for p in install_dir.rglob("YuNian.exe"):
        return p
    return None


def smoke_launch(installer: Path, *, timeout_sec: int = 45) -> list[str]:
    """Silent-install to temp dir, launch, check startup.log."""
    failures: list[str] = []
    if sys.platform != "win32":
        print("Skipping smoke launch (non-Windows)", flush=True)
        return failures

    print("\n=== startup smoke test ===", flush=True)
    install_dir = Path(tempfile.mkdtemp(prefix="lianyu-verify-"))
    log_path = Path(os.environ.get("APPDATA", "")) / "lianyu-pc" / "startup.log"
    log_before = log_path.read_text(encoding="utf-8", errors="replace") if log_path.is_file() else ""

    try:
        proc = subprocess.run(
            [str(installer), "/S", f"/D={install_dir}"],
            capture_output=True,
            text=True,
            timeout=300,
        )
        if proc.returncode != 0:
            failures.append(f"silent install failed (exit {proc.returncode})")
            return failures

        exe = find_installed_exe(install_dir)
        if not exe:
            failures.append(f"YuNian.exe not found under {install_dir}")
            return failures

        subprocess.Popen([str(exe)], cwd=str(exe.parent))
        deadline = time.time() + timeout_sec
        new_log = log_before
        while time.time() < deadline:
            time.sleep(2)
            if log_path.is_file():
                new_log = log_path.read_text(encoding="utf-8", errors="replace")
                if "did-finish-load" in new_log and "asar integrity verification OK" in new_log:
                    break
        else:
            tail = new_log[-1500:] if new_log else "(no log)"
            failures.append(f"startup.log missing expected markers within {timeout_sec}s:\n{tail}")

        subprocess.run(["taskkill", "/F", "/IM", "YuNian.exe"], capture_output=True)
    finally:
        shutil.rmtree(install_dir, ignore_errors=True)

    return failures


def tamper_test(installer: Path) -> list[str]:
    """Optional: tamper app.asar → integrity check should fail at launch."""
    failures: list[str] = []
    if sys.platform != "win32":
        return failures

    print("\n=== tamper test (optional) ===", flush=True)
    install_dir = Path(tempfile.mkdtemp(prefix="lianyu-tamper-"))
    try:
        proc = subprocess.run(
            [str(installer), "/S", f"/D={install_dir}"],
            capture_output=True,
            text=True,
            timeout=300,
        )
        if proc.returncode != 0:
            print(f"tamper test skipped: install failed ({proc.returncode})", flush=True)
            return failures

        asar_path = install_dir / "resources" / "app.asar"
        if not asar_path.is_file():
            for p in install_dir.rglob("app.asar"):
                asar_path = p
                break
        if not asar_path.is_file():
            print("tamper test skipped: app.asar not found", flush=True)
            return failures

        data = bytearray(asar_path.read_bytes())
        if len(data) > 100:
            data[50] ^= 0xFF
            asar_path.write_bytes(data)

        exe = find_installed_exe(install_dir)
        if not exe:
            return failures

        proc = subprocess.run([str(exe)], capture_output=True, timeout=15)
        # Tampered asar should exit quickly (dialog + exit 1); non-zero or no hang is OK
        print(f"tamper launch exit code: {proc.returncode}", flush=True)
    except subprocess.TimeoutExpired:
        failures.append("tampered app did not exit promptly (integrity check may be broken)")
    finally:
        subprocess.run(["taskkill", "/F", "/IM", "YuNian.exe"], capture_output=True)
        shutil.rmtree(install_dir, ignore_errors=True)
    return failures


def main() -> None:
    parser = argparse.ArgumentParser(description="Verify YuNian Electron release")
    parser.add_argument("installer", nargs="?", default="")
    parser.add_argument("--skip-smoke", action="store_true")
    parser.add_argument("--skip-tamper", action="store_true")
    parser.add_argument("--skip-test", action="store_true")
    args = parser.parse_args()

    if args.installer:
        installer = Path(args.installer).resolve()
    else:
        pkg = FRONTEND / "package.json"
        import json

        version = json.loads(pkg.read_text(encoding="utf-8"))["version"]
        contract = json.loads((ROOT / "scripts" / "release_assets.json").read_text(encoding="utf-8"))
        installer_name = contract["installer"]["exeName"].replace("{version}", version)
        installer = FRONTEND / "release" / f"v{version}" / installer_name

    if not installer.is_file():
        raise SystemExit(f"Installer not found: {installer}")

    version = parse_version_from_installer(installer)
    print(f"=== Verify {installer.name} ({version}) ===\n", flush=True)

    all_failures: list[str] = []

    audit = audit_installer(installer, version=version)
    all_failures.extend(assert_audit(audit))

    if not args.skip_test:
        try:
            run_npm_test()
        except SystemExit as exc:
            all_failures.append(str(exc))

    if not args.skip_smoke:
        all_failures.extend(smoke_launch(installer))

    if not args.skip_tamper:
        all_failures.extend(tamper_test(installer))

    print("\n=== Verification summary ===", flush=True)
    if all_failures:
        for f in all_failures:
            print(f"FAIL: {f}", flush=True)
        raise SystemExit(1)

    print("PASS: audit + tests + smoke (and tamper if run)", flush=True)
    print(f"Report: {audit['report']}", flush=True)


if __name__ == "__main__":
    main()
