# -*- coding: utf-8 -*-
"""Mock end-to-end tests for the GeoScene Enterprise server metrics backend.

The server path replaces the local GIS interpreter's geometry math with calls
to the GeoScene Enterprise Geometry Service (areasAndLengths / intersect /
union). Here that network boundary is stubbed with a reference implementation
that reproduces the standard-library semantics applied in extract_urban_metrics,
which lets us assert the determinism guarantee: **the server backend and the
pure standard-library backend produce identical metrics for the same input.**
"""
import json
from contextlib import ExitStack
from unittest.mock import patch

import pytest

from gis import adapter, model, service


AOI = {
    "type": "Feature",
    "geometry": {"type": "Polygon", "coordinates": [[
        [121.472, 31.230], [121.476, 31.230], [121.476, 31.233],
        [121.472, 31.233], [121.472, 31.230],
    ]]},
    "properties": {"name": "aoi"},
}


def _rect(lon0, lon1, lat0, lat1, properties):
    return {
        "type": "Feature",
        "geometry": {"type": "Polygon", "coordinates": [[
            [lon0, lat0], [lon1, lat0], [lon1, lat1], [lon0, lat1], [lon0, lat0],
        ]]},
        "properties": properties,
    }


BUILDINGS = {"type": "FeatureCollection", "features": [
    _rect(121.4721, 121.4729, 31.2302, 31.2308, {"id": "B1", "building:levels": 22, "height": 66.0}),
    _rect(121.4731, 121.4739, 31.2302, 31.2308, {"id": "B2", "building:levels": 20, "height": 60.0}),
    _rect(121.4741, 121.4749, 31.2302, 31.2308, {"id": "B3", "building:levels": 18, "height": 54.0}),
    _rect(121.4721, 121.4729, 31.2312, 31.2318, {"id": "B4", "building:levels": 16, "height": 48.0}),
    _rect(121.4731, 121.4739, 31.2312, 31.2318, {"id": "B5", "building:levels": 15, "height": 45.0}),
    _rect(121.4741, 121.4749, 31.2312, 31.2318, {"id": "B6", "building:levels": 4, "height": 12.0}),
]}


# --- reference implementation that mimics the standard-library semantics ------
def _ring_area(geom):
    """Match model._ring_area_sqm aggregation used by the stdlib path."""
    if not geom:
        return 0.0
    if geom.get("type") == "Polygon":
        return model._ring_area_sqm(geom["coordinates"][0])
    total = 0.0
    for polygon in geom.get("coordinates") or []:
        if polygon:
            total += model._ring_area_sqm(polygon[0])
    return total


def _extent_of(geom):
    xs, ys = [], []
    for point in _points(geom):
        xs.append(point[0])
        ys.append(point[1])
    return (min(xs), min(ys), max(xs), max(ys))


def _points(geom):
    """Flatten a geometry's coordinates into [lon, lat] points."""
    out = []
    for c in geom.get("coordinates") or []:
        if isinstance(c, (int, float)):
            continue
        if len(c) == 2 and isinstance(c[0], (int, float)) and isinstance(c[1], (int, float)):
            out.append(c)
        else:
            out.extend(_points({"coordinates": c}))
    return out


def _overlaps(g1, g2):
    a = _extent_of(g1)
    b = _extent_of(g2)
    return not (a[2] < b[0] or a[0] > b[2] or a[3] < b[1] or a[1] > b[3])


def _fake_server(url, fields=None, file_path=None, timeout=300):
    """Stand-in for the GeoScene Enterprise Geometry Service."""
    op = url.rstrip("/").split("/")[-1].split("?")[0]
    fields = fields or {}
    if op == "areasAndLengths":
        return {"areas": [_ring_area(json.loads(fields["polygons"]))]}
    if op == "intersect":
        g1, g2 = json.loads(fields["geometries1"])[0], json.loads(fields["geometries2"])[0]
        return {"geometries": [g1]} if _overlaps(g1, g2) else {"geometries": []}
    if op == "union":
        geoms = json.loads(fields["geometries"])
        return {"geometries": [geoms[0]]} if geoms else {"geometries": []}
    raise AssertionError(f"unexpected geometry-service operation: {url}")


