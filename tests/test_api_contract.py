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


def test_flood_route_rejects_invalid_return_period(client):
    resp = client.post("/analysis/flood", json={"returnPeriodYears": 0})
    assert resp.status_code == 422


def test_flood_route_returns_risk_layer(client):
    aoi = {
        "type": "Feature",
        "geometry": {"type": "Polygon", "coordinates": [[[121.47, 31.23], [121.471, 31.23], [121.471, 31.231], [121.47, 31.231], [121.47, 31.23]]]},
        "properties": {},
    }
    resp = client.post("/analysis/flood", json={
        "aoi": aoi,
        "dem": {"samples": [
            {"longitude": 121.470 + col * .0004, "latitude": 31.230 + row * .0004,
             "elevation_m": 4 if row == 1 and col == 1 else 8}
            for row in range(3) for col in range(3)
        ]},
        "rainfall_scenario": {"rainfallMm": 120},
    })
    body = resp.json()
    assert resp.status_code == 200
    assert body["status"] == "Success"
    assert body["risk_cells"]["type"] == "FeatureCollection"
    assert body["commands"][0]["params"]["layerId"] == "flood-risk-screening"


def test_site_selection_ranks_candidates_and_excludes_near_constraint(client):
    candidates = {"type": "FeatureCollection", "features": [
        {"type": "Feature", "properties": {"id": "near-facility"}, "geometry": {"type": "Point", "coordinates": [121.4705, 31.2305]}},
        {"type": "Feature", "properties": {"id": "near-constraint"}, "geometry": {"type": "Point", "coordinates": [121.4800, 31.2305]}},
    ]}
    facilities = {"type": "FeatureCollection", "features": [
        {"type": "Feature", "properties": {}, "geometry": {"type": "Point", "coordinates": [121.4706, 31.2305]}}
    ]}
    constraints = {"type": "FeatureCollection", "features": [
        {"type": "Feature", "properties": {}, "geometry": {"type": "Point", "coordinates": [121.4801, 31.2305]}}
    ]}
    resp = client.post("/analysis/site-selection", json={
        "candidates": candidates, "facilities": facilities, "constraints": constraints,
        "facilityInfluenceM": 1000, "exclusionDistanceM": 100,
    })
    body = resp.json()
    assert resp.status_code == 200
    assert body["status"] == "Success"
    assert body["eligible_count"] == 1
    assert body["best_site"]["properties"]["id"] == "near-facility"
    assert body["ranked_sites"]["features"][1]["properties"]["screeningStatus"] == "excluded"


def test_site_selection_rejects_mixed_point_and_polygon_candidates(client):
    candidates = {"type": "FeatureCollection", "features": [
        {"type": "Feature", "properties": {}, "geometry": {"type": "Point", "coordinates": [121.47, 31.23]}},
        {"type": "Feature", "properties": {}, "geometry": {"type": "Polygon", "coordinates": [[[121.471, 31.23], [121.472, 31.23], [121.472, 31.231], [121.471, 31.23]]]}},
    ]}
    resp = client.post("/analysis/site-selection", json={"candidates": candidates})
    assert resp.status_code == 200
    assert resp.json()["status"] == "InvalidData"


def test_nearest_facility_distance_returns_distance_and_bearing(client):
    candidates = {"type": "FeatureCollection", "features": [
        {"type": "Feature", "properties": {"id": "candidate-a"},
         "geometry": {"type": "Point", "coordinates": [121.4705, 31.2305]}},
    ]}
    facilities = {"type": "FeatureCollection", "features": [
        {"type": "Feature", "properties": {"id": "facility-a", "name": "社区服务中心"},
         "geometry": {"type": "Point", "coordinates": [121.4706, 31.2305]}},
        {"type": "Feature", "properties": {"id": "facility-b"},
         "geometry": {"type": "Point", "coordinates": [121.4800, 31.2305]}},
    ]}
    resp = client.post("/analysis/execute", json={
        "operation": "nearest_facility_distance",
        "params": {"candidates": candidates, "facilities": facilities},
    })
    body = resp.json()
    assert resp.status_code == 200
    assert body["status"] == "Success"
    assert body["nearest_features"]["features"][0]["properties"]["nearestFacilityId"] == "facility-a"
    assert body["nearest_features"]["features"][0]["properties"]["nearestFacilityDistanceM"] > 0


