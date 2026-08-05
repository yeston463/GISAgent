#!/usr/bin/env python3
"""Live HTTP contract for the approved skyline and sunlight capabilities."""

from __future__ import annotations

import json
import os
import sys
import uuid
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


BASE_URL = os.environ.get("SPATIAL_API_BASE_URL", "http://127.0.0.1:8080").rstrip("/")


def post(path: str, payload: dict) -> dict:
    request = Request(
        f"{BASE_URL}{path}",
        data=json.dumps(payload).encode("utf-8"),
        headers={"Content-Type": "application/json; charset=utf-8"},
        method="POST",
    )
    try:
        with urlopen(request, timeout=45) as response:
            return json.loads(response.read().decode("utf-8"))
    except HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")
        raise AssertionError(f"{path} returned HTTP {error.code}: {detail}") from error
    except URLError as error:
        raise AssertionError(f"{path} is unreachable: {error.reason}") from error


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def main() -> int:
    memory_id = f"spatial-ci-{uuid.uuid4().hex}"
    demo = post("/api/gis/demo-context", {"memoryId": memory_id})
    require(demo.get("status") == "Success", "demo context was not saved")
    require(demo.get("hasAoi") and demo.get("hasBuildings"), "demo context is incomplete")
    require(demo.get("buildingCount") == 3, "demo context must contain three buildings")

    for label, message, capability in (
        ("skyline", "skyline analysis", "skyline_analysis"),
        ("sunlight", "sunlight analysis", "sunlight_analysis"),
        ("flood", "flood analysis", "flood_analysis"),
    ):
        response = post("/api/agent/chat/agentic", {"memoryId": memory_id, "message": message})
        outcome = response.get("outcome") or {}
        provenance = ((response.get("resultEnvelope") or {}).get("provenance") or {})
        require(outcome.get("status") == "Success", f"{label} status was {outcome.get('status')}")
        require(outcome.get("analysisType") == capability, f"{label} selected the wrong capability")
        require(any(step.get("phase") == "plan" for step in response.get("trace", [])),
                f"{label} has no plan trace")
        require(bool(response.get("commands")), f"{label} returned no map command")
        require(bool(response.get("metrics")), f"{label} returned no metrics")
        require(bool(provenance.get("runId")), f"{label} returned no provenance run id")
        print(f"{label}: {outcome['status']} ({provenance['runId']})")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except AssertionError as error:
        print(f"Spatial integration contract failed: {error}", file=sys.stderr)
        raise SystemExit(1)
