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
from gis import adapter, model, service


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


def test_overpass_uses_backup_endpoint_and_caches_result(monkeypatch, tmp_path):
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
    monkeypatch.setattr(adapter, "OVERPASS_CACHE_DIR", tmp_path)
    monkeypatch.setattr(adapter.urllib.request, "urlopen", fake_urlopen)
    monkeypatch.setattr(adapter.time, "sleep", lambda _seconds: None)
    monkeypatch.setattr(adapter, "_overpass_cache", {})
    monkeypatch.setattr(adapter, "OVERPASS_REQUEST_TIMEOUT_SECONDS", 6.0)
    monkeypatch.setattr(adapter, "OVERPASS_TOTAL_TIMEOUT_SECONDS", 14.0)

    assert adapter._call_overpass(query) == {"elements": [{"id": 42}]}
    assert calls == [("https://busy.example", 6.0), ("https://backup.example", 6.0)]

    # Same query is served from the short-lived cache; no further network call.
    assert adapter._call_overpass(query) == {"elements": [{"id": 42}]}
    assert len(calls) == 2


def test_overpass_disk_cache_survives_process_cache_reset(monkeypatch, tmp_path):
    query = "[out:json];way[building](0,0,1,1);out tags geom qt;"
    calls = []

    class FakeResponse:
        def read(self):
            return b'{"elements": [{"id": 7}]}'

        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc, traceback):
            return False

    def fake_urlopen(request, timeout):
        calls.append((request.full_url, timeout))
        return FakeResponse()

    monkeypatch.setattr(adapter, "OVERPASS_ENDPOINTS", ["https://cache.example"])
    monkeypatch.setattr(adapter, "OVERPASS_CACHE_DIR", tmp_path)
    monkeypatch.setattr(adapter, "OVERPASS_CACHE_TTL_SECONDS", 900)
    monkeypatch.setattr(adapter, "OVERPASS_DISK_CACHE_TTL_SECONDS", 3600)
    monkeypatch.setattr(adapter.urllib.request, "urlopen", fake_urlopen)
    monkeypatch.setattr(adapter, "_overpass_cache", {})

    assert adapter._call_overpass(query) == {"elements": [{"id": 7}]}
    assert len(calls) == 1

    # Simulate a Python GIS service restart: memory is empty, network must not
    # be called because the exact, still-valid OSM response is persisted.
    monkeypatch.setattr(adapter, "_overpass_cache", {})
    assert adapter._call_overpass(query) == {"elements": [{"id": 7}]}
    assert len(calls) == 1


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


def test_footprint_quality_rejects_tiny_aoi_edge_sliver():
    # A clipped building can be valid GeoJSON while still being only a tiny
    # triangle at the edge of a hand-drawn AOI.  It must not become a FAR or
    # CityEngine input building.
    sliver = {
        "type": "Feature",
        "geometry": {
            "type": "Polygon",
            "coordinates": [[[116.4337355, 39.8866564], [116.4333479, 39.8866451], [116.4337350, 39.8866663], [116.4337355, 39.8866564]]],
        },
        "properties": {},
    }
    valid, reason, area_sqm = main._footprint_quality(sliver)
    assert not valid
    assert "reliability threshold" in reason
    assert 0 < area_sqm < 25


def test_footprint_quality_accepts_normal_closed_building():
    building = {
        "type": "Feature",
        "geometry": {
            "type": "Polygon",
            "coordinates": [[[116.43, 39.88], [116.4303, 39.88], [116.4303, 39.8802], [116.43, 39.8802], [116.43, 39.88]]],
        },
        "properties": {},
    }
    valid, reason, area_sqm = main._footprint_quality(building)
    assert valid and not reason and area_sqm > 25


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


def test_mesh_height_source_is_preserved_in_vertical_profile():
    vertical = model._vertical_for_record({
        "height": 18.4,
        "floors": 6,
        "heightSource": "scene_mesh_z_range",
        "floorSource": "mesh_height_inferred",
        "heightEstimated": False,
    }, 100.0)
    assert vertical["height_m"] == 18.4
    assert vertical["height_source"] == "scene_mesh_z_range"
    assert vertical["floor_source"] == "mesh_height_inferred"
    assert vertical["estimated"] is False


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


def test_calculate_flood_risk_lock():
    aoi = {
        "type": "Feature",
        "geometry": {"type": "Polygon", "coordinates": [[[121.47, 31.23], [121.471, 31.23], [121.471, 31.231], [121.47, 31.231], [121.47, 31.23]]]},
        "properties": {},
    }
    dem = {
        "type": "FeatureCollection",
        "features": [
            {"type": "Feature", "geometry": {"type": "Point", "coordinates": [121.470 + col * .0004, 31.230 + row * .0004]},
             "properties": {"elevation_m": 4 if row == 1 and col == 1 else 8}}
            for row in range(3) for col in range(3)
        ],
    }
    result = main.calculate_flood_risk({
        "aoi": aoi, "dem": dem,
        "rainfall_scenario": {"rainfallMm": 120, "returnPeriodYears": 20},
    })

    assert result["status"] == "Success"
    assert result["analysis_type"] == "flood"
    assert result["high_risk_cell_count"] >= 1
    assert result["risk_cells"]["type"] == "FeatureCollection"
    assert result["risk_cells"]["features"][0]["properties"]["gridCellWidthM"] > 30
    assert result["building_exposure_available"] is False
    assert result["affected_building_count"] is None
    assert result["commands"][0]["action"] == "addGeoJsonLayer"
    advanced = next(command for command in result["commands"] if command["action"] == "showAdvancedAnalysis")
    assert advanced["params"]["analysisType"] == "flood"
    assert advanced["params"]["buildingExposureAvailable"] is False
    assert advanced["params"]["affectedBuildingCount"] is None


