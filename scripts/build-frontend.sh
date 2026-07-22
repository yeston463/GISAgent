#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Build the Vue / ArcGIS frontend and sync the output into the Spring Boot
# static folder (src/main/resources/static).
#
# Source of truth: share/<snapshot>/frontend-arcgis1  (discovered automatically
# so the dated snapshot folder name does not matter). Used by CI (Linux).
#
# Usage: bash scripts/build-frontend.sh
# ---------------------------------------------------------------------------
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATIC="$ROOT/src/main/resources/static"
FRONTEND="$(find "$ROOT/share" -maxdepth 2 -type d -name frontend-arcgis1 | head -n1)"

if [ -z "$FRONTEND" ]; then
  echo "[ERROR] frontend-arcgis1 not found under $ROOT/share"
  exit 1
fi
echo "Using frontend source: $FRONTEND"

cd "$FRONTEND"
[ -d node_modules ] || npm install
echo "Building frontend ..."
npm run build

if [ ! -d dist ]; then
  echo "[ERROR] dist missing after build"
  exit 1
fi

echo "Syncing dist -> $STATIC"
mkdir -p "$STATIC"
# Refresh hashed assets: replace index.html + assets, keep other static files
# (e.g. demo-case which is placed manually).
rm -rf "$STATIC/assets" "$STATIC/index.html"
cp -r dist/. "$STATIC/"
echo "[OK] Frontend synced to $STATIC"
