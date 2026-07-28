# -*- coding: utf-8 -*-
from copy import deepcopy

import pytest

import cityengine_bridge as bridge
from cityengine_bridge import _prepare_buildings, _write_shapefile, normalize_requirements


L_SHAPED_GEOMETRY = {
    "type": "Polygon",
    "coordinates": [[
        [121.4700, 31.2300],
        [121.4710, 31.2300],
        [121.4710, 31.2304],
        [121.4704, 31.2304],
        [121.4704, 31.2310],
        [121.4700, 31.2310],
        [121.4700, 31.2300],
    ]],
}


def _feature(building_id="L-01", geometry=None, **properties):
    return {
        "type": "Feature",
        "geometry": deepcopy(geometry or L_SHAPED_GEOMETRY),
        "properties": {
            "id": building_id,
            "name": f"建筑 {building_id}",
            "height": 60,
            "geometrySource": "arcgis_polygon_rings",
            "geometryApproximation": False,
            **properties,
        },
    }


def _prepare(features, *, setback=0, problem_ids=()):
    rules = {"rules": {"buildingHeight": {"max": 45}}}
    requirements = normalize_requirements({"setback": setback}, rules)
    problems = {
        "type": "FeatureCollection",
        "features": [_feature(building_id) for building_id in problem_ids],
    }
    return _prepare_buildings(
        {"type": "FeatureCollection", "features": features},
        rules,
        problems,
        requirements,
    )


def test_l_shaped_footprint_is_preserved_and_height_reason_is_reported():
    original = _feature()
    prepared, actions, summary = _prepare([original], problem_ids=("L-01",))

    assert prepared["features"][0]["geometry"] == original["geometry"]
    assert summary["preservedCount"] == 1
    assert summary["changedGeometryCount"] == 0
    assert summary["approximatedCount"] == 0
    assert summary["decisions"][0]["originalVertexCount"] == 6
    assert summary["decisions"][0]["decision"] == "preserve_footprint"
    assert "保留原始" in summary["decisions"][0]["reason"]
    assert actions[0]["action"] == "reduce_height"
    assert actions[0]["fromHeight"] == 60
    assert actions[0]["toHeight"] == 45
    assert "超过规划限高" in actions[0]["reason"]


def test_extent_approximation_is_exported_with_an_explicit_marker():
    approximate = _feature(
        "bbox-01",
        geometry={
            "type": "Polygon",
            "coordinates": [[[0, 0], [1, 0], [1, 1], [0, 1], [0, 0]]],
        },
        geometrySource="extent_bbox",
        geometryApproximation=True,
    )
    prepared, _actions, summary = _prepare([_feature(), approximate])

    assert len(prepared["features"]) == 2
    assert summary["skippedCount"] == 0
    assert summary["approximatedCount"] == 1
    approximated = next(item for item in summary["decisions"] if item["buildingId"] == "bbox-01")
    assert approximated["decision"] == "approximate_extent_rectangle"
    assert prepared["features"][1]["properties"]["geom_aprx"] == 1


def test_only_approximate_footprints_can_generate_slpk():
    approximate = _feature(
        geometry={
            "type": "Polygon",
            "coordinates": [[[0, 0], [1, 0], [1, 1], [0, 1], [0, 0]]],
        },
        geometrySource="extent",
        geometryApproximation=True,
    )
    prepared, _actions, summary = _prepare([approximate])
    assert len(prepared["features"]) == 1
    assert summary["approximatedCount"] == 1


def test_explicit_setback_is_the_only_reported_footprint_change():
    prepared, actions, summary = _prepare([_feature()], setback=3, problem_ids=("L-01",))

    assert prepared["features"][0]["properties"]["ce_setback"] == 3
    assert summary["preservedCount"] == 0
    assert summary["changedGeometryCount"] == 1
    assert summary["decisions"][0]["decision"] == "apply_explicit_setback"
    assert "退界 3 米" in summary["decisions"][0]["reason"]
    assert any(action["action"] == "apply_setback" for action in actions)


def test_cityengine_uses_metrics_vertical_profile_for_missing_input_height():
    feature = _feature("estimated-01", height=None)
    feature["properties"].pop("height")
    rules = {"rules": {"buildingHeight": {"max": 54}}}
    requirements = normalize_requirements({}, rules)
    prepared, _actions, summary = _prepare_buildings(
        {"type": "FeatureCollection", "features": [feature]},
        rules,
        {"type": "FeatureCollection", "features": []},
        requirements,
        [{
            "building_id": "estimated-01",
            "floors": 7,
            "floor_source": "estimated",
            "height_m": 22.4,
            "height_source": "building_type_estimated",
            "estimated": True,
        }],
    )

    assert prepared["features"][0]["properties"]["ce_height"] == 22.4
    assert prepared["features"][0]["properties"]["ce_floors"] == 7
    assert prepared["features"][0]["properties"]["vert_est"] == 1
    assert summary["decisions"][0]["heightSource"] == "building_type_estimated"


def test_shapefile_round_trip_keeps_l_shaped_geometry(tmp_path):
    geopandas = pytest.importorskip("geopandas")
    shapely_geometry = pytest.importorskip("shapely.geometry")
    prepared, _actions, _summary = _prepare([_feature()])
    path = tmp_path / "footprints.shp"

    _write_shapefile(prepared, path)

    written = geopandas.read_file(path).geometry.iloc[0]
    original = shapely_geometry.shape(L_SHAPED_GEOMETRY)
    # ESRI Shapefile may normalize ring direction; topology and vertices must remain unchanged.
    assert written.equals(original)
    assert len(list(written.exterior.coords)) - 1 == 6


def test_cold_boot_timeout_does_not_consume_automation_timeout(tmp_path, monkeypatch):
    job_id = "ce-test-cold-boot"
    run = tmp_path / job_id
    startup = run / "home" / bridge.JYTHON_STARTUP_RELATIVE
    startup.parent.mkdir(parents=True)
    (startup.parent / "cache.marker").write_text("ready", encoding="ascii")
    result = run / "result" / f"{job_id}.json"
    result.parent.mkdir(parents=True)
    log_path = run / "logs" / "cityengine.log"
    log_path.parent.mkdir(parents=True)
    runtime = {
        "startup": startup,
        "started": run / "result" / f"{job_id}.started",
        "result": result,
        "log": log_path,
        "metadataLog": run / "logs" / "cityengine-metadata.log",
        "workspace": run / "workspace",
        "home": run / "home",
        "workspaceMetadataLog": run / "workspace" / ".metadata" / ".log",
    }

    class Process:
        returncode = 0

        def poll(self):
            return None

        def wait(self):
            return 0

    clock = iter((0.0, 299.0, 301.0))
    monkeypatch.setattr(bridge.time, "monotonic", lambda: next(clock))
    monkeypatch.setattr(bridge.time, "sleep", lambda _seconds: result.write_text("{}", encoding="utf-8"))
    monkeypatch.setattr(bridge, "CITYENGINE_BOOT_TIMEOUT_SECONDS", 300)
    monkeypatch.setattr(bridge, "CITYENGINE_AUTOMATION_TIMEOUT_SECONDS", 120)
    monkeypatch.setattr(bridge, "JOB_TIMEOUT_SECONDS", 900)

    with log_path.open("ab") as log_stream:
        bridge._monitor_cityengine(job_id, Process(), runtime, log_stream, "# startup hook\n")

    assert result.is_file()
    assert "startup hook installed" in log_path.read_text(encoding="utf-8")