def test_nearest_facility_distance_requires_facilities(client):
    resp = client.post("/analysis/execute", json={
        "operation": "nearest_facility_distance",
        "params": {"candidates": {"type": "FeatureCollection", "features": []}},
    })
    assert resp.status_code == 200
    assert resp.json()["status"] == "NoData"
    assert "facilities" in resp.json()["missing_data"]


def test_spatial_file_inspection_route_returns_ready_metadata(client, tmp_path, monkeypatch):
    root = tmp_path / "gis-inputs"
    path = root / "session" / "terrain.asc"
    path.parent.mkdir(parents=True)
    path.write_text(
        "ncols 3\nnrows 3\nxllcorner 121\nyllcorner 31\ncellsize 0.001\nNODATA_value -9999\n1 2 3\n4 5 6\n7 8 9\n",
        encoding="utf-8",
    )
    monkeypatch.setenv("GIS_RASTER_ROOT", str(root))

    resp = client.post("/analysis/data/inspect", json={"path": str(path), "extension": "asc"})

    assert resp.status_code == 200
    assert resp.json()["metadataStatus"] == "ready"
    assert resp.json()["width"] == 3


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


def test_analyze_area_prefers_context_buildings(client, monkeypatch):
    """加载数据包（上下文建筑）后，analyze_area 应优先用上下文数据，不请求 OSM。"""
    calls = {"fetch": 0}

    def spy_fetch(aoi):
        calls["fetch"] += 1
        raise AssertionError("context buildings 覆盖范围时不应回退 OSM")

    def fake_metrics(payload):
        assert payload.get("buildings") is not None
        return {"status": "Success", "building_count": 3, "far": 1.5}

    monkeypatch.setattr("gis.router.service.fetch_buildings_for_aoi", spy_fetch)
    monkeypatch.setattr("gis.router.service.calculate_metrics", fake_metrics)

    context_buildings = {"type": "FeatureCollection", "features": [{"type": "Feature", "properties": {}, "geometry": None}]}
    resp = client.post("/analysis/analyze_area", json={
        "lon": 116.39, "lat": 39.9, "radius": 500, "buildings": context_buildings})
    assert resp.status_code == 200
    body = resp.json()
    assert body.get("status") == "Success"
    assert body.get("building_source") == "context_data_pack"
    assert body.get("building_count") == 3
    assert calls["fetch"] == 0


def test_analyze_area_falls_back_to_osm_when_context_misses(client, monkeypatch):
    """上下文建筑未覆盖请求范围（裁剪为空）时，应回退 OSM 在线数据。"""
    fake_fetch = {"status": "Success", "source": "overpass", "building_count": 2,
                  "buildings": [{"id": 1}, {"id": 2}]}
    metrics_results = iter([
        {"status": "Success", "building_count": 0},   # 上下文裁剪为空
        {"status": "Success", "building_count": 2, "far": 1.2},  # OSM 拉取后
    ])

    monkeypatch.setattr("gis.router.service.fetch_buildings_for_aoi", lambda aoi: fake_fetch)
    monkeypatch.setattr("gis.router.service.calculate_metrics", lambda payload: next(metrics_results))

    resp = client.post("/analysis/analyze_area", json={
        "lon": 116.39, "lat": 39.9, "radius": 500, "buildings": {"type": "FeatureCollection", "features": []}})
    assert resp.status_code == 200
    body = resp.json()
    assert body.get("status") == "Success"
    assert body.get("building_source") == "overpass"
    assert body.get("building_count") == 2
