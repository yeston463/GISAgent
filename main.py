# -*- coding: utf-8 -*-
"""Backward-compatible entrypoint for the GIS engine.

The implementation now lives in the :mod:`gis` package (model / adapter /
service / router). This module re-exports the public symbols previously defined
here so that ``import main`` (the test suite and uvicorn's ``main:app``) keeps
working unchanged after the refactor.
"""
from gis.logging_setup import configure_logging

configure_logging()

from gis.router import app, _cityengine_pipeline_terminal
from gis.adapter import ensure_cityengine_published, runtime_status
from gis.service import calculate_skyline, calculate_sunlight, create_buffer_feature
from gis.model import (
    _metric_crs,
    _epsg_number,
    _normalize_geometry,
    _features_from_source,
    _ring_area,
    _overpass_query,
    _elements_to_features,
    _footprint_quality,
    _filter_usable_building_footprints,
    _create_buffer_feature_approx,
    _parse_number,
    _safe_float,
    _is_missing_value,
    _json_safe_dict,
    _estimate_missing_floors,
    _floor_for_record,
    _evaluate_rule,
    _build_metrics_result,
    _distance_and_bearing,
    _solar_position,
    _convex_hull,
    _feature_centroid,
    _feature_height,
    _shadow_feature,
)

if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="127.0.0.1", port=8000)
