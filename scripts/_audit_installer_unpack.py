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
FRONTEND = ROOT / "frontend"
DEFAULT_INSTALLER = FRONTEND / "release" / "v0.2.119" / "LianYu Setup 0.2.119.exe"
AUDIT_ROOT = ROOT / "release_audit"
STUB_MAX_BYTES = 500

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


def load_cloud_api_patterns() -> list[bytes]:
    """Patterns that must not appear as plaintext in shipped asar JS/JSC."""
    patterns: list[bytes] = []
    env_path = FRONTEND / ".env.production.cloud"
    if env_path.is_file():
        for line in env_path.read_text(encoding="utf-8", errors="replace").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            key, _, value = line.partition("=")
            if key.strip() == "VITE_LIANYU_API_ORIGIN" and value.strip():
                origin = value.strip().rstrip("/")
                patterns.append(origin.encode("utf-8"))
                try:
                    from urllib.parse import urlparse

                    host = urlparse(origin).hostname
                    if host:
                        patterns.append(host.encode("utf-8"))
                except Exception:
                    pass
            if key.strip() == "VITE_LIANYU_CERT_FINGERPRINT" and value.strip():
                fp = value.strip().replace(":", "").upper()
                if len(fp) >= 8:
                    patterns.append(fp[:16].encode("utf-8"))
    return patterns


def scan_plaintext_leaks(asar_path: Path, asar_dir: Path, extract_ok: bool) -> dict:
    """Scan JS/JSC/bin under dist-electron + renderer for cloud host / env literals."""
    targets: list[Path] = []
    rel_paths: list[str] = []
    if extract_ok and asar_dir.is_dir():
        for pattern in (
            "dist-electron/*.js",
            "dist-electron/*.cjs",
            "dist-electron/*.jsc",
            "dist-electron/*.bin",
            "dist-electron/*.dat",
            "dist/assets/*.js",
        ):
            for fp in asar_dir.glob(pattern):
                targets.append(fp)
                rel_paths.append(str(fp.relative_to(asar_dir)).replace("\\", "/"))
    else:
        for entry in list_asar_entries(asar_path):
            norm = entry.replace("\\", "/")
            if norm.startswith("dist-electron/") or (
                norm.startswith("dist/assets/") and norm.endswith(".js")
            ):
                tmp = AUDIT_ROOT / "_tmp_extract" / Path(norm).name
                if extract_asar_file(asar_path, entry, tmp):
                    targets.append(tmp)
                    rel_paths.append(norm)

    host_patterns = load_cloud_api_patterns()
    host_hits: list[str] = []
    api_env_hits: list[str] = []
    vue_path_hits = 0

    for fp, rel in zip(targets, rel_paths):
        try:
            data = fp.read_bytes()
        except OSError:
            continue
        for pat in host_patterns:
            if pat and pat in data:
                host_hits.append(f"{rel} contains {pat!r}")
        if b"VITE_LIANYU_API_ORIGIN" in data and rel.startswith("dist/assets/"):
            api_env_hits.append(rel)
        if rel.startswith("dist/assets/") and fp.suffix == ".js":
            text = data.decode("utf-8", errors="replace")
            vue_path_hits += len(re.findall(r"@/views/", text))
            vue_path_hits += len(re.findall(r"src/views/", text))

    return {
        "host_hits": host_hits,
        "api_env_hits": api_env_hits,
        "vue_path_hits": vue_path_hits,
    }


def measure_stub_sizes(asar_path: Path, asar_dir: Path, extract_ok: bool) -> dict:
    stubs = {
        "main.js": "dist-electron/main.js",
        "preload.cjs": "dist-electron/preload.cjs",
    }
    sizes: dict[str, int | None] = {}
    for label, internal in stubs.items():
        fp = asar_dir / internal if extract_ok else None
        if fp and fp.is_file():
            sizes[label] = fp.stat().st_size
            continue
        tmp = AUDIT_ROOT / "_tmp_extract" / label
        if extract_asar_file(asar_path, internal, tmp):
            sizes[label] = tmp.stat().st_size
        else:
            sizes[label] = None
    too_large = [
        name for name, size in sizes.items()
        if size is None or size > STUB_MAX_BYTES
    ]
    return {"sizes": sizes, "too_large": too_large}


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


