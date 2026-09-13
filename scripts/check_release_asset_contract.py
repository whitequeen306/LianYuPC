#!/usr/bin/env python3
"""Guard the update-asset naming contract (docs/refactor-plan-coupling.md item 1).

Verifies that:
1. scripts/release_assets.json patterns compile.
2. deploy/api-gateway/nginx.conf has a location regex for every asset family
   in both server blocks (/api/public/files/updates/... and the bare /... block).
3. Client runtime regex (wechatChannelRelease.js) is derived from the JSON,
   not a stale literal.

Run: python scripts/check_release_asset_contract.py
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = json.loads((ROOT / "scripts" / "release_assets.json").read_text(encoding="utf-8"))
NGINX = (ROOT / "deploy" / "api-gateway" / "nginx.conf").read_text(encoding="utf-8")


def main() -> int:
    failures: list[str] = []

    # 1. JSON patterns compile
    for name, family in ASSETS.items():
        if name.startswith("$"):
            continue
        try:
            re.compile(family["versionPattern"])
        except re.error as exc:
            failures.append(f"{name}: versionPattern does not compile: {exc}")

    # 2. nginx has one location regex per family in both server blocks
    for fam_key in ("installer", "agentEngine", "wechatChannel"):
        stem = ASSETS[fam_key].get("zipName", ASSETS[fam_key].get("exeName", "")).split("{version}")[0].rstrip("-.")
        for prefix in (r"/api/public/files/updates/", r"/"):
            expected = rf"location ~ \^{re.escape(prefix)}{re.escape(stem)}-"
            if not re.search(expected, NGINX):
                failures.append(f"nginx: missing location regex for {stem} under {prefix}")

    # 3. client runtime derives from JSON (no stale literal regex)
    client = (
        ROOT / "frontend" / "electron" / "wechatBridge" / "wechatChannelRelease.js"
    ).read_text(encoding="utf-8")
    if "/^WechatChannel-win-x64-" in client:
        failures.append(
            "client: wechatChannelRelease.js hardcodes the zip regex instead of deriving it from release_assets.json"
        )
    if "release_assets.json" not in client:
        failures.append("client: wechatChannelRelease.js no longer references release_assets.json")

    if failures:
        print("release-asset contract check FAILED:")
        for f in failures:
            print(f"  - {f}")
        return 1
    print("release-asset contract check OK: json/nginx/client in sync")
    return 0


if __name__ == "__main__":
    sys.exit(main())
