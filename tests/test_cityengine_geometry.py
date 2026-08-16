# -*- coding: utf-8 -*-
import json
from copy import deepcopy

import pytest

import cityengine_bridge as bridge
from cityengine_bridge import (
    _prepare_buildings,
    _write_shapefile,
    normalize_requirements,
)
from gis import model


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


def test_result_manifest_retries_transient_windows_file_lock(tmp_path, monkeypatch):
    result_path = tmp_path / "ce-test.json"
    original_replace = bridge.os.replace
    attempts = {"count": 0}

    def locked_then_replace(source, destination):
        attempts["count"] += 1
        if attempts["count"] < 3:
            raise PermissionError("result manifest is temporarily locked")
        return original_replace(source, destination)

    monkeypatch.setattr(bridge.os, "replace", locked_then_replace)
    monkeypatch.setattr(bridge.time, "sleep", lambda _seconds: None)

    bridge._atomic_write_json(result_path, {"status": "completed"})

    assert attempts["count"] == 3
    assert json.loads(result_path.read_text(encoding="utf-8")) == {"status": "completed"}
    assert not list(tmp_path.glob("*.tmp"))


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


def test_cityengine_rejects_aoi_edge_sliver_instead_of_exporting_it():
    sliver = _feature(
        "sliver-01",
        geometry={
            "type": "Polygon",
            "coordinates": [[[116.4337355, 39.8866564], [116.4333479, 39.8866451], [116.4337350, 39.8866663], [116.4337355, 39.8866564]]],
        },
    )
    with pytest.raises(ValueError, match="没有可用于 CityEngine"):
        _prepare([sliver])


def test_cityengine_keeps_small_measured_scene_mesh_buildings():
    small_mesh = _feature(
        "mesh-small-01",
        geometry={
            "type": "Polygon",
            "coordinates": [[
                [116.393900, 39.916200], [116.393930, 39.916200],
                [116.393930, 39.916220], [116.393900, 39.916220],
                [116.393900, 39.916200],
            ]],
        },
        geometrySource="arcgis_scene_mesh_roof_projection",
        geometryApproximation=False,
    )

    prepared, _actions, summary = _prepare([small_mesh])

    assert len(prepared["features"]) == 1
    assert summary["skippedCount"] == 0


def test_missing_vertical_fields_still_generates_a_visible_one_story_building():
    feature = _feature("missing-vertical")
    feature["properties"].pop("height")
    feature["properties"].pop("floors", None)

    prepared, _actions, summary = _prepare([feature])
    properties = prepared["features"][0]["properties"]

    assert properties["ce_height"] == 3.0
    assert properties["ce_floors"] == 1
    assert properties["vert_src"] == "default_display_height"
    assert properties["vert_est"] == 1
    assert summary["estimatedHeightCount"] == 1


def test_single_mesh_roof_triangle_is_not_extruded_as_a_wedge():
    feature = _feature(
        "mesh-roof-triangle",
        geometry={
            "type": "Polygon",
            "coordinates": [[[116.38, 39.90], [116.381, 39.90], [116.38, 39.901], [116.38, 39.90]]],
        },
        geometrySource="arcgis_scene_mesh_roof_projection",
    )
    prepared, _actions, summary = _prepare([feature])
    output = prepared["features"][0]

    assert output["geometry"]["coordinates"][0] == [
        [116.38, 39.90], [116.381, 39.90], [116.381, 39.901],
        [116.38, 39.901], [116.38, 39.90],
    ]
    assert output["properties"]["geom_aprx"] == 1
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


def test_geometry_summary_reports_height_provenance():
    prepared, _actions, summary = _prepare([_feature("mesh-01", height=18.4)])
    assert len(prepared["features"]) == 1
    assert summary["trustedHeightCount"] == 1
    assert summary["estimatedHeightCount"] == 0
    assert summary["heightSourceCounts"]["input_height"] == 1


def test_cityengine_default_input_limit_covers_large_hand_drawn_aoi(monkeypatch):
    # Do not silently crop a legitimate city-scale redline such as the 994
    # buildings in the Forbidden City test AOI.
    monkeypatch.setattr(bridge, "CITYENGINE_MAX_INPUT_BUILDINGS", 1200)
    features = [_feature(f"building-{index}") for index in range(994)]
    prepared, _actions, summary = _prepare(features)
    assert len(prepared["features"]) == 994
    assert summary["inputCount"] == 994


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