def test_flood_risk_classes_reduce_for_smaller_rainfall():
    aoi = {"type": "Feature", "geometry": {"type": "Polygon", "coordinates": [[[121.47, 31.23], [121.471, 31.23], [121.471, 31.231], [121.47, 31.231], [121.47, 31.23]]]}, "properties": {}}
    dem = {"type": "FeatureCollection", "features": [
        {"type": "Feature", "geometry": {"type": "Point", "coordinates": [121.470 + col * .0004, 31.230 + row * .0004]},
         "properties": {"elevation_m": 8 - row - col}}
        for row in range(3) for col in range(3)
    ]}
    storm = main.calculate_flood_risk({"aoi": aoi, "dem": dem, "rainfall_scenario": {"rainfallMm": 120}})
    light_rain = main.calculate_flood_risk({"aoi": aoi, "dem": dem, "rainfall_scenario": {"rainfallMm": 20}})

    assert light_rain["max_estimated_depth_m"] < storm["max_estimated_depth_m"]
    assert light_rain["high_risk_cell_count"] <= storm["high_risk_cell_count"]
    assert light_rain["commands"][0]["params"]["style"] == "floodRisk"
    assert "dem_quality" in light_rain


def test_calculate_flood_risk_returns_structured_missing_data():
    result = main.calculate_flood_risk({
        "aoi": {"type": "Feature", "geometry": {"type": "Point", "coordinates": [121.47, 31.23]}, "properties": {}},
    })

    assert result["status"] == "NoData"
    assert result["missing_data"] == ["hydrologic_dem_grid"]


def test_ascii_grid_dem_is_accepted_for_flood_screening(tmp_path, monkeypatch):
    raster_root = tmp_path / "gis-inputs"
    raster_path = raster_root / "session" / "terrain.asc"
    raster_path.parent.mkdir(parents=True)
    raster_path.write_text(
        "ncols 2\nnrows 2\nxllcorner 121.47\nyllcorner 31.23\ncellsize 0.0005\nNODATA_value -9999\n8 6\n4 5\n",
        encoding="utf-8",
    )
    monkeypatch.setenv("GIS_RASTER_ROOT", str(raster_root))

    samples = service._dem_samples({"kind": "raster", "path": str(raster_path)})

    assert len(samples) == 4
    assert min(sample[2] for sample in samples) == 4.0


def test_spatial_file_inspection_reports_normalized_ascii_metadata(tmp_path, monkeypatch):
    raster_root = tmp_path / "gis-inputs"
    raster_path = raster_root / "session" / "terrain.asc"
    raster_path.parent.mkdir(parents=True)
    raster_path.write_text(
        "ncols 3\nnrows 3\nxllcorner 121\nyllcorner 31\ncellsize 0.001\nNODATA_value -9999\n1 2 3\n4 5 6\n7 8 9\n",
        encoding="utf-8",
    )
    monkeypatch.setenv("GIS_RASTER_ROOT", str(raster_root))

    inspected = service.inspect_spatial_file({"path": str(raster_path), "extension": "asc"})

    assert inspected["metadataStatus"] == "ready"
    assert inspected["normalizedCrs"] == "EPSG:4326"
    assert inspected["width"] == 3
    assert inspected["gridPolicy"] == "native_grid_preserved"


def test_spatial_file_inspection_normalizes_geopackage(tmp_path, monkeypatch):
    geopandas = pytest.importorskip("geopandas")
    shapely = pytest.importorskip("shapely.geometry")
    raster_root = tmp_path / "gis-inputs"
    vector_path = raster_root / "session" / "buildings.gpkg"
    vector_path.parent.mkdir(parents=True)
    frame = geopandas.GeoDataFrame({"name": ["sample"]}, geometry=[shapely.Point(13522390, 3640000)], crs="EPSG:3857")
    frame.to_file(vector_path, driver="GPKG")
    monkeypatch.setenv("GIS_RASTER_ROOT", str(raster_root))

    inspected = service.inspect_spatial_file({"path": str(vector_path), "extension": "gpkg"})

    assert inspected["metadataStatus"] == "ready"
    assert inspected["normalizedCrs"] == "EPSG:4326"
    assert inspected["featureCount"] == 1
    coordinates = inspected["geoJson"]["features"][0]["geometry"]["coordinates"]
    assert 121 < coordinates[0] < 122


