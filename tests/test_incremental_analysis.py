# -*- coding: utf-8 -*-
"""Tests for incremental analysis: compute_delta_metrics.

Covers:
- AOI unchanged + one building added -> incremental
- AOI changed -> full recompute
- Building removed -> correct new metrics
- Incremental result equals full recompute result (correctness guarantee)
"""
import pytest

from gis import service


# --------------------------------------------------------------------------- #
# Fixed test dataset (same coordinate system as test_offline_demo_metrics)
# --------------------------------------------------------------------------- #
AOI = {
    "type": "Feature",
    "geometry": {"type": "Polygon", "coordinates": [[
        [121.472, 31.230], [121.476, 31.230], [121.476, 31.233],
        [121.472, 31.233], [121.472, 31.230],
    ]]},
    "properties": {"name": "test-aoi"},
}


def _rectangle(lon0, lon1, lat0, lat1, properties):
    return {
        "type": "Feature",
        "geometry": {"type": "Polygon", "coordinates": [[
            [lon0, lat0], [lon1, lon0 + (lat1 - lat0)], [lon1, lat1], [lon0, lat1], [lon0, lat0],
        ]]},
        "properties": properties,
    }


def _rect(lon0, lon1, lat0, lat1, properties):
    """Build a rectangular building footprint (closed ring)."""
    return {
        "type": "Feature",
        "geometry": {"type": "Polygon", "coordinates": [[
            [lon0, lat0], [lon1, lat0], [lon1, lat1], [lon0, lat1], [lon0, lat0],
        ]]},
        "properties": properties,
    }


BUILDINGS_6 = {"type": "FeatureCollection", "features": [
    _rect(121.4721, 121.4729, 31.2302, 31.2308,
          {"id": "B1", "building:levels": 22, "height": 66.0, "building": "residential"}),
    _rect(121.4731, 121.4739, 31.2302, 31.2308,
          {"id": "B2", "building:levels": 20, "height": 60.0, "building": "residential"}),
    _rect(121.4741, 121.4749, 31.2302, 31.2308,
          {"id": "B3", "building:levels": 18, "height": 54.0, "building": "office"}),
    _rect(121.4721, 121.4729, 31.2312, 31.2318,
          {"id": "B4", "building:levels": 16, "height": 48.0, "building": "office"}),
    _rect(121.4731, 121.4739, 31.2312, 31.2318,
          {"id": "B5", "building:levels": 15, "height": 45.0, "building": "commercial"}),
    _rect(121.4741, 121.4749, 31.2312, 31.2318,
          {"id": "B6", "building:levels": 4, "height": 12.0, "building": "house"}),
]}


def _make_state(aoi, buildings):
    """Build a state dict with pre-computed metrics and the building-records
    cache so compute_delta_metrics can genuinely reuse unchanged buildings."""
    metrics = service.extract_urban_metrics(aoi, buildings, include_records=True)
    return {
        "aoi": aoi,
        "buildings": buildings,
        "metrics": metrics,
        "building_records": metrics.get("building_records", []),
    }


