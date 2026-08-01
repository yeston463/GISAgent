# -*- coding: utf-8 -*-
"""Inspect or cancel stale GISAgent GeoScene publishing jobs.

The command is dry-run by default. External state changes require --execute.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

import geoscene_publisher as publisher  # noqa: E402


def _emit(payload):
    print(json.dumps(payload, ensure_ascii=False), flush=True)


def main():
    parser = argparse.ArgumentParser(
        description="Find stale GISAgent Scene Service publish jobs and optionally cancel them."
    )
    parser.add_argument(
        "--execute",
        action="store_true",
        help="Cancel the discovered jobs. Without this flag the command is read-only.",
    )
    parser.add_argument(
        "--delete-partial-items",
        action="store_true",
        help="After cancellation, delete only the matching partial Scene Service items.",
    )
    parser.add_argument(
        "--min-age-seconds",
        type=int,
        default=publisher.STALE_PUBLICATION_MIN_AGE_SECONDS,
        help="Minimum partial-job age. Default comes from GEOSCENE_STALE_PUBLICATION_MIN_AGE_SECONDS.",
    )
    args = parser.parse_args()

    if args.delete_partial_items and not args.execute:
        parser.error("--delete-partial-items requires --execute")
    if args.min_age_seconds < 60:
        parser.error("--min-age-seconds must be at least 60")
    if not publisher.publishing_status()["configured"]:
        raise RuntimeError(
            "GeoScene publishing is not configured. Check GEOSCENE_PORTAL_* variables or .env."
        )

    token = publisher._token()
    stale = publisher.discover_stale_publications(
        token,
        min_age_seconds=args.min_age_seconds,
    )
    _emit(
        {
            "mode": "execute" if args.execute else "dry-run",
            "count": len(stale),
            "publications": stale,
        }
    )
    if not args.execute or not stale:
        return 0

    results = publisher.cancel_stale_publications(
        token,
        stale,
        delete_partial_items=args.delete_partial_items,
    )
    _emit({"event": "cleanup_complete", "results": results})
    failures = [
        result
        for result in results
        if not result.get("cancelRequested") or result.get("error")
    ]
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