def test_launch_cityengine_starts_process_before_queue_monitoring(tmp_path, monkeypatch):
    job_id = "ce-test-launch"
    executable = tmp_path / "CityEngine.exe"
    executable.write_text("", encoding="ascii")
    workspace = tmp_path / "workspace"
    workspace.mkdir()
    runtime = {
        "workspace": workspace,
        "home": tmp_path / "home",
        "project": tmp_path / "project",
        "log": tmp_path / "logs" / "cityengine.log",
        "result": tmp_path / "result" / f"{job_id}.json",
    }
    runtime["result"].parent.mkdir(parents=True)
    runtime["log"].parent.mkdir(parents=True)
    runtime["result"].write_text("{}", encoding="utf-8")
    calls = []

    class Process:
        pid = 12345

        def wait(self, timeout=None):
            return 0

    def fake_popen(command, **kwargs):
        calls.append((command, kwargs))
        return Process()

    monkeypatch.setattr(bridge, "CITYENGINE_EXE", executable)
    monkeypatch.setattr(bridge.subprocess, "Popen", fake_popen)
    with bridge._monitor_lock:
        bridge._monitor_threads.clear()
        bridge._running_processes.clear()

    result = bridge.launch_cityengine(job_id, runtime, tmp_path / "job.cfg")

    assert result["started"] is True
    assert calls and calls[0][0][0] == str(executable)
    assert calls[0][1]["cwd"] == str(workspace)


def test_service_restart_closes_unstarted_cityengine_job(tmp_path, monkeypatch):
    jobs_dir = tmp_path / "jobs"
    results_dir = tmp_path / "results"
    jobs_dir.mkdir()
    job_id = "ce-test-abandoned"
    started_marker = tmp_path / "runtime" / f"{job_id}.started"
    job_path = jobs_dir / f"{job_id}.json"
    job_path.write_text(
        json.dumps({
            "jobId": job_id,
            "status": "starting",
            "runtimeStartedMarker": str(started_marker),
            "runtimeResult": str(tmp_path / "runtime" / f"{job_id}.json"),
        }),
        encoding="utf-8",
    )
    monkeypatch.setattr(bridge, "JOBS_DIR", jobs_dir)
    monkeypatch.setattr(bridge, "RESULTS_DIR", results_dir)

    bridge._recover_unstarted_jobs()

    recovered = json.loads(job_path.read_text(encoding="utf-8"))
    assert recovered["status"] == "failed"
    assert "restarted" in recovered["message"]
    assert (results_dir / f"{job_id}.json").is_file()


def test_validate_footprint_geometry_repairs_self_intersection():
    # Bowtie: outer ring crosses itself -> shapely invalid, buffer(0) repairs it.
    bowtie = {
        "type": "Polygon",
        "coordinates": [[[0, 0], [2, 2], [2, 0], [0, 2], [0, 0]]],
    }
    valid, reason, repaired = bridge._validate_footprint_geometry(bowtie)
    assert valid, reason
    assert repaired is not None
    from shapely.geometry import shape
    assert shape(repaired).is_valid


def test_validate_footprint_geometry_rejects_unrepairable():
    # Collapsed ring -> buffer(0) yields an empty geometry.
    collapsed = {
        "type": "Polygon",
        "coordinates": [[[1, 1], [1, 1], [1, 1], [1, 1], [1, 1]]],
    }
    valid, reason, repaired = bridge._validate_footprint_geometry(collapsed)
    assert not valid
    assert repaired is None
    assert "无法自动修复" in reason or "为空" in reason


