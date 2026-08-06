# -*- coding: utf-8 -*-
"""Offline-sealed demo metrics: fixed input -> fixed expected output.

The competition demo must keep producing verifiable urban metrics with no
network and no optional GIS backend (no Overpass/ArcGIS, no arcpy/geopandas/
shapely). This suite drives ``service.extract_urban_metrics`` — a pure
standard-library aggregation added next to the existing backend-selected
``calculate_metrics`` — with hand-written, fixed GeoJSON, and asserts
deterministic, fixed ranges.

Every test below is a "fixed input, fixed expected" case: nothing is fetched,
nothing is sampled, and the constants are computed once and hard-coded here as
the acceptance baseline (see test_documented_baseline_values).

Baseline values (computed offline, standard-library path):
  site_area_sqm            = 126264.63  (0.004 deg lon x 0.003 deg lat at ~31.23N)
  total_const_area_sqm     = 479807.48  (sum footprint x floors)
  footprint_area_sqm       = 30303.59
  far                      = 3.8
  building_density         = 24.0 (%)
  building_count           = 6
  height_stats             max=66.0 min=12.0 avg=47.5 measured_ratio=1.0
  floor_stats              max=22 min=4 avg=15.8 counts={4:1,15:1,16:1,18:1,20:1,22:1}
  estimated_ratio          = 0.0
"""
import json
import time
from pathlib import Path

import pytest

from gis import adapter, model, service


BUNDLED_CASE = Path(__file__).resolve().parents[1] / "src" / "main" / "resources" / "demo-case" / "case.json"


# --------------------------------------------------------------------------- #
# Fixed offline demo dataset (mirrors the bundled demo case AOI/buildings)
# --------------------------------------------------------------------------- #
# AOI: Shanghai 121.472-121.476 E, 31.230-31.233 N (same extent as the bundled
# demo case). Hand-written closed ring, WGS84.
AOI = {
    "type": "Feature",
    "geometry": {"type": "Polygon", "coordinates": [[
        [121.472, 31.230], [121.476, 31.230], [121.476, 31.233],
        [121.472, 31.233], [121.472, 31.230],
    ]]},
    "properties": {"name": "offline-demo-aoi"},
}


def _rectangle(lon0, lon1, lat0, lat1, properties):
    """Build one simple hand-written rectangle building (closed ring)."""
    return {
        "type": "Feature",
        "geometry": {"type": "Polygon", "coordinates": [[
            [lon0, lat0], [lon1, lat0], [lon1, lat1], [lon0, lat1], [lon0, lat0],
        ]]},
        "properties": properties,
    }


# Six fixed buildings: 22/20/18/16/15/4 levels, each with explicit height.
BUILDINGS = {"type": "FeatureCollection", "features": [
    _rectangle(121.4721, 121.4729, 31.2302, 31.2308,
               {"id": "B1", "building:levels": 22, "height": 66.0, "building": "residential"}),
    _rectangle(121.4731, 121.4739, 31.2302, 31.2308,
               {"id": "B2", "building:levels": 20, "height": 60.0, "building": "residential"}),
    _rectangle(121.4741, 121.4749, 31.2302, 31.2308,
               {"id": "B3", "building:levels": 18, "height": 54.0, "building": "office"}),
    _rectangle(121.4721, 121.4729, 31.2312, 31.2318,
               {"id": "B4", "building:levels": 16, "height": 48.0, "building": "office"}),
    _rectangle(121.4731, 121.4739, 31.2312, 31.2318,
               {"id": "B5", "building:levels": 15, "height": 45.0, "building": "commercial"}),
    _rectangle(121.4741, 121.4749, 31.2312, 31.2318,
               {"id": "B6", "building:levels": 4, "height": 12.0, "building": "house"}),
]}


