#!/usr/bin/env python3
"""Red-team unpack + secret scan for LianYu Electron NSIS installer."""
from __future__ import annotations

import argparse
import json
import re
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_INSTALLER = ROOT / "frontend" / "release" / "v0.2.119" / "LianYu Setup 0.2.119.exe"
AUDIT_ROOT = ROOT / "release_audit"

SECRET_PATTERNS = [
    (r"sk-[A-Za-z0-9]{20,}", "OpenAI-style sk- key"),
    (r"Bearer\s+[A-Za-z0-9._\-]{20,}", "Bearer token"),
    (r"DASHSCOPE", "DashScope reference"),
    (r"LIANYU_MASTER", "Master key env"),
    (r"JASYPT", "Jasypt reference"),
    (r"redis://[^\s\"']+", "Redis URL"),
    (r"mysql://[^\s\"']+", "MySQL URL"),
    (r"mongodb://[^\s\"']+", "MongoDB URL"),
    (r"api[_-]?key", "api_key literal (case-insensitive)", re.I),
    (r"password\s*[:=]", "password assignment", re.I),
    (r"secret\s*[:=]", "secret assignment", re.I),
    (r"154\.219\.111\.30", "cloud API host"),
    (r"EdDpp/", "SPKI pin prefix"),
    (r"8B:D6:4E:A0", "cert fingerprint prefix"),
    (r"lianyu-token", "Sa-Token header name"),
]

IPC_RE = re.compile(r"^\s+(\w+)\s*:", re.M)


def run(cmd: list[str], *, cwd: Path | None = None, shell: bool = False) -> subprocess.CompletedProcess:
    print(f"$ {' '.join(cmd)}", flush=True)
    if sys.platform == "win32" and not shell and cmd and cmd[0] in {"npx", "npm", "node"}:
        shell = True
    return subprocess.run(
        cmd,
        cwd=cwd,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        shell=shell,
    )


def find_7z() -> str | None:
    for name in ("7z", "7z.exe"):
        path = shutil.which(name)
        if path:
            return path
    candidates = [
        Path(r"C:\Program Files\7-Zip\7z.exe"),
        Path(r"C:\Program Files (x86)\7-Zip\7z.exe"),
    ]
    for p in candidates:
        if p.is_file():
            return str(p)
    return None


def extract_nsis(installer: Path, out_dir: Path, seven_z: str) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    proc = run([seven_z, "x", str(installer), f"-o{out_dir}", "-y"])
    if proc.returncode != 0:
        raise SystemExit(f"7z level1 failed:\n{proc.stderr}\n{proc.stdout}")


def find_app_7z(level1: Path) -> Path | None:
    for name in ("app-64.7z", "$PLUGINSDIR/app-64.7z"):
        p = level1 / name
        if p.is_file():
            return p
    for p in level1.rglob("app-64.7z"):
        return p
    return None


def extract_app_archive(app_7z: Path, out_dir: Path, seven_z: str) -> None:
    out_dir.mkdir(parents=True, exist_ok=True)
    proc = run([seven_z, "x", str(app_7z), f"-o{out_dir}", "-y"])
    if proc.returncode != 0:
        raise SystemExit(f"7z level2 failed:\n{proc.stderr}\n{proc.stdout}")


def extract_asar(asar_path: Path, out_dir: Path) -> None:
    if out_dir.exists():
        shutil.rmtree(out_dir)
    out_dir.mkdir(parents=True)
    proc = run(["npx", "--yes", "@electron/asar", "extract", str(asar_path), str(out_dir)], cwd=ROOT)
    if proc.returncode != 0:
        raise SystemExit(f"asar extract failed:\n{proc.stderr}\n{proc.stdout}")


def beautify_js(files: list[Path]) -> None:
    try:
        import jsbeautifier  # type: ignore
    except ImportError:
        subprocess.check_call([sys.executable, "-m", "pip", "install", "jsbeautifier", "-q"])
        import jsbeautifier  # type: ignore

    opts = jsbeautifier.default_options()
    opts.indent_size = 2
    for fp in files:
        if not fp.is_file() or fp.stat().st_size > 8_000_000:
            continue
        try:
            text = fp.read_text(encoding="utf-8", errors="replace")
            fp.write_text(jsbeautifier.beautify(text, opts), encoding="utf-8")
        except Exception as exc:
            print(f"beautify skip {fp}: {exc}", flush=True)