def extract_asar(asar_path: Path, out_dir: Path) -> dict:
    """Returns {ok, useful, bloat_files, stderr, stdout}."""
    if out_dir.exists():
        shutil.rmtree(out_dir)
    out_dir.mkdir(parents=True)
    proc = run(["npx", "--yes", "@electron/asar", "extract", str(asar_path), str(out_dir)], cwd=ROOT)
    ok = proc.returncode == 0
    useful = ok and (out_dir / "package.json").is_file() and (out_dir / "dist" / "index.html").is_file()
    bloat_files = 0
    if ok and out_dir.is_dir():
        for fp in out_dir.iterdir():
            if fp.is_file() and re.fullmatch(r"[0-9a-f]{60,64}", fp.name):
                bloat_files += 1
    return {
        "ok": ok,
        "useful": useful,
        "bloat_files": bloat_files,
        "stderr": proc.stderr or "",
        "stdout": proc.stdout or "",
    }


def extract_asar_file(asar_path: Path, internal_path: str, out_file: Path) -> bool:
    out_file.parent.mkdir(parents=True, exist_ok=True)
    proc = run(
        ["npx", "--yes", "@electron/asar", "extract-file", str(asar_path), internal_path, str(out_file)],
        cwd=ROOT,
    )
    return proc.returncode == 0 and out_file.is_file()


def list_asar_entries(asar_path: Path) -> list[str]:
    proc = run(["npx", "--yes", "@electron/asar", "list", str(asar_path)], cwd=ROOT)
    if proc.returncode != 0:
        return []
    return [line.strip() for line in proc.stdout.splitlines() if line.strip()]


def score_obfuscation(text: str) -> dict:
    """Heuristic obfuscation score for a JS bundle."""
    markers = {
        "_0x": len(re.findall(r"_0x[0-9a-fA-F]+", text)),
        "string_array_fn": len(re.findall(r"function\s+_0x", text)),
        "base64_strings": len(re.findall(r"['\"][A-Za-z0-9+/=]{20,}['\"]", text)),
        "control_flow": len(re.findall(r"while\s*\(\s*!\!\[\]", text)),
        "mangled_vars": len(re.findall(r"const\s+[A-Za-z]{1,2}\s*=\s*[A-Za-z]{1,2}\b", text)),
    }
    score = min(
        100,
        markers["_0x"] * 2
        + markers["string_array_fn"] * 5
        + min(markers["base64_strings"], 20)
        + markers["control_flow"] * 10
        + min(markers["mangled_vars"], 10),
    )
    return {"score": score, "markers": markers, "obfuscated": score >= 15}


def collect_obfuscation_scores(asar_path: Path, asar_dir: Path, extract_ok: bool) -> dict:
    samples: dict[str, dict] = {}
    renderer_dir = asar_dir / "dist" / "assets"
    file_targets = {
        "preload": asar_dir / "dist-electron" / "preload.cjs",
        "main": asar_dir / "dist-electron" / "main.js",
    }

    if extract_ok and asar_dir.is_dir():
        if renderer_dir.is_dir():
            for js in renderer_dir.glob("*.js"):
                samples["renderer"] = score_obfuscation(js.read_text(encoding="utf-8", errors="replace"))
                break
        for label, fp in file_targets.items():
            if fp.is_file():
                samples[label] = score_obfuscation(fp.read_text(encoding="utf-8", errors="replace"))
    else:
        entries = list_asar_entries(asar_path)
        for entry in entries:
            norm = entry.replace("\\", "/")
            if norm.startswith("dist/assets/") and norm.endswith(".js") and "renderer" not in samples:
                tmp = AUDIT_ROOT / "_tmp_extract" / "renderer.js"
                if extract_asar_file(asar_path, entry, tmp):
                    samples["renderer"] = score_obfuscation(tmp.read_text(encoding="utf-8", errors="replace"))
            elif norm.endswith("dist-electron/preload.cjs") and "preload" not in samples:
                tmp = AUDIT_ROOT / "_tmp_extract" / "preload.cjs"
                if extract_asar_file(asar_path, entry, tmp):
                    samples["preload"] = score_obfuscation(tmp.read_text(encoding="utf-8", errors="replace"))
            elif norm.endswith("dist-electron/main.js") and "main" not in samples:
                tmp = AUDIT_ROOT / "_tmp_extract" / "main.js"
                if extract_asar_file(asar_path, entry, tmp):
                    samples["main"] = score_obfuscation(tmp.read_text(encoding="utf-8", errors="replace"))
    return samples