# --------------------------------------------------------------------------- #
# Main sealed metrics: fixed input -> fixed expected
# --------------------------------------------------------------------------- #
def test_documented_baseline_values():
    """固定输入固定预期: the documented baseline must reproduce exactly.

    These constants are the offline acceptance reference (see module docstring).
    """
    result = service.extract_urban_metrics(AOI, BUILDINGS)
    assert result["status"] == "Success"
    assert result["building_count"] == 6
    assert result["site_area_sqm"] == pytest.approx(126264.63, rel=1e-4)
    assert result["total_const_area_sqm"] == pytest.approx(479807.48, rel=1e-3)
    assert result["footprint_area_sqm"] == pytest.approx(30303.59, rel=1e-3)
    assert result["far"] == pytest.approx(3.8, abs=0.05)
    assert result["building_density"] == pytest.approx(24.0, abs=0.5)
    assert result["height_stats"]["max"] == 66.0
    assert result["height_stats"]["min"] == 12.0
    assert result["height_stats"]["avg"] == 47.5
    assert result["floor_stats"]["max"] == 22
    assert result["floor_stats"]["min"] == 4
    assert result["floor_stats"]["avg"] == 15.8


def test_bundled_case_matches_offline_acceptance_baseline():
    """The API demo fixture and its acceptance baseline must evolve together."""
    with BUNDLED_CASE.open(encoding="utf-8") as handle:
        bundled = json.load(handle)

    result = service.extract_urban_metrics(bundled["aoi"], bundled["buildings"])
    assert result["status"] == "Success"
    assert result["building_count"] == 6
    assert result["site_area_sqm"] == pytest.approx(126264.63, rel=1e-4)
    assert result["total_const_area_sqm"] == pytest.approx(359118.95, rel=1e-3)
    assert result["far"] == pytest.approx(2.844, abs=0.05)
    assert result["building_density"] == pytest.approx(19.33, abs=0.5)


def test_metric_shapes_and_types():
    """固定输入固定预期: return structure and value types are stable."""
    result = service.extract_urban_metrics(AOI, BUILDINGS)
    assert result["status"] == "Success"
    assert isinstance(result["far"], float) and 0 < result["far"] < 10
    assert isinstance(result["building_count"], int) and result["building_count"] == 6
    assert isinstance(result["site_area"], float) and result["site_area"] > 0
    assert isinstance(result["site_area_sqm"], float) and result["site_area_sqm"] > 0
    assert isinstance(result["total_const_area_sqm"], float)
    assert isinstance(result["building_density"], float)
    assert 0 < result["building_density"] < 100
    assert isinstance(result["height_stats"], dict) and result["height_stats"]["max"] > 0
    assert isinstance(result["floor_stats"], dict)
    assert isinstance(result["vertical_profile"], list) and len(result["vertical_profile"]) == 6
    assert result["vertical_profile"][0]["floors"] == 22
    assert [item["floors"] for item in result["vertical_profile"]] == [22, 20, 18, 16, 15, 4]
    assert result["gis_backend"] == "standard_library_metrics"
    assert result["metric_crs"] == "EPSG:32651"


def test_runs_in_milliseconds():
    """固定输入固定预期: 6 buildings + 1 AOI must finish well under a second.

    Measured on the reference machine: ~0.1-0.3 ms per call. The bound is kept
    generous (500 ms) so slow CI boxes still flag only real regressions.
    """
    started = time.perf_counter()
    for _ in range(20):
        result = service.extract_urban_metrics(AOI, BUILDINGS)
    elapsed_ms = (time.perf_counter() - started) * 1000.0
    assert result["status"] == "Success"
    assert elapsed_ms < 500.0


def test_never_touches_the_network():
    """固定输入固定预期: the sealed path must not call Overpass at all.

    If any branch ever reached the Overpass fetcher, this test would fail
    loudly instead of hanging on a dead endpoint during the live demo.
    """
    def forbidden_call(query):
        raise AssertionError("extract_urban_metrics must never call Overpass")

    monkeypatch = pytest.MonkeyPatch()
    monkeypatch.setattr(service.adapter, "_call_overpass", forbidden_call)
    try:
        result = service.extract_urban_metrics(AOI, BUILDINGS)
    finally:
        monkeypatch.undo()
    assert result["status"] == "Success"
    assert result["building_count"] == 6