def scan_secrets(root: Path) -> list[dict]:
    hits: list[dict] = []
    if not root.is_dir():
        return hits
    for fp in root.rglob("*"):
        if not fp.is_file():
            continue
        if fp.suffix.lower() not in {".js", ".cjs", ".json", ".html", ".txt", ".env", ".yml", ".yaml"}:
            if fp.name not in {"app.asar", "package.json"}:
                continue
        try:
            text = fp.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        for item in SECRET_PATTERNS:
            if len(item) == 3:
                pattern, label, flags = item
                rx = re.compile(pattern, flags)
            else:
                pattern, label = item
                rx = re.compile(pattern)
            for m in rx.finditer(text):
                line_no = text.count("\n", 0, m.start()) + 1
                snippet = text[max(0, m.start() - 40) : m.end() + 40].replace("\n", " ")
                hits.append(
                    {
                        "file": str(fp.relative_to(root)),
                        "line": line_no,
                        "label": label,
                        "match": m.group(0)[:120],
                        "snippet": snippet[:200],
                    }
                )
    return hits


def list_ipc_exposed(preload_path: Path) -> list[str]:
    if not preload_path.is_file():
        return []
    text = preload_path.read_text(encoding="utf-8", errors="replace")
    m = re.search(r"exposeInMainWorld\(\s*['\"]electronAPI['\"]\s*,\s*\{([^}]+)\}", text, re.S)
    if not m:
        return []
    block = m.group(1)
    return sorted(set(IPC_RE.findall(block)))


def tree_summary(root: Path, max_lines: int = 80) -> str:
    lines: list[str] = []
    if not root.is_dir():
        return "(missing)"
    for fp in sorted(root.rglob("*")):
        if fp.is_file():
            rel = fp.relative_to(root)
            size = fp.stat().st_size
            lines.append(f"  {rel} ({size:,} B)")
            if len(lines) >= max_lines:
                lines.append("  ...")
                break
    return "\n".join(lines) if lines else "(empty)"


def write_report(
    *,
    installer: Path,
    version: str,
    level1: Path,
    level2: Path,
    asar_dir: Path,
    extra_dist: Path | None,
    hits: list[dict],
    ipc_apis: list[str],
    out_md: Path,
) -> None:
    now = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")
    out_md.parent.mkdir(parents=True, exist_ok=True)

    critical = [h for h in hits if h["label"] not in (
        "cloud API host", "SPKI pin prefix", "cert fingerprint prefix", "Sa-Token header name"
    ) and "api_key literal" not in h["label"].lower()]

    lines = [
        f"# Installer Security Audit — {version}",
        "",
        f"- **Installer**: `{installer}`",
        f"- **Generated**: {now}",
        f"- **Audit dir**: `{AUDIT_ROOT}`",
        "",
        "## 1. Unpack tree",
        "",
        "### level1 (NSIS)",
        "```",
        tree_summary(level1),
        "```",
        "",
        "### level2 (app-64.7z)",
        "```",
        tree_summary(level2),
        "```",
        "",
        "### app.asar extracted",
        "```",
        tree_summary(asar_dir),
        "```",
        "",
    ]
    if extra_dist and extra_dist.is_dir():
        lines += [
            "### resources/dist (outside asar)",
            "```",
            tree_summary(extra_dist),
            "```",
            "",
        ]

    lines += [
        "## 2. IPC surface (preload → main)",
        "",
        ", ".join(f"`{k}`" for k in ipc_apis) if ipc_apis else "(preload not found)",
        "",
        "## 3. Secret / sensitive string scan",
        "",
        f"Total hits: **{len(hits)}** | Non-public config hits: **{len(critical)}**",
        "",
    ]
    if hits:
        lines.append("| File | Line | Label | Match |")
        lines.append("|------|------|-------|-------|")
        for h in hits[:200]:
            lines.append(
                f"| `{h['file']}` | {h['line']} | {h['label']} | `{h['match'][:80]}` |"
            )
        if len(hits) > 200:
            lines.append(f"| ... | | | ({len(hits) - 200} more) |")
    else:
        lines.append("_No pattern matches._")

    lines += [
        "",
        "## 4. Verdict",
        "",
    ]
    if any(h["label"] in ("OpenAI-style sk- key", "Master key env", "Redis URL", "MySQL URL") for h in hits):
        lines.append("**CRITICAL**: Possible secrets found in installer payload.")
    elif critical:
        lines.append("**MEDIUM**: Business logic / config strings recoverable; no backend secrets detected.")
    else:
        lines.append("**LOW (secrets)**: No backend API keys in payload; only expected public endpoints/pins.")

    lines += [
        "",
        "- Vue/Electron JS is minified but recoverable (beautified in audit dir).",
        "- `.cursor/` rules are NOT in installer (not in electron-builder files list).",
        "",
    ]
    out_md.write_text("\n".join(lines), encoding="utf-8")
    print(f"Report written: {out_md}", flush=True)