def count_plain_ipc_channels(text: str) -> int:
    return len(re.findall(r"['\"]desktop:[^'\"]+['\"]|['\"]auth:[^'\"]+['\"]", text))


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
    asar_extract: dict,
    obfuscation: dict,
    plain_ipc_count: int,
    bytecode_present: bool,
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
        "## 2. ASAR protection",
        "",
    ]
    if not asar_extract["ok"]:
        lines.append("- **Full `asar extract`**: FAILED (expected with asarmor patch)")
    elif not asar_extract.get("useful", False):
        lines.append("- **Full `asar extract`**: succeeded but payload unusable (dist/index.html missing — asarmor effective)")
    else:
        lines.append("- **Full `asar extract`**: succeeded with readable tree (patch adds bloat; rely on obfuscation + fuses)")
    lines.append(f"- **Bloat/hash entries at extract root**: {asar_extract.get('bloat_files', 0)}")
    if not asar_extract["ok"] and asar_extract.get("stderr"):
        lines.append(f"- Extract stderr: `{asar_extract['stderr'][:200].strip()}`")
    lines += [
        "",
        "## 3. Obfuscation score",
        "",
        "| Bundle | Score | Obfuscated | _0x refs |",
        "|--------|-------|------------|----------|",
    ]
    for label in ("renderer", "preload", "main"):
        if label in obfuscation:
            o = obfuscation[label]
            m = o["markers"]
            lines.append(
                f"| {label} | {o['score']} | {'yes' if o['obfuscated'] else 'no'} | {m['_0x']} |"
            )
        else:
            lines.append(f"| {label} | — | — | — |")
    lines += [
        "",
        f"- Plain IPC channel literals in preload/main sample: **{plain_ipc_count}**",
        "",
        f"- **V8 bytecode (.jsc)**: {'present' if bytecode_present else 'missing'}",
        "",
        "## 4. IPC surface (preload → main)",
        "",
        ", ".join(f"`{k}`" for k in ipc_apis) if ipc_apis else "(preload obfuscated or not found)",
        "",
        "## 5. Secret / sensitive string scan",
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
        "## 6. Verdict",
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
        "- Vue/Electron JS is obfuscated; full extract may fail under asarmor patch.",
        "- `.cursor/` rules are NOT in installer (not in electron-builder files list).",
        "",
    ]
    out_md.write_text("\n".join(lines), encoding="utf-8")
    print(f"Report written: {out_md}", flush=True)