# --------------------------------------------------------------------------- #
# Cross-check against the production offline-capable backend (geopandas path)
# --------------------------------------------------------------------------- #
def test_agrees_with_calculate_metrics_backend_path():
    """固定输入固定预期: pure stdlib and geopandas backends agree on the dataset.

        calculate_metrics tries arcpy first, then geopandas, then GeoScene Server
    and finally the standard library. ArcPy/GeoScene Server are optional and
    disabled by default; with the standard interpreter only geopandas is
    present. This proves the sealed pure path stays consistent with the
    backend the demo actually runs.
    """
    pytest.importorskip("geopandas")
    result = service.extract_urban_metrics(AOI, BUILDINGS)
    backend = service.calculate_metrics({"aoi": AOI, "buildings": BUILDINGS})
    assert backend["status"] == "Success"
    assert backend["building_count"] == result["building_count"] == 6
    # WGS84 approximate metre projection vs true projected area differ by <1%
    assert result["site_area_sqm"] == pytest.approx(backend["site_area_sqm"], rel=0.01)
    assert result["far"] == pytest.approx(backend["far"], rel=0.01)
    assert result["building_density"] == pytest.approx(backend["building_density"], rel=0.01)


# --------------------------------------------------------------------------- #
# Fault tolerance: invalid input / empty buildings
# --------------------------------------------------------------------------- #
def test_invalid_aoi_raises_value_error():
    """固定输入固定预期: missing/garbage AOI is rejected with ValueError."""
    with pytest.raises(ValueError):
        service.extract_urban_metrics(None, BUILDINGS)  # AOI required
    with pytest.raises(ValueError):
        service.extract_urban_metrics({}, BUILDINGS)  # no usable geometry


def test_unparseable_buildings_raises_value_error():
    """固定输入固定预期: non-GeoJSON buildings payload is rejected, not silently empty."""
    with pytest.raises(ValueError):
        service.extract_urban_metrics(AOI, "this is not geojson")


def test_empty_buildings_are_tolerated():
    """固定输入固定预期: empty building set -> NoData, FAR 0, site area preserved."""
    empty = {"type": "FeatureCollection", "features": []}
    result = service.extract_urban_metrics(AOI, empty)
    assert result["status"] == "NoData"
    assert result["building_count"] == 0
    assert result["far"] == 0
    assert result["site_area_sqm"] == pytest.approx(126264.63, rel=1e-4)
    # missing buildings key behaves like an empty set (route contract allows it)
    assert service.extract_urban_metrics(AOI)["status"] == "NoData"


def test_buildings_outside_aoi_are_excluded():
    """固定输入固定预期: a footprint outside the AOI bbox must not count."""
    payload = {"type": "FeatureCollection", "features": BUILDINGS["features"] + [
        _rectangle(121.500, 121.501, 31.230, 31.231,
                   {"id": "OUT", "building:levels": 3, "height": 9.0}),
    ]}
    result = service.extract_urban_metrics(AOI, payload)
    assert result["status"] == "Success"
    assert result["building_count"] == 6
    assert "OUT" not in [item["building_id"] for item in result["vertical_profile"]]


# --------------------------------------------------------------------------- #
# Missing levels -> estimation logic (deterministic heuristics)
# --------------------------------------------------------------------------- #
def test_missing_levels_are_estimated_from_building_type():
    """固定输入固定预期: no levels/height -> building-type estimate (12 floors for
    apartments -> 38.4 m at the 3.2 m storey default), flagged as estimated."""
    est = service.extract_urban_metrics(AOI, {"type": "FeatureCollection", "features": [
        _rectangle(121.4721, 121.4729, 31.2302, 31.2308,
                   {"id": "EST1", "building": "apartments"}),
    ]})
    profile = est["vertical_profile"][0]
    assert profile["floors"] == 12
    assert profile["floor_source"] == "estimated"
    assert profile["height_m"] == 38.4
    assert profile["height_source"] == "building_type_estimated"
    assert profile["estimated"] is True
    assert est["floor_stats"]["estimated_ratio"] == 1.0
    assert est["height_stats"]["estimated_ratio"] == 1.0
    assert est["height_stats"]["confidence"] == "low"


def test_height_only_buildings_infer_floors_from_height():
    """固定输入固定预期: no levels but explicit height -> floors = ceil(height/3.5)."""
    result = service.extract_urban_metrics(AOI, {"type": "FeatureCollection", "features": [
        _rectangle(121.4721, 121.4729, 31.2302, 31.2308,
                   {"id": "H1", "height": 35.0}),
    ]})
    profile = result["vertical_profile"][0]
    assert profile["floors"] == 10  # ceil(35 / 3.5)
    assert profile["floor_source"] == "height"
    assert profile["estimated"] is False
    assert result["height_stats"]["measured_ratio"] == 1.0
    assert result["height_stats"]["confidence"] == "high"
