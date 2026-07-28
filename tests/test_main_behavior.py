# -*- coding: utf-8 -*-
"""Behavior-locking tests for main.py.

These tests pin the CURRENT correct behavior of the pure/model helpers and the
backend-independent aggregators BEFORE the module is split into
router / service / adapter / model. They must keep passing after the refactor.

Design rules (so the suite runs in a clean env without optional GIS libs):
- No network calls: pass `buildings`/`aoi` directly instead of triggering
  fetch_buildings_for_aoi -> Overpass.
- No arcpy/geopandas: covered functions either are pure or fall back to the
  standard-library path. `calculate_metrics` success path needs a GIS backend,
  so we lock the pure aggregator `_build_metrics_result` directly instead.
- No CityEngine/GeoScene IO: only the early-return branches of
  ensure_cityengine_published are exercised.
"""
import math
import urllib.error

import pytest

import main
from gis import adapter


# --------------------------------------------------------------------------- #
# Pure geometry / CRS helpers (model layer)
# --------------------------------------------------------------------------- #
def test_metric_crs_lock():
    # Shanghai-ish -> UTM zone 51N
    assert main._metric_crs(121.47, 31.23) == "EPSG:32651"
    # lon 0 -> zone int(180/6)+1 = 31, lat >= 0 -> 32631
    assert main._metric_crs(0.0, 0.0) == "EPSG:32631"
    # missing coords -> web mercator fallback
    assert main._metric_crs(None, None) == "EPSG:3857"


def test_epsg_number_lock():
    assert main._epsg_number("EPSG:4326") == 4326
    assert main._epsg_number(3857) == 3857
    assert main._epsg_number(None) == 4326
    # trailing digits are parsed by the regex; "WGS84" -> 84 (documents current behavior)
    assert main._epsg_number("WGS84") == 84


def test_normalize_geometry_lock():
    assert main._normalize_geometry(
        {"rings": [[[0, 0], [1, 0], [1, 1], [0, 1], [0, 0]]]}
    )["type"] == "Polygon"
    pt = main._normalize_geometry({"x": 121.0, "y": 31.0})
    assert pt["type"] == "Point" and pt["coordinates"] == [121.0, 31.0]
    assert main._normalize_geometry({"type": "Point", "coordinates": [1, 2]}) == {
        "type": "Point",
        "coordinates": [1, 2],
    }
    assert main._normalize_geometry(None) is None


def test_features_from_source_lock():
    fc = {
        "type": "FeatureCollection",
        "features": [
            {"type": "Feature", "geometry": {"type": "Point", "coordinates": [1, 2]}, "properties": {"k": "v"}}
        ],
    }
    feats = main._features_from_source(fc)
    assert len(feats) == 1
    assert feats[0]["geometry"]["type"] == "Point"
    # raw ArcGIS polygon dict (rings) becomes a single normalized feature
    feats2 = main._features_from_source({"rings": [[[0, 0], [1, 0], [1, 1], [0, 0]]]})
    assert len(feats2) == 1 and feats2[0]["geometry"]["type"] == "Polygon"


def test_ring_area_lock():
    coords = [[0, 0], [1, 0], [1, 1], [0, 1], [0, 0]]
    assert abs(main._ring_area(coords) - 1.0) < 1e-9
    assert main._ring_area([[0, 0]]) == 0.0


def test_overpass_query_bbox_ordering():
    # Overpass expects south,west,north,east
    q = main._overpass_query((116.0, 39.0, 117.0, 40.0))
    assert "39.0,116.0,40.0,117.0" in q
    assert "out tags geom qt;" in q


def test_overpass_uses_backup_endpoint_and_caches_result(monkeypatch):
    """A busy public endpoint must not turn valid OSM geometry into a bbox fallback."""
    query = "[out:json];way[building](0,0,1,1);out tags geom qt;"
    calls = []

    class FakeResponse:
        def read(self):
            return b'{"elements": [{"id": 42}]}'

        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc, traceback):
            return False

    def fake_urlopen(request, timeout):
        calls.append((request.full_url, timeout))
        if len(calls) == 1:
            raise urllib.error.HTTPError(request.full_url, 504, "Gateway Timeout", {}, None)
        return FakeResponse()

    monkeypatch.setattr(adapter, "OVERPASS_ENDPOINTS", ["https://busy.example", "https://backup.example"])
    monkeypatch.setattr(adapter.urllib.request, "urlopen", fake_urlopen)
    monkeypatch.setattr(adapter.time, "sleep", lambda _seconds: None)
    monkeypatch.setattr(adapter, "_overpass_cache", {})

    assert adapter._call_overpass(query) == {"elements": [{"id": 42}]}
    assert calls == [("https://busy.example", 25), ("https://backup.example", 25)]

    # Same query is served from the short-lived cache; no further network call.
    assert adapter._call_overpass(query) == {"elements": [{"id": 42}]}
    assert len(calls) == 2