def _server_ctx():
    stack = ExitStack()
    stack.enter_context(patch.object(adapter, "HAS_GEOSCENE_SERVER", True))
    stack.enter_context(patch.object(adapter, "_geoscene_token", return_value="token-x"))
    stack.enter_context(patch.object(adapter, "_geoscene_request", side_effect=_fake_server))
    return stack


class TestGeosceneServerMetrics:
    def test_server_result_equals_stdlib_reference(self):
        """Server backend must reproduce the pure stdlib metrics (determinism)."""
        with _server_ctx():
            result = service._calculate_metrics_geoscene_server(
                {"aoi": AOI, "buildings": BUILDINGS}
            )
        reference = service.extract_urban_metrics(AOI, BUILDINGS)

        assert result["gis_backend"] == "geoscene_server"
        assert result["building_count"] == reference["building_count"]
        for key in ("far", "site_area_sqm", "total_const_area_sqm",
                    "footprint_area_sqm", "building_density"):
            assert result[key] == pytest.approx(reference[key], rel=1e-9)

    def test_server_requires_configuration(self):
        with patch.object(adapter, "HAS_GEOSCENE_SERVER", False):
            with pytest.raises(RuntimeError, match="not configured"):
                service._calculate_metrics_geoscene_server(
                    {"aoi": AOI, "buildings": BUILDINGS}
                )

    def test_server_is_used_when_configured_and_healthy(self):
        with patch.object(adapter, "HAS_GEOSCENE_SERVER", True), \
             patch.object(adapter, "HAS_ARCPY", False), \
             patch.object(adapter, "HAS_OPEN_SOURCE", False), \
             patch.object(adapter, "_geoscene_token", return_value="token-x"), \
             patch.object(adapter, "_geoscene_request", side_effect=_fake_server):
            result = service.calculate_metrics({"aoi": AOI, "buildings": BUILDINGS})

        assert result["status"] == "Success"
        assert result["gis_backend"] == "geoscene_server"
        assert result["building_count"] == 6
        assert "fallback_errors" not in result

    def test_server_failure_falls_back_to_stdlib(self):
        def boom(url, fields=None, file_path=None, timeout=300):
            raise RuntimeError("GeoScene server unreachable")

        with patch.object(adapter, "HAS_GEOSCENE_SERVER", True), \
             patch.object(adapter, "HAS_ARCPY", False), \
             patch.object(adapter, "HAS_OPEN_SOURCE", False), \
             patch.object(adapter, "_geoscene_token", return_value="token-x"), \
             patch.object(adapter, "_geoscene_request", side_effect=boom):
            result = service.calculate_metrics({"aoi": AOI, "buildings": BUILDINGS})

        assert result["status"] == "Success"
        assert result["gis_backend"] == "standard_library_metrics"
        assert result["building_count"] == 6
        assert any("geoscene_server" in err for err in result["fallback_errors"])

    def test_server_issues_geometry_requests_against_geometry_service(self):
        urls = []

        def record(url, fields=None, file_path=None, timeout=300):
            urls.append(url)
            return _fake_server(url, fields, file_path, timeout)

        with patch.object(adapter, "HAS_GEOSCENE_SERVER", True), \
             patch.object(adapter, "_geoscene_token", return_value="token-x"), \
             patch.object(adapter, "_geoscene_request", side_effect=record):
            service._calculate_metrics_geoscene_server({"aoi": AOI, "buildings": BUILDINGS})

        assert urls, "server backend must call the Geometry Service"
        assert all("GeometryServer" in u for u in urls)
        assert any(u.endswith("areasAndLengths") for u in urls)
        assert any(u.endswith("intersect") for u in urls)