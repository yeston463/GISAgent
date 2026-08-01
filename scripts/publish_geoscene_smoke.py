# -*- coding: utf-8 -*-
"""Publish the latest CityEngine SLPK to GeoScene as a small smoke test."""

from __future__ import annotations

import argparse
import json
import sys
import time
import uuid
import shutil
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from geoscene_publisher import UPLOAD_TEMP_DIR, publish_slpk, publishing_status  # noqa: E402


DEFAULT_RUNS_DIR = Path(r"C:\GISAgentCityEngine\runs")
JSON_LOG = None


def _latest_slpk(runs_dir: Path) -> Path:
    candidates = sorted(
        runs_dir.rglob("*.slpk"),
        key=lambda path: path.stat().st_mtime,
        reverse=True,
    )
    if not candidates:
        raise FileNotFoundError(f"No SLPK files found under {runs_dir}")
    return candidates[0]


def _progress(stage: str, status: str, message: str, details: dict) -> None:
    payload = {
        "stage": stage,
        "status": status,
        "message": message,
        "details": details,
        "ts": int(time.time()),
    }
    _emit(payload)


def _emit(payload: dict) -> None:
    line = json.dumps(payload, ensure_ascii=False)
    print(line, flush=True)
    if JSON_LOG:
        JSON_LOG.write(line + "\n")
        JSON_LOG.flush()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--slpk", type=Path, default=None, help="SLPK path. Defaults to latest under C:\\GISAgentCityEngine\\runs.")
    parser.add_argument("--runs-dir", type=Path, default=DEFAULT_RUNS_DIR)
    parser.add_argument("--job-id", default=f"gisagent_smoke_{time.strftime('%Y%m%d_%H%M%S')}_{uuid.uuid4().hex[:6]}")
    parser.add_argument("--json-log", type=Path, default=None, help="Optional newline-delimited JSON log file.")
    args = parser.parse_args()

    global JSON_LOG
    if args.json_log:
        args.json_log.parent.mkdir(parents=True, exist_ok=True)
        JSON_LOG = args.json_log.open("a", encoding="utf-8")

    status = publishing_status()
    if not status["configured"]:
        raise RuntimeError("GeoScene publishing is not configured. Check GEOSCENE_PORTAL_* environment variables or .env.")

    slpk_path = args.slpk or _latest_slpk(args.runs_dir)
    _emit({
        "event": "smoke_start",
        "jobId": args.job_id,
        "slpk": str(slpk_path),
        "slpkBytes": slpk_path.stat().st_size,
        "portal": status["portalUrl"],
        "folder": status["folder"],
    })

    temp_upload = None
    try:
        UPLOAD_TEMP_DIR.mkdir(parents=True, exist_ok=True)
        temp_upload = UPLOAD_TEMP_DIR / f"{slpk_path.stem}-{uuid.uuid4().hex[:8]}{slpk_path.suffix}"
        shutil.copyfile(slpk_path, temp_upload)
        publication = publish_slpk(temp_upload, args.job_id, progress_callback=_progress)
    finally:
        if temp_upload and temp_upload.exists():
            temp_upload.unlink(missing_ok=True)
    _emit({
        "event": "smoke_success",
        "jobId": args.job_id,
        "publication": publication,
    })
    if JSON_LOG:
        JSON_LOG.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