def test_elements_to_features_lock():
    elements = [{
        "type": "way",
        "id": 1,
        "geometry": [{"lon": 0.0, "lat": 0.0}, {"lon": 1.0, "lat": 0.0}, {"lon": 1.0, "lat": 1.0}],
        "tags": {"building": "yes"},
    }]
    feats = main._elements_to_features(elements)
    assert len(feats) == 1
    assert feats[0]["geometry"]["type"] == "Polygon"
    assert feats[0]["properties"]["osm_id"] == 1


def test_create_buffer_feature_approx_lock():
    f = main._create_buffer_feature_approx(121.47, 31.23, 500)
    assert f["properties"]["backend"] == "standard_library_approx"
    assert f["properties"]["radius"] == 500
    coords = f["geometry"]["coordinates"][0]
    assert len(coords) == 97  # 96 samples + closing point
    assert coords[0] == coords[-1]  # closed ring


# --------------------------------------------------------------------------- #
# Pure value / JSON helpers (model layer)
# --------------------------------------------------------------------------- #
def test_parse_number_lock():
    assert main._parse_number("3.5") == 3.5
    assert main._parse_number(None) is None
    assert main._parse_number(10) == 10.0
    assert main._parse_number("abc") is None
    assert main._parse_number("高度 12.5 m") == 12.5


def test_safe_float_lock():
    assert main._safe_float("3.5") == 3.5
    assert main._safe_float(None, 500) == 500
    assert main._safe_float("x", 1) == 1


def test_is_missing_value_lock():
    assert main._is_missing_value(None)
    assert not main._is_missing_value(0)
    assert not main._is_missing_value("")
    assert not main._is_missing_value([])


def test_json_safe_dict_lock():
    data = {"a": 1, "b": None, "c": float("nan"), "d": "x"}
    out = main._json_safe_dict(data)
    assert "a" in out and out["a"] == 1
    assert "b" not in out
    assert "c" not in out
    assert out["d"] == "x"


# --------------------------------------------------------------------------- #
# Business-rule heuristics (model layer) - must not change silently
# --------------------------------------------------------------------------- #
def test_estimate_missing_floors_lock():
    assert main._estimate_missing_floors({"building": "apartments"}, 100) == 12
    assert main._estimate_missing_floors({"building": "office"}, 100) == 10
    assert main._estimate_missing_floors({"building": "house"}, 100) == 1
    assert main._estimate_missing_floors({"building": "unknown"}, 100) == 7
    # large footprint bumps the estimate
    assert main._estimate_missing_floors({"building": "apartments"}, 20000) == 14


def test_floor_for_record_lock():
    floor, src = main._floor_for_record({"floors": 8}, 100.0)
    assert floor == 8 and src == "floors"
    floor, src = main._floor_for_record({"height": 35.0}, 100.0)
    assert floor == 10 and src == "height"  # 35 / 3.5 == 10
    floor, src = main._floor_for_record({}, 100.0)
    assert src == "estimated"


def test_evaluate_rule_lock():
    r = main._evaluate_rule("far", 1.5, {"min": 0.5, "max": 2.0})
    assert r["passed"] is True and r["value"] == 1.5
    r2 = main._evaluate_rule("far", 3.0, {"max": 2.0})
    assert r2["passed"] is False


# --------------------------------------------------------------------------- #
# Pure math (model layer)
# --------------------------------------------------------------------------- #
def test_convex_hull_lock():
    pts = [(0, 0), (1, 0), (1, 1), (0, 1)]
    hull = main._convex_hull(pts)
    assert len(hull) == 4
    assert set(hull) == {(0, 0), (1, 0), (1, 1), (0, 1)}


def test_distance_and_bearing_lock():
    dist, bear = main._distance_and_bearing((0, 0), (1, 1))
    assert abs(dist - 157427.3) < 1
    assert abs(bear - 45.0) < 0.5


def test_solar_position_lock():
    # ~ summer solstice noon at lat 31 -> high sun, due south azimuth
    alt, azi = main._solar_position(31.23, 173, 12)
    assert 80 < alt < 84
    assert 175 < azi < 185


# --------------------------------------------------------------------------- #
# Backend-independent aggregators (service layer, pure core)
# --------------------------------------------------------------------------- #
def test_build_metrics_result_far_lock():
    records = [{
        "properties": {"building": "apartments", "floors": 10, "building:levels": 10},
        "footprint_area": 100.0,
    }]
    result = main._build_metrics_result(
        records, site_area=100.0, buffer_area=0.0, backend="open_source_geopandas"
    )
    assert result["status"] == "Success"
    assert result["building_count"] == 1
    assert result["far"] == 10.0
    assert result["lower_bound_far"] == 10.0
    assert result["building_density"] == 100.0
    assert result["floor_stats"]["max"] == 10
    assert result["floor_stats"]["confidence"] == "high"
    assert result["floor_stats"]["measured_ratio"] == 1.0