def test_prepare_buildings_repairs_self_intersection_and_skips_collapsed():
    bowtie = {
        "type": "Feature",
        "geometry": {
            "type": "Polygon",
            "coordinates": [[[0, 0], [2, 2], [2, 0], [0, 2], [0, 0]]],
        },
        "properties": {"id": "repairable-1"},
    }
    collapsed = {
        "type": "Feature",
        "geometry": {
            "type": "Polygon",
            "coordinates": [[[1, 1], [1, 1], [1, 1], [1, 1], [1, 1]]],
        },
        "properties": {"id": "collapsed-1"},
    }
    prepared, _actions, summary = _prepare([bowtie, collapsed])
    ids = {f["properties"]["id"] for f in prepared["features"]}
    assert "repairable-1" in ids
    assert "collapsed-1" not in ids
    assert any(d["buildingId"] == "collapsed-1" and d["decision"] == "skip_invalid_footprint"
               for d in summary["decisions"])


def test_planned_metrics_uses_real_geometry_and_planning_limits():
    # 三栋 60m 高层建筑，规划限高 54m -> 规划后最高建筑应为 54m，
    # 基底面积来自真实几何（footprint_sqm），容积率按规划层数重算。
    features = [
        _feature("H-1", height=60, floors=20),
        _feature("H-2", height=60, floors=20),
        _feature("H-3", height=60, floors=20),
    ]
    rule_set = {"ruleSetId": "test", "effective": True,
                "source": "test", "rules": {"buildingHeight": {"max": 54.0, "unit": "m"}}}
    normalized = normalize_requirements(
        {"maxBuildingHeight": 54, "floorHeight": 3.0, "lotCoverage": 0.75}, rule_set)
    prepared, _actions, _summary = _prepare_buildings(
        {"type": "FeatureCollection", "features": features},
        rule_set, {"type": "FeatureCollection", "features": []}, normalized,
    )
    # 限高 54m 触发高度调整（问题建筑集合为空 -> 不调整，直接按输入 60m）
    planned = bridge._planned_metrics(
        prepared, {"site_area": 50000.0, "building_count": 3}, normalized)

    assert planned["building_count"] == 3
    assert planned["site_area"] == 50000.0
    assert planned["footprint_area_sqm"] > 0
    assert planned["far"] > 0
    assert planned["building_density"] > 0
    assert 0 < planned["green_rate"] <= 100
    # 规划限高对全部建筑生效：高度压到 54m，层数按 54/3=18 层
    assert planned["buildingHeight"] == 54.0


def test_planned_metrics_caps_height_for_problem_buildings():
    # 标记为问题建筑 -> 高度压到限高 54m，规划后最高建筑为 54m
    features = [
        _feature("P-1", height=60, floors=20),
        _feature("P-2", height=60, floors=20),
    ]
    rule_set = {"ruleSetId": "test", "effective": True,
                "source": "test", "rules": {"buildingHeight": {"max": 54.0, "unit": "m"}}}
    normalized = normalize_requirements(
        {"maxBuildingHeight": 54, "floorHeight": 3.0, "lotCoverage": 0.75}, rule_set)
    problem = {"type": "FeatureCollection", "features": [
        _feature("P-1"), _feature("P-2")]}
    prepared, _actions, _summary = _prepare_buildings(
        {"type": "FeatureCollection", "features": features},
        rule_set, problem, normalized,
    )
    planned = bridge._planned_metrics(
        prepared, {"site_area": 50000.0, "building_count": 2}, normalized)
    assert planned["buildingHeight"] == 54.0


def test_normalize_requirements_reads_green_space_params():
    rule_set = {"ruleSetId": "t", "effective": True, "source": "t", "rules": {}}
    normalized = normalize_requirements(
        {"adjustGreenSpace": True, "greenRate": 35.0}, rule_set)
    assert normalized["adjustGreenSpace"] is True
    assert normalized["greenRate"] == 35.0
    # 默认保持历史行为：不调整绿地
    defaulted = normalize_requirements({}, rule_set)
    assert defaulted["adjustGreenSpace"] is False


def test_generate_cga_excludes_green_space_rule():
    # 绿地/道路混入建模会破坏 SLPK 导出，CGA 只应包含建筑规则
    rule_set = {"ruleSetId": "t", "effective": True, "source": "t", "rules": {}}
    normalized = normalize_requirements({}, rule_set)
    cga = bridge._generate_cga(normalized)
    assert 'ce_kind == "green"' not in cga
    assert "GreenSpace" not in cga
    assert "extrude(ce_height)" in cga

