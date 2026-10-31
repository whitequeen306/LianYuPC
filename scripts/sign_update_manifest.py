#!/usr/bin/env python3
"""Sign the Electron update manifest (latest.yml) with Ed25519.

Why: the updater only compares the installer against the sha512 carried by
latest.yml. If the manifest itself is replaced (bucket write, proxy rewrite,
tampered release script), attacker and victim hash agree. A detached signature
over the manifest bytes closes that gap without a Windows code-signing cert,
which this project does not have yet (signAndEditExecutable=false).

Usage:
  # one-off: derive the public key to embed in the client
  python scripts/sign_update_manifest.py --print-public-key

  # sign the manifest produced by electron-pack for a version
  python scripts/sign_update_manifest.py --version 0.3.13

Key: base64 (or hex) 32-byte Ed25519 seed from LIANYU_UPDATE_SIGNING_KEY.
Never commit the key; keep it in .env / CI secret.

Output: frontend/release/v<version>/latest.yml.sig  (base64 signature)
Rollout order matters: deploy nginx route -> publish signed manifest ->
bake the public key into .env.production.cloud and rebuild the client.
"""
from __future__ import annotations

import argparse
import base64
import binascii
import os
import sys
from pathlib import Path

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

ROOT = Path(__file__).resolve().parents[1]
MANIFEST_NAME = "latest.yml"
SIGNATURE_SUFFIX = ".sig"


def load_seed(raw: str) -> bytes:
    value = (raw or "").strip()
    if not value:
        raise SystemExit("LIANYU_UPDATE_SIGNING_KEY is empty")
    try:
        if len(value) == 64 and all(c in "0123456789abcdefABCDEF" for c in value):
            seed = bytes.fromhex(value)
        else:
            seed = base64.b64decode(value, validate=True)
    except (binascii.Error, ValueError) as exc:
        raise SystemExit(f"LIANYU_UPDATE_SIGNING_KEY is not valid base64/hex: {exc}") from exc
    if len(seed) != 32:
        raise SystemExit(f"LIANYU_UPDATE_SIGNING_KEY must decode to 32 bytes, got {len(seed)}")
    return seed


def public_key_b64(private_key: Ed25519PrivateKey) -> str:
    der = private_key.public_key().public_bytes(
        encoding=serialization.Encoding.DER,
        format=serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    return base64.b64encode(der).decode("ascii")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", help="x.y.z; reads frontend/release/v<version>/latest.yml")
    parser.add_argument("--manifest", help="explicit path to latest.yml (overrides --version)")
    parser.add_argument("--out", help="signature output path (default: <manifest>.sig)")
    parser.add_argument("--print-public-key", action="store_true")
    args = parser.parse_args()

    seed = load_seed(os.environ.get("LIANYU_UPDATE_SIGNING_KEY", ""))
    private_key = Ed25519PrivateKey.from_private_bytes(seed)

    if args.print_public_key:
        print(public_key_b64(private_key))
        return 0

    if args.manifest:
        manifest = Path(args.manifest)
    elif args.version:
        manifest = ROOT / "frontend" / "release" / f"v{args.version}" / MANIFEST_NAME
    else:
        raise SystemExit("either --version, --manifest or --print-public-key is required")

    if not manifest.is_file():
        raise SystemExit(f"manifest not found: {manifest}")

    payload = manifest.read_bytes()
    signature = private_key.sign(payload)
    out = Path(args.out) if args.out else manifest.with_name(manifest.name + SIGNATURE_SUFFIX)
    out.write_text(base64.b64encode(signature).decode("ascii") + "\n", encoding="ascii")

    print(f"Signed {manifest.name} -> {out.name}")
    print("Public key (embed as VITE_LIANYU_UPDATE_PUBKEY):")
    print(public_key_b64(private_key))
    return 0


if __name__ == "__main__":
    sys.exit(main())