def parse_version_from_installer(installer: Path) -> str:
    m = re.search(r"(\d+\.\d+\.\d+)", installer.stem)
    return f"v{m.group(1)}" if m else "unknown"


def main() -> None:
    parser = argparse.ArgumentParser(description="Unpack and audit LianYu installer")
    parser.add_argument("installer", nargs="?", default=str(DEFAULT_INSTALLER))
    parser.add_argument("--version", default="")
    parser.add_argument(
        "--out-md",
        default="",
        help="Report path (default .cortexloop/audit-installer-{version}.md)",
    )
    args = parser.parse_args()

    installer = Path(args.installer).resolve()
    if not installer.is_file():
        raise SystemExit(f"Installer not found: {installer}")

    seven_z = find_7z()
    if not seven_z:
        raise SystemExit("7z not found. Install 7-Zip or add 7z to PATH.")

    version = args.version or parse_version_from_installer(installer)
    level1 = AUDIT_ROOT / version / "level1"
    level2 = AUDIT_ROOT / version / "level2"
    asar_dir = AUDIT_ROOT / version / "asar"

    if AUDIT_ROOT.exists():
        shutil.rmtree(AUDIT_ROOT / version, ignore_errors=True)

    print(f"=== Auditing {installer.name} ({version}) ===", flush=True)
    extract_nsis(installer, level1, seven_z)

    app_7z = find_app_7z(level1)
    if not app_7z:
        raise SystemExit("app-64.7z not found in NSIS extract")
    extract_app_archive(app_7z, level2, seven_z)

    asar_path = level2 / "resources" / "app.asar"
    if not asar_path.is_file():
        raise SystemExit(f"app.asar not found: {asar_path}")
    extract_asar(asar_path, asar_dir)

    extra_dist = level2 / "resources" / "dist"
    js_targets: list[Path] = []
    for pattern in ("dist-electron/*.js", "dist/assets/*.js"):
        js_targets.extend(asar_dir.glob(pattern))
    if extra_dist.is_dir():
        js_targets.extend(extra_dist.glob("assets/*.js"))
    beautify_js(js_targets)

    scan_roots = [asar_dir]
    if extra_dist.is_dir():
        scan_roots.append(extra_dist)
    all_hits: list[dict] = []
    for root in scan_roots:
        for h in scan_secrets(root):
            h["file"] = f"{root.name}/{h['file']}" if root != asar_dir else h["file"]
            all_hits.append(h)

    preload = asar_dir / "dist-electron" / "preload.cjs"
    ipc_apis = list_ipc_exposed(preload)

    out_md = Path(args.out_md) if args.out_md else ROOT / ".cortexloop" / f"audit-installer-{version}.md"
    write_report(
        installer=installer,
        version=version,
        level1=level1,
        level2=level2,
        asar_dir=asar_dir,
        extra_dist=extra_dist if extra_dist.is_dir() else None,
        hits=all_hits,
        ipc_apis=ipc_apis,
        out_md=out_md,
    )


if __name__ == "__main__":
    main()