def test_vertical_profile_uses_transparent_estimate_when_height_is_missing():
    result = main._build_metrics_result(
        [{"properties": {"id": "estimated-1", "building": "apartments"}, "footprint_area": 100.0}],
        site_area=100.0,
        buffer_area=0.0,
        backend="open_source_geopandas",
    )

    assert result["height_stats"]["max"] == 38.4
    assert result["height_stats"]["estimated_ratio"] == 1.0
    assert result["height_stats"]["confidence"] == "low"
    assert result["vertical_profile"] == [{
        "building_id": "estimated-1",
        "floors": 12,
        "floor_source": "estimated",
        "height_m": 38.4,
        "height_source": "building_type_estimated",
        "estimated": True,
    }]


def test_vertical_profile_prefers_measured_height():
    result = main._build_metrics_result(
        [{"properties": {"id": "measured-1", "height": 35}, "footprint_area": 100.0}],
        site_area=100.0,
        buffer_area=0.0,
        backend="open_source_geopandas",
    )

    assert result["height_stats"]["max"] == 35.0
    assert result["height_stats"]["measured_ratio"] == 1.0
    assert result["vertical_profile"][0]["height_source"] == "height"


def test_build_metrics_result_no_buildings_lock():
    result = main._build_metrics_result([], site_area=100.0, buffer_area=0.0, backend="x")
    assert result["status"] == "NoData"
    assert result["building_count"] == 0
    assert result["far"] == 0


def test_build_metrics_result_zero_site_area_lock():
    records = [{"properties": {}, "footprint_area": 10.0}]
    result = main._build_metrics_result(records, site_area=0, buffer_area=0.0, backend="x")
    assert result["status"] == "Fail"


def test_calculate_skyline_lock():
    payload = {
        "buildings": {
            "type": "FeatureCollection",
            "features": [{
                "type": "Feature",
                "geometry": {
                    "type": "Polygon",
                    "coordinates": [[[121.47, 31.23], [121.471, 31.23], [121.471, 31.231], [121.47, 31.231], [121.47, 31.23]]],
                },
                "properties": {"name": "Tower", "height": 50.0},
            }],
        },
        "bin_count": 24,
    }
    result = main.calculate_skyline(payload)
    assert result["status"] == "Success"
    assert result["building_count"] == 1
    assert result["max_height"] == 50.0
    assert result["mean_height"] == 50.0
    assert len(result["skyline_profile"]) == 24
    assert any(p["height"] == 50.0 for p in result["skyline_profile"])
    assert result["data_source"] == "current_context"


def test_calculate_sunlight_lock():
    payload = {
        "buildings": {
            "type": "FeatureCollection",
            "features": [{
                "type": "Feature",
                "geometry": {
                    "type": "Polygon",
                    "coordinates": [[[121.47, 31.23], [121.471, 31.23], [121.471, 31.231], [121.47, 31.231], [121.47, 31.23]]],
                },
                "properties": {"name": "Tower", "height": 50.0},
            }],
        },
        "date": "2024-06-21",
        "hours": [8, 10, 12, 14, 16],
    }
    result = main.calculate_sunlight(payload)
    assert result["status"] == "Success"
    assert result["building_count"] == 1
    assert result["sample_count"] == 5
    assert result["shadows"]["type"] == "FeatureCollection"
    assert result["max_shadow_length_m"] >= 0


# --------------------------------------------------------------------------- #
# CityEngine pipeline state machine (pure branch logic)
# --------------------------------------------------------------------------- #
def test_cityengine_pipeline_terminal_lock():
    assert main._cityengine_pipeline_terminal({"status": "failed"})
    assert main._cityengine_pipeline_terminal({"status": "completed", "outputs": {}})
    assert main._cityengine_pipeline_terminal(
        {"status": "completed", "outputs": {"slpk": "x"}, "sceneServiceUrl": "y"}
    )
    # completed with slpk but not yet hosted/published -> not terminal
    assert not main._cityengine_pipeline_terminal({"status": "completed", "outputs": {"slpk": "x"}})
    assert not main._cityengine_pipeline_terminal({"status": "running"})


def test_ensure_cityengine_published_early_returns():
    # not completed -> returned unchanged, no IO
    running = {"status": "running", "x": 1}
    assert main.ensure_cityengine_published("j1", running) is running
    # completed + already shared -> returned unchanged, no IO
    shared = {
        "status": "completed",
        "sceneServiceUrl": "u",
        "publication": {"sharedWithEveryone": True, "itemDetails": {"a": 1}},
    }
    assert main.ensure_cityengine_published("j2", shared) is shared


# --------------------------------------------------------------------------- #
# Runtime / capability reporting (router health shape)
# --------------------------------------------------------------------------- #
def test_runtime_status_shape_lock():
    st = main.runtime_status()
    assert st["status"] == "Success"
    assert "preferred_backend" in st
    assert "capabilities" in st
    for key in ("arcpy", "geopandas", "pandas", "shapely"):
        assert key in st["capabilities"]


# --------------------------------------------------------------------------- #
# Buffer input validation (service layer, backend-independent)
# --------------------------------------------------------------------------- #
def test_create_buffer_feature_validation():
    with pytest.raises(ValueError):
        main.create_buffer_feature(0, 0, 500)  # (0,0) rejected
    with pytest.raises(ValueError):
        main.create_buffer_feature(None, None, 500)  # missing lon/lat