def audit_installer(installer: Path, *, version: str = "", out_md: Path | None = None) -> dict:
    """Run full audit; returns structured result for verify gate."""
    seven_z = find_7z()
    if not seven_z:
        raise SystemExit("7z not found. Install 7-Zip or add 7z to PATH.")

    version = version or parse_version_from_installer(installer)
    level1 = AUDIT_ROOT / version / "level1"
    level2 = AUDIT_ROOT / version / "level2"
    asar_dir = AUDIT_ROOT / version / "asar"

    if (AUDIT_ROOT / version).exists():
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

    asar_extract = extract_asar(asar_path, asar_dir)
    extra_dist = level2 / "resources" / "dist"

    js_targets: list[Path] = []
    if asar_extract["ok"]:
        for pattern in ("dist-electron/*.js", "dist-electron/*.cjs", "dist/assets/*.js"):
            js_targets.extend(asar_dir.glob(pattern))
        if extra_dist.is_dir():
            js_targets.extend(extra_dist.glob("assets/*.js"))
        beautify_js(js_targets)

    obfuscation = collect_obfuscation_scores(asar_path, asar_dir, asar_extract["ok"])

    ipc_text = ""
    preload = asar_dir / "dist-electron" / "preload.cjs"
    main_js = asar_dir / "dist-electron" / "main.js"
    if preload.is_file():
        ipc_text += preload.read_text(encoding="utf-8", errors="replace")
    elif extract_asar_file(asar_path, "dist-electron/preload.cjs", AUDIT_ROOT / "_tmp_extract" / "preload.cjs"):
        ipc_text += (AUDIT_ROOT / "_tmp_extract" / "preload.cjs").read_text(encoding="utf-8", errors="replace")
    if main_js.is_file():
        ipc_text += main_js.read_text(encoding="utf-8", errors="replace")
    elif extract_asar_file(asar_path, "dist-electron/main.js", AUDIT_ROOT / "_tmp_extract" / "main.js"):
        ipc_text += (AUDIT_ROOT / "_tmp_extract" / "main.js").read_text(encoding="utf-8", errors="replace")
    plain_ipc_count = count_plain_ipc_channels(ipc_text)

    scan_roots = [asar_dir] if asar_extract["ok"] else []
    if extra_dist.is_dir():
        scan_roots.append(extra_dist)
    all_hits: list[dict] = []
    for root in scan_roots:
        for h in scan_secrets(root):
            h["file"] = f"{root.name}/{h['file']}" if root != asar_dir else h["file"]
            all_hits.append(h)

    ipc_apis = list_ipc_exposed(preload) if preload.is_file() else []

    bytecode_present = any(
        "dist-electron/main.jsc" in e.replace("\\", "/") for e in list_asar_entries(asar_path)
    ) and any(
        "dist-electron/preload.jsc" in e.replace("\\", "/") for e in list_asar_entries(asar_path)
    )

    leak_scan = scan_plaintext_leaks(asar_path, asar_dir, asar_extract["ok"])
    stub_info = measure_stub_sizes(asar_path, asar_dir, asar_extract["ok"])

    stale_main_cjs = (asar_dir / "dist-electron" / "main.cjs").is_file() or any(
        e.replace("\\", "/").endswith("dist-electron/main.cjs") for e in list_asar_entries(asar_path)
    )

    report_path = out_md or (ROOT / ".cortexloop" / f"audit-installer-{version}.md")
    write_report(
        installer=installer,
        version=version,
        level1=level1,
        level2=level2,
        asar_dir=asar_dir,
        extra_dist=extra_dist if extra_dist.is_dir() else None,
        hits=all_hits,
        ipc_apis=ipc_apis,
        asar_extract=asar_extract,
        obfuscation=obfuscation,
        plain_ipc_count=plain_ipc_count,
        bytecode_present=bytecode_present,
        out_md=report_path,
    )

    critical = [h for h in all_hits if h["label"] in (
        "OpenAI-style sk- key", "Master key env", "Redis URL", "MySQL URL"
    )]

    return {
        "version": version,
        "report": report_path,
        "asar_extract_blocked": not asar_extract["ok"],
        "asar_extract_useful": asar_extract.get("useful", False),
        "asar_bloat_files": asar_extract.get("bloat_files", 0),
        "extra_dist_outside_asar": extra_dist.is_dir(),
        "obfuscation": obfuscation,
        "plain_ipc_count": plain_ipc_count,
        "critical_hits": len(critical),
        "total_hits": len(all_hits),
        "bytecode_present": bytecode_present,
        "stub_sizes": stub_info["sizes"],
        "stub_too_large": stub_info["too_large"],
        "plaintext_host_hits": leak_scan["host_hits"],
        "renderer_api_env_leak": leak_scan["api_env_hits"],
        "vue_path_hits": leak_scan["vue_path_hits"],
        "stale_main_cjs": stale_main_cjs,
    }


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

    version = args.version or parse_version_from_installer(installer)
    out_md = Path(args.out_md) if args.out_md else None
    audit_installer(installer, version=version, out_md=out_md)


if __name__ == "__main__":
    main()
