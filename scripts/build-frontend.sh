#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# Build the Vue / ArcGIS frontend and sync the output into the Spring Boot
# static folder (src/main/resources/static).
#
# Source of truth: frontend/ (Vue 3 + Vite + @arcgis/core). Used by CI (Linux).
# This script only builds into frontend/dist/ — it does NOT sync into the
# Spring Boot static/ folder (backend no longer hosts the UI).
#
# Usage: bash scripts/build-frontend.sh
# ---------------------------------------------------------------------------
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FRONTEND="$ROOT/frontend"

if [ ! -f "$FRONTEND/package.json" ]; then
  echo "[ERROR] frontend/package.json not found at $FRONTEND"
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

echo "[OK] Frontend built at $FRONTEND/dist (backend no longer hosts static assets)"
