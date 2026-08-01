# -*- coding: utf-8 -*-
"""API contract + route-level E2E tests (Task C validation + Task G smoke).

These run the real FastAPI app through TestClient, exercising the full
route -> service -> model path. Network/GIS backends are mocked where needed
so the suite stays hermetic (no OSM/ArcGIS calls).
"""
import sys
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

# Ensure repo root (where gis/ and the local bridge modules live) is importable.
REPO_ROOT = Path(__file__).resolve().parent.parent
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from gis.router import app  # noqa: E402


@pytest.fixture
def client():
    return TestClient(app)


# --- Task C: input validation (malformed body -> 422, not 500) ---------------
def test_runtime_status_is_200(client):
    resp = client.get("/analysis/runtime")
    assert resp.status_code == 200
    assert isinstance(resp.json(), dict)


def test_buffer_valid_returns_feature_collection(client):
    resp = client.post("/analysis/buffer", json={"lon": 116.39, "lat": 39.9, "radius": 500})
    assert resp.status_code == 200
    body = resp.json()
    assert body.get("type") == "FeatureCollection"
    assert len(body.get("features", [])) == 1


def test_buffer_invalid_lon_type_returns_422(client):
    resp = client.post("/analysis/buffer", json={"lon": "not-a-number", "lat": 39.9})
    assert resp.status_code == 422


def test_buffer_negative_radius_returns_422(client):
    resp = client.post("/analysis/buffer", json={"lon": 116.39, "lat": 39.9, "radius": -5})
    assert resp.status_code == 422


def test_buffer_radius_too_large_returns_422(client):
    resp = client.post("/analysis/buffer", json={"lon": 116.39, "lat": 39.9, "radius": 1_000_000})
    assert resp.status_code == 422


def test_fetch_buildings_requires_aoi_or_coords_422(client):
    # Neither aoi nor lon/lat -> validation error
    resp = client.post("/analysis/fetch_buildings", json={"radius": 500})
    assert resp.status_code == 422


def test_spatial_execute_rejects_unknown_operation(client):
    resp = client.post("/analysis/execute", json={"operation": "run_python", "params": {}})
    assert resp.status_code == 200
    body = resp.json()
    assert body["status"] == "Error"
    assert "urban_metrics" in body["allowed_operations"]


def test_spatial_execute_dispatches_whitelisted_operation(client, monkeypatch):
    expected = {"status": "Success", "analysis_type": "skyline", "commands": []}
    monkeypatch.setattr("gis.router.service.calculate_skyline", lambda payload: expected)
    resp = client.post("/analysis/execute", json={
        "operation": "skyline",
        "params": {"buildings": {"type": "FeatureCollection", "features": []}},
    })
    assert resp.status_code == 200
    assert resp.json() == expected


# --- Task G: route-level E2E with mocked GIS backends ------------------------
def test_fetch_buildings_with_aoi_e2e(client, monkeypatch):
    fake = {
        "status": "Success",
        "source": "overpass",
        "building_count": 3,
        "buildings": [{"id": i} for i in range(3)],
    }
    monkeypatch.setattr("gis.router.service.fetch_buildings_for_aoi", lambda aoi: fake)
    aoi = {
        "type": "Feature",
        "geometry": {"type": "Polygon", "coordinates": [[[0, 0], [0, 1], [1, 1], [1, 0], [0, 0]]]},
        "properties": {},
    }
    resp = client.post("/analysis/fetch_buildings", json={"aoi": aoi})
    assert resp.status_code == 200
    assert resp.json().get("building_count") == 3


def test_analyze_area_e2e_pipeline(client, monkeypatch):
    fake_fetch = {
        "status": "Success",
        "source": "overpass",
        "building_count": 2,
        "buildings": [{"id": 1}, {"id": 2}],
    }
    fake_metrics = {"status": "Success", "building_count": 2, "far": 1.2}
    monkeypatch.setattr("gis.router.service.fetch_buildings_for_aoi", lambda aoi: fake_fetch)
    monkeypatch.setattr("gis.router.service.calculate_metrics", lambda payload: fake_metrics)

    resp = client.post("/analysis/analyze_area", json={"lon": 116.39, "lat": 39.9, "radius": 500})
    assert resp.status_code == 200
    body = resp.json()
    assert body.get("status") == "Success"
    assert body.get("building_count") == 2
    assert "aoi" in body


def test_urban_metrics_returns_effective_height_and_source(client):
    aoi = {
        "type": "Feature",
        "geometry": {"type": "Polygon", "coordinates": [[[121.47, 31.23], [121.471, 31.23], [121.471, 31.231], [121.47, 31.231], [121.47, 31.23]]]},
        "properties": {},
    }
    buildings = {
        "type": "FeatureCollection",
        "features": [{
            "type": "Feature",
            "geometry": {"type": "Polygon", "coordinates": [[[121.4701, 31.2301], [121.4703, 31.2301], [121.4703, 31.2303], [121.4701, 31.2303], [121.4701, 31.2301]]]},
            "properties": {"id": "heightless-1", "building": "apartments"},
        }],
    }

    resp = client.post("/analysis/urban_metrics", json={"aoi": aoi, "buildings": buildings})

    assert resp.status_code == 200
    body = resp.json()
    assert body["height_stats"]["max"] == 38.4
    assert body["height_stats"]["confidence"] == "low"
    assert body["vertical_profile"][0]["building_id"] == "heightless-1"
    assert body["vertical_profile"][0]["height_source"] == "building_type_estimated"
