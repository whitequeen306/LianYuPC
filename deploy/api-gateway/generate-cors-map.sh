#!/bin/sh
# Generate nginx CORS map from CORS_ALLOWED_ORIGINS (comma-separated).
set -eu

OUT="/etc/nginx/conf.d/cors-map.conf"
ORIGINS="${CORS_ALLOWED_ORIGINS:-http://localhost:5173,https://localhost}"

{
  echo "map \$http_origin \$cors_origin {"
  echo "    default \"\";"
  echo "$ORIGINS" | tr ',' '\n' | while IFS= read -r origin; do
    origin=$(echo "$origin" | tr -d '[:space:]')
    if [ -n "$origin" ]; then
      echo "    \"$origin\"   \"\$http_origin\";"
    fi
  done
  echo "}"
} > "$OUT"