def test_hydrologic_flood_uses_depression_fill_flow_and_drainage(tmp_path, monkeypatch):
    raster_root = tmp_path / "gis-inputs"
    raster_path = raster_root / "session" / "terrain.asc"
    raster_path.parent.mkdir(parents=True)
    raster_path.write_text(
        "ncols 3\nnrows 3\nxllcorner 121.47\nyllcorner 31.23\ncellsize 0.0004\nNODATA_value -9999\n"
        "8 8 8\n8 4 8\n8 8 8\n", encoding="utf-8")
    monkeypatch.setenv("GIS_RASTER_ROOT", str(raster_root))
    aoi = {"type": "Feature", "geometry": {"type": "Polygon", "coordinates": [
        [[121.47, 31.23], [121.4712, 31.23], [121.4712, 31.2312], [121.47, 31.2312], [121.47, 31.23]]]}, "properties": {}}
    base = main.calculate_flood_risk({"aoi": aoi, "dem": {"kind": "raster", "path": str(raster_path)},
                                       "rainfall_scenario": {"rainfallMm": 120}})
    with_drainage = main.calculate_flood_risk({"aoi": aoi, "dem": {"kind": "raster", "path": str(raster_path)},
        "rainfall_scenario": {"rainfallMm": 120}, "drainage_network": {"type": "FeatureCollection", "features": [
            {"type": "Feature", "geometry": {"type": "LineString", "coordinates": [[121.47, 31.2304], [121.4708, 31.2304]]}, "properties": {}}]}})
    centre = min(base["risk_cells"]["features"], key=lambda item: item["properties"]["elevationM"])
    assert base["method"] == "hydrologic_dem_priority_flood_d8_flow_accumulation"
    assert centre["properties"]["depressionFillDepthM"] == 4.0
    assert "flowAccumulationCells" in centre["properties"]
    assert with_drainage["hydrology"]["drainageNetworkApplied"] is True
    assert with_drainage["max_estimated_depth_m"] < base["max_estimated_depth_m"]


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


def _cityengine_context_feature(index):
    west = 116.390 + index * 0.002
    return {
        "type": "Feature",
        "geometry": {"type": "Polygon", "coordinates": [[
            [west, 39.900], [west + 0.001, 39.900], [west + 0.001, 39.901],
            [west, 39.901], [west, 39.900],
        ]]},
        "properties": {"id": f"building-{index}"},
    }


def test_cityengine_low_count_context_recovers_full_aoi_footprints(monkeypatch):
    local = {"type": "FeatureCollection", "features": [_cityengine_context_feature(1), _cityengine_context_feature(2)]}
    recovered = {"type": "FeatureCollection", "features": [_cityengine_context_feature(index) for index in range(5)]}
    monkeypatch.setattr(
        service,
        "fetch_buildings_for_aoi",
        lambda _aoi: {"status": "Success", "buildings": recovered},
    )

    buildings, quality = service._resolve_cityengine_context_buildings({"type": "Feature", "geometry": local["features"][0]["geometry"]}, local)

    assert buildings == recovered
    assert quality["source"] == "openstreetmap_overpass_recovery"
    assert quality["contextBuildingCount"] == 2
    assert quality["recoveredBuildingCount"] == 5


def test_cityengine_rejects_unverified_sparse_context(monkeypatch):
    local = {"type": "FeatureCollection", "features": [_cityengine_context_feature(1), _cityengine_context_feature(2)]}
    monkeypatch.setattr(
        service,
        "fetch_buildings_for_aoi",
        lambda _aoi: {"status": "NoData", "message": "Overpass returned no building footprints"},
    )

    with pytest.raises(ValueError, match="仅有 2 栋有效建筑"):
        service._resolve_cityengine_context_buildings({"type": "Feature", "geometry": local["features"][0]["geometry"]}, local)


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


def test_flood_depth_magnitude_is_plausible_for_storm():
    """固定输入固定预期: 80mm/24h 暴雨在带汇流的城市 DEM 上，
    最大相对水深应达到分米级（0.1~1.0 m），而不是被压缩到厘米级。"""
    size = 16
    features = []
    for row in range(size):
        for col in range(size):
            x = 121.470 + col * .0004
            y = 31.230 + row * .0004
            dist = ((row - size / 2) ** 2 + (col - size / 2) ** 2) ** 0.5
            elev = 10 + (row - col) * 0.05 + max(0.0, dist - 4) * 0.8
            features.append({"type": "Feature",
                             "geometry": {"type": "Point", "coordinates": [x, y]},
                             "properties": {"elevation_m": round(elev, 2)}})
    aoi = {"type": "Feature", "geometry": {"type": "Polygon", "coordinates": [
        [121.470, 31.230], [121.474, 31.230], [121.474, 31.234], [121.470, 31.234], [121.470, 31.230]]},
        "properties": {}}
    dem = {"type": "FeatureCollection", "features": features}
    result = main.calculate_flood_risk({"aoi": aoi, "dem": dem,
                                        "rainfall_scenario": {"rainfallMm": 80, "returnPeriodYears": 20}})
    assert 0.10 <= result["max_estimated_depth_m"] <= 1.0
    assert result["high_risk_cell_count"] >= 1
