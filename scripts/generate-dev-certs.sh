#!/usr/bin/env bash
# Generate self-signed TLS cert for api-gateway (local Docker / dev).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CERTS="$ROOT/certs"
mkdir -p "$CERTS"

if ! command -v openssl >/dev/null 2>&1; then
  echo "openssl not found" >&2
  exit 1
fi

openssl req -x509 -nodes -newkey rsa:2048 \
  -keyout "$CERTS/server.key" \
  -out "$CERTS/server.crt" \
  -days 365 \
  -subj "/CN=localhost/O=LianYu-PC/C=CN" \
  -addext "subjectAltName=DNS:localhost,IP:127.0.0.1"

echo "Created $CERTS/server.crt and $CERTS/server.key"
echo "Do not commit private keys. See certs/README.md."