# --------------------------------------------------------------------------- #
# Tests
# --------------------------------------------------------------------------- #
class TestIncrementalAnalysis:
    def test_aoi_unchanged_building_added_triggers_incremental(self):
        """AOI same + one new building -> status=incremental, count increases."""
        previous = _make_state(AOI, BUILDINGS_6)

        new_buildings = {"type": "FeatureCollection", "features": list(BUILDINGS_6["features"]) + [
            _rect(121.4751, 121.4759, 31.2322, 31.2328,
                  {"id": "B7", "building:levels": 10, "height": 30.0, "building": "apartments"}),
        ]}
        current = _make_state(AOI, new_buildings)

        result = service.compute_delta_metrics(previous, current)

        assert result["status"] == "incremental"
        assert result["metrics"]["building_count"] == 7
        assert len(result["delta"]["added"]) == 1
        assert result["delta"]["added"][0]["properties"]["id"] == "B7"
        assert len(result["delta"]["removed"]) == 0
        assert len(result["delta"]["modified"]) == 0
        assert result["computationSaved"] != "0%"

    def test_aoi_changed_triggers_full_recompute(self):
        """AOI geometry changed -> status=full."""
        previous = _make_state(AOI, BUILDINGS_6)

        new_aoi = {
            "type": "Feature",
            "geometry": {"type": "Polygon", "coordinates": [[
                [121.471, 31.229], [121.477, 31.229], [121.477, 31.234],
                [121.471, 31.234], [121.471, 31.229],
            ]]},
            "properties": {"name": "test-aoi-modified"},
        }
        current = _make_state(new_aoi, BUILDINGS_6)

        result = service.compute_delta_metrics(previous, current)

        assert result["status"] == "full"
        assert result["computationSaved"] == "0%"
        assert result["metrics"]["building_count"] == 6

    def test_building_removed_correct_new_metrics(self):
        """Remove one building -> count decreases, metrics reflect 5 buildings."""
        previous = _make_state(AOI, BUILDINGS_6)

        remaining = {"type": "FeatureCollection", "features": BUILDINGS_6["features"][:5]}
        current = _make_state(AOI, remaining)

        result = service.compute_delta_metrics(previous, current)

        assert result["status"] == "incremental"
        assert result["metrics"]["building_count"] == 5
        assert len(result["delta"]["removed"]) == 1
        assert result["delta"]["removed"][0]["properties"]["id"] == "B6"
        assert result["metrics"]["far"] > 0
        assert result["metrics"]["site_area_sqm"] == pytest.approx(
            previous["metrics"]["site_area_sqm"], rel=1e-6
        )

    def test_incremental_equals_full_recompute_correctness(self):
        """Incremental result must match full recompute of same state."""
        previous = _make_state(AOI, BUILDINGS_6)

        new_buildings = {"type": "FeatureCollection", "features": list(BUILDINGS_6["features"][:5]) + [
            _rect(121.4751, 121.4759, 31.2322, 31.2328,
                  {"id": "B7", "building:levels": 10, "height": 30.0, "building": "apartments"}),
        ]}
        current = _make_state(AOI, new_buildings)

        incremental = service.compute_delta_metrics(previous, current)
        full = service.extract_urban_metrics(AOI, new_buildings)

        assert incremental["metrics"]["building_count"] == full["building_count"]
        assert incremental["metrics"]["far"] == pytest.approx(full["far"], rel=1e-6)
        assert incremental["metrics"]["site_area_sqm"] == pytest.approx(full["site_area_sqm"], rel=1e-6)
        assert incremental["metrics"]["total_const_area_sqm"] == pytest.approx(
            full["total_const_area_sqm"], rel=1e-6
        )
        assert incremental["metrics"]["footprint_area_sqm"] == pytest.approx(
            full["footprint_area_sqm"], rel=1e-6
        )
        assert incremental["metrics"]["building_density"] == pytest.approx(
            full["building_density"], rel=1e-6
        )

    def test_previous_state_none_triggers_full(self):
        """previous_state=None -> full recompute."""
        current = _make_state(AOI, BUILDINGS_6)
        result = service.compute_delta_metrics(None, current)

        assert result["status"] == "full"
        assert result["metrics"]["building_count"] == 6
        assert result["computationSaved"] == "0%"

    def test_no_changes_returns_100_percent_saved(self):
        """AOI and buildings identical -> 100% computation saved."""
        state = _make_state(AOI, BUILDINGS_6)
        result = service.compute_delta_metrics(state, state)

        assert result["status"] == "incremental"
        assert result["computationSaved"] == "100%"
        assert result["metrics"]["building_count"] == 6

    def test_reuses_cached_records_does_not_recompute_unchanged(self):
        """With a building-records cache, only the delta is recomputed."""
        previous = _make_state(AOI, BUILDINGS_6)

        new_buildings = {"type": "FeatureCollection", "features": list(BUILDINGS_6["features"]) + [
            _rect(121.4751, 121.4759, 31.2322, 31.2328,
                  {"id": "B7", "building:levels": 10, "height": 30.0, "building": "apartments"}),
        ]}
        current = _make_state(AOI, new_buildings)

        result = service.compute_delta_metrics(previous, current)

        assert result["status"] == "incremental"
        assert result["metrics"]["incremental_reused"] == 6
        assert result["metrics"]["incremental_computed"] == 1
        assert result["computationSaved"] == "86%"
        assert len(result["building_records"]) == 7

    def test_geometry_hash_deterministic(self):
        """Same geometry produces same hash."""
        geom = {"type": "Polygon", "coordinates": [[[0, 0], [1, 0], [1, 1], [0, 1], [0, 0]]]}
        assert service._geometry_hash(geom) == service._geometry_hash(geom)

    def test_geometry_hash_detects_difference(self):
        """Different geometries produce different hashes."""
        geom1 = {"type": "Polygon", "coordinates": [[[0, 0], [1, 0], [1, 1], [0, 1], [0, 0]]]}
        geom2 = {"type": "Polygon", "coordinates": [[[0, 0], [2, 0], [2, 2], [0, 2], [0, 0]]]}
        assert service._geometry_hash(geom1) != service._geometry_hash(geom2)

    def test_diff_buildings_identifies_modified(self):
        """Building with same id but different properties -> modified."""
        old = [{"type": "Feature", "geometry": {"type": "Polygon", "coordinates": [[[0, 0], [1, 0], [1, 1], [0, 1], [0, 0]]]},
                "properties": {"id": "B1", "building:levels": 10}}]
        new = [{"type": "Feature", "geometry": {"type": "Polygon", "coordinates": [[[0, 0], [1, 0], [1, 1], [0, 1], [0, 0]]]},
                "properties": {"id": "B1", "building:levels": 20}}]

        delta = service._diff_buildings(old, new)
        assert len(delta["modified"]) == 1
        assert len(delta["added"]) == 0
        assert len(delta["removed"]) == 0
