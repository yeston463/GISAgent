import json
import os
import math
import shutil
import stat
import subprocess
import threading
import time
import uuid
from configparser import ConfigParser
from datetime import datetime, timezone
from pathlib import Path

CITYENGINE_EXE = Path(os.environ.get("CITYENGINE_EXE", r"C:\Program Files\ArcGIS\CityEngine2025.1\CityEngine.exe"))
ROOT = Path(__file__).resolve().parent
WORKSPACE = Path(os.environ.get("CITYENGINE_WORKSPACE", ROOT / "cityengine-workspace"))
PROJECT = Path(os.environ.get("CITYENGINE_PROJECT", ROOT / "cityengine-project"))
JOBS_DIR = WORKSPACE / "automation" / "jobs"
RESULTS_DIR = WORKSPACE / "automation" / "results"
RUNTIME_ROOT = Path(os.environ.get("CITYENGINE_RUNTIME_ROOT", r"C:\GISAgentCityEngine"))
RUNS_DIR = RUNTIME_ROOT / "runs"
JYTHON_STARTUP_RELATIVE = Path(".CityEngine") / "2025.1R.win32.win32.x86_64" / "jythonCache3" / "startup.py"
ALLOWED_EXPORTS = {"slpk", "obj", "fbx", "gltf"}
ALLOWED_STYLES = {"modern", "residential", "commercial", "industrial", "institutional", "mixed_use"}
TERMINAL_STATUSES = {"completed", "failed", "error"}
_monitor_lock = threading.Lock()
_monitor_threads = {}


def _is_ascii_path(path):
    try:
        str(path).encode("ascii")
        return True
    except UnicodeEncodeError:
        return False


def _positive_int_env(name, default, minimum):
    try:
        return max(minimum, int(os.environ.get(name, default)))
    except (TypeError, ValueError):
        return default


LEGACY_STARTUP_TIMEOUT_SECONDS = _positive_int_env("CITYENGINE_STARTUP_TIMEOUT_SECONDS", 120, 10)
CITYENGINE_BOOT_TIMEOUT_SECONDS = _positive_int_env(
    "CITYENGINE_BOOT_TIMEOUT_SECONDS",
    max(300, LEGACY_STARTUP_TIMEOUT_SECONDS),
    30,
)
CITYENGINE_AUTOMATION_TIMEOUT_SECONDS = _positive_int_env(
    "CITYENGINE_AUTOMATION_TIMEOUT_SECONDS",
    LEGACY_STARTUP_TIMEOUT_SECONDS,
    30,
)
JOB_TIMEOUT_SECONDS = max(
    CITYENGINE_BOOT_TIMEOUT_SECONDS + CITYENGINE_AUTOMATION_TIMEOUT_SECONDS,
    _positive_int_env("CITYENGINE_JOB_TIMEOUT_SECONDS", 900, 60),
)
KEEP_RUNTIME_CACHE = os.environ.get("CITYENGINE_KEEP_RUNTIME_CACHE", "false").lower() in {
    "1", "true", "yes",
}


def runtime_status():
    startup_template = RUNS_DIR / "<jobId>" / "home" / JYTHON_STARTUP_RELATIVE
    return {
        "available": CITYENGINE_EXE.is_file(),
        "executable": str(CITYENGINE_EXE),
        "workspace": str(WORKSPACE),
        "project": str(PROJECT),
        "runtimeRoot": str(RUNTIME_ROOT),
        "runsDirectory": str(RUNS_DIR),
        "runtimeRootAscii": _is_ascii_path(RUNTIME_ROOT),
        "startupTimeoutSeconds": CITYENGINE_AUTOMATION_TIMEOUT_SECONDS,
        "bootTimeoutSeconds": CITYENGINE_BOOT_TIMEOUT_SECONDS,
        "automationTimeoutSeconds": CITYENGINE_AUTOMATION_TIMEOUT_SECONDS,
        "jobTimeoutSeconds": JOB_TIMEOUT_SECONDS,
        "keepRuntimeCache": KEEP_RUNTIME_CACHE,
        "startupScriptTemplate": str(startup_template),
        "automationConfigured": CITYENGINE_EXE.is_file() and _is_ascii_path(RUNTIME_ROOT),
    }


def _number(value, default, minimum, maximum):
    try:
        return max(minimum, min(maximum, float(value)))
    except (TypeError, ValueError):
        return default


def normalize_requirements(requirements, rule_set):
    requirements = requirements if isinstance(requirements, dict) else {}
    rule_height = rule_set.get("rules", {}).get("buildingHeight", {}).get("max", 54)
    exports = [str(item).lower() for item in requirements.get("exportFormats", ["slpk", "obj"])]
    exports = [item for item in exports if item in ALLOWED_EXPORTS] or ["slpk", "obj"]
    style = str(requirements.get("facadeStyle", "modern")).lower()
    return {
        "maxBuildingHeight": _number(requirements.get("maxBuildingHeight"), float(rule_height), 3, 300),
        "floorHeight": _number(requirements.get("floorHeight"), 3.0, 2.4, 6.0),
        "setback": _number(requirements.get("setback"), 0.0, 0.0, 50.0),
        "lotCoverage": _number(requirements.get("lotCoverage"), 0.75, 0.1, 1.0),
        "facadeStyle": style if style in ALLOWED_STYLES else "modern",
        "roofType": str(requirements.get("roofType", "flat"))[:40],
        "primaryColor": str(requirements.get("primaryColor", "#65d6c4"))[:20],
        "exportFormats": list(dict.fromkeys(exports)),
        "adjustGreenSpace": False,
        "generateModels": True,
        "designSummary": str(requirements.get("designSummary", ""))[:1000],
    }


def _atomic_write_json(path, payload):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary_path = path.with_name(f"{path.name}.{os.getpid()}.{threading.get_ident()}.tmp")
    temporary_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    temporary_path.replace(path)


def _read_json(path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError):
        return None


def _prepare_runtime(job_id):
    if not all(character.isascii() and (character.isalnum() or character in "-_") for character in job_id):
        raise ValueError("CityEngine job id contains unsupported characters")
    if not _is_ascii_path(RUNTIME_ROOT):
        raise ValueError("CITYENGINE_RUNTIME_ROOT must contain ASCII characters only")

    run_dir = RUNS_DIR / job_id
    runtime = {
        "run": run_dir,
        "workspace": run_dir / "workspace",
        "home": run_dir / "home",
        "project": run_dir / "project",
        "result": run_dir / "result" / f"{job_id}.json",
        "started": run_dir / "result" / f"{job_id}.started",
        "log": run_dir / "logs" / "cityengine.log",
        "metadataLog": run_dir / "logs" / "cityengine-metadata.log",
    }
    runtime.update({
        "startup": runtime["home"] / JYTHON_STARTUP_RELATIVE,
        "workspaceMetadataLog": runtime["workspace"] / ".metadata" / ".log",
        "scripts": runtime["project"] / "scripts" / "generated",
        "data": runtime["project"] / "data" / "generated",
        "rules": runtime["project"] / "rules" / "generated",
        "models": runtime["project"] / "models" / "generated",
        "config": runtime["project"] / "config" / "generated",
        "scenes": runtime["project"] / "scenes",
    })
    for path in (
        WORKSPACE, JOBS_DIR, RESULTS_DIR, runtime["workspace"], runtime["home"],
        runtime["result"].parent, runtime["log"].parent, runtime["startup"].parent,
        runtime["scripts"], runtime["data"], runtime["rules"], runtime["models"],
        runtime["config"], runtime["scenes"],
    ):
        path.mkdir(parents=True, exist_ok=True)

    if runtime["startup"].is_file():
        runtime["startup"].unlink()

    project_file = runtime["project"] / ".project"
    project_file.write_text('''<?xml version="1.0" encoding="UTF-8"?>\n<projectDescription>\n  <name>GISAgentCityEngineAutomation</name>\n  <comment></comment>\n  <projects></projects>\n  <buildSpec></buildSpec>\n  <natures></natures>\n</projectDescription>\n''', encoding="ascii")
    return runtime


def _runtime_metadata(runtime):
    return {
        "runtimeRun": str(runtime["run"]),
        "runtimeWorkspace": str(runtime["workspace"]),
        "runtimeHome": str(runtime["home"]),
        "runtimeProject": str(runtime["project"]),
        "runtimeResult": str(runtime["result"]),
        "runtimeStartedMarker": str(runtime["started"]),
        "runtimeLog": str(runtime["log"]),
        "runtimeMetadataLog": str(runtime["metadataLog"]),
        "runtimeOutputRoot": str(runtime["models"]),
    }


def repository_result_path(job_id):
    if not all(character.isascii() and (character.isalnum() or character in "-_") for character in job_id):
        raise ValueError("CityEngine job id contains unsupported characters")
    return RESULTS_DIR / f"{job_id}.json"


def write_job_result(job_id, result):
    _atomic_write_json(repository_result_path(job_id), result)


def _startup_script():
    return '''from scripting import *\nfrom java import lang\nimport ConfigParser\nimport sys\n\nif __name__ == '__startup__':\n    ce = CE()\n    projectFolder = lang.System.getProperty("projectFolder")\n    configFilePath = lang.System.getProperty("configFilePath")\n    if "automationProject" in ce.listProjects():\n        ce.removeProject("automationProject")\n    ce.importProject(projectFolder, False, "automationProject")\n    cp = ConfigParser.ConfigParser()\n    cp.read(configFilePath)\n    scriptPath = cp.get("config", "scriptPath")\n    sys.path.append(ce.toFSPath("/automationProject/scripts/generated"))\n    execfile(scriptPath)\n'''


def _is_finite_coordinate(point):
    return (
        isinstance(point, (list, tuple))
        and len(point) >= 2
        and all(
            isinstance(value, (int, float))
            and not isinstance(value, bool)
            and math.isfinite(value)
            for value in point[:2]
        )
    )


def _valid_ring(ring):
    return (
        isinstance(ring, list)
        and len(ring) >= 4
        and all(_is_finite_coordinate(point) for point in ring)
        and ring[0][:2] == ring[-1][:2]
    )


def _validate_footprint_geometry(geometry):
    if not isinstance(geometry, dict):
        return False, "缺少 GeoJSON 面几何"
    geometry_type = geometry.get("type")
    coordinates = geometry.get("coordinates")
    if geometry_type == "Polygon":
        valid = isinstance(coordinates, list) and bool(coordinates) and all(_valid_ring(ring) for ring in coordinates)
    elif geometry_type == "MultiPolygon":
        valid = (
            isinstance(coordinates, list)
            and bool(coordinates)
            and all(
                isinstance(polygon, list)
                and bool(polygon)
                and all(_valid_ring(ring) for ring in polygon)
                for polygon in coordinates
            )
        )
    else:
        return False, f"不支持 {geometry_type or 'unknown'} 几何，仅接受 Polygon/MultiPolygon"
    if not valid:
        return False, "面坐标为空、无效或 rings 未闭合"
    return True, ""


def _footprint_vertex_count(geometry):
    coordinates = geometry["coordinates"]
    polygons = [coordinates] if geometry["type"] == "Polygon" else coordinates
    return sum(max(0, len(ring) - 1) for polygon in polygons for ring in polygon)


def _is_approximate_geometry(properties):
    value = properties.get("geometryApproximation", False)
    if isinstance(value, str):
        value = value.strip().lower() in {"1", "true", "yes", "extent", "bounding_box"}
    source = str(properties.get("geometrySource", "")).strip().lower()
    return bool(value) or source in {"extent", "extent_bbox", "bounding_box", "envelope"}


def _prepare_buildings(buildings, rule_set, problem_buildings, requirements):
    problem_ids = {
        str((feature.get("properties") or {}).get("id"))
        for feature in (problem_buildings or {}).get("features", [])
    }
    rule_height = _number(rule_set.get("rules", {}).get("buildingHeight", {}).get("max"), 54.0, 3, 300)
    prepared = {"type": "FeatureCollection", "features": []}
    actions = []
    decisions = []
    input_features = buildings.get("features", []) if isinstance(buildings, dict) else []
    for index, feature in enumerate(input_features):
        props = dict(feature.get("properties") or {})
        building_id = str(props.get("id", f"feature-{index + 1}"))
        geometry = feature.get("geometry")
        source = str(props.get("geometrySource") or "geojson_polygon")
        if _is_approximate_geometry(props):
            decisions.append({
                "buildingId": building_id,
                "name": props.get("name"),
                "decision": "skip_approximate_footprint",
                "geometrySource": source,
                "geometryChanged": False,
                "reason": "输入仅为 extent/包围盒近似，已跳过，避免把原建筑静默变成四棱柱。",
            })
            continue
        valid, invalid_reason = _validate_footprint_geometry(geometry)
        if not valid:
            decisions.append({
                "buildingId": building_id,
                "name": props.get("name"),
                "decision": "skip_invalid_footprint",
                "geometrySource": source,
                "geometryChanged": False,
                "reason": f"{invalid_reason}；已跳过且未生成替代矩形。",
            })
            continue

        vertex_count = _footprint_vertex_count(geometry)
        current_height = _number(props.get("height"), 3.0, 3, 300)
        should_modify = building_id in problem_ids
        target_height = min(current_height, rule_height) if should_modify else current_height
        target_setback = requirements["setback"] if should_modify else 0.0
        geometry_changed = target_setback > 0
        source_reason = str(props.get("geometryChangeReason") or "").strip()[:500]
        geometry_reason = (
            f"用户/规划要求对问题建筑退界 {target_setback:g} 米；原始轮廓仍作为输入基准。"
            if geometry_changed
            else source_reason or "保留原始 Polygon/MultiPolygon 轮廓，CityEngine 仅沿该轮廓拉伸高度。"
        )
        for metadata_field in (
            "geometrySource", "geometryApproximation", "originalVertexCount", "geometryChangeReason"
        ):
            props.pop(metadata_field, None)
        props["ce_height"] = target_height
        props["ce_modify"] = 1 if target_height != current_height else 0
        props["ce_setback"] = target_setback
        props["ce_color"] = requirements["primaryColor"] if should_modify else "#b7c1c8"
        props["geom_src"] = source[:40]
        props["geom_aprx"] = 0
        props["orig_vtx"] = vertex_count
        props["geom_note"] = geometry_reason[:240]
        if target_height != current_height:
            actions.append({
                "buildingId": building_id,
                "name": props.get("name"),
                "action": "reduce_height",
                "fromHeight": current_height,
                "toHeight": target_height,
                "reason": f"建筑现状高度 {current_height:g} 米超过规划限高 {rule_height:g} 米。",
            })
        if geometry_changed:
            actions.append({
                "buildingId": building_id,
                "name": props.get("name"),
                "action": "apply_setback",
                "setback": target_setback,
                "reason": geometry_reason,
            })
        decisions.append({
            "buildingId": building_id,
            "name": props.get("name"),
            "decision": "apply_explicit_setback" if geometry_changed else "preserve_footprint",
            "geometrySource": source,
            "originalVertexCount": vertex_count,
            "geometryChanged": geometry_changed,
            "reason": geometry_reason,
        })
        prepared["features"].append({"type": "Feature", "properties": props, "geometry": geometry})

    if not prepared["features"]:
        reasons = "; ".join(item["reason"] for item in decisions[:3])
        raise ValueError(f"没有可用于 CityEngine 的真实建筑面轮廓。{reasons}")

    skipped = [item for item in decisions if item["decision"].startswith("skip_")]
    changed = [item for item in decisions if item["geometryChanged"]]
    summary = {
        "policy": "preserve_original_footprint",
        "inputCount": len(input_features),
        "preservedCount": len(prepared["features"]) - len(changed),
        "changedGeometryCount": len(changed),
        "approximatedCount": 0,
        "skippedCount": len(skipped),
        "message": "默认保持原始建筑轮廓；仅在明确要求退界时改变生成轮廓，绝不使用 extent 静默替代。",
        "decisions": decisions,
    }
    return prepared, actions, summary


def _write_shapefile(buildings, shp_path):
    import geopandas as gpd
    from shapely.geometry import shape
    rows = []
    for feature in buildings.get("features", []):
        props = dict(feature.get("properties") or {})
        geometry = shape(feature["geometry"])
        if geometry.geom_type not in {"Polygon", "MultiPolygon"} or geometry.is_empty or not geometry.is_valid:
            building_id = props.get("id", "unknown")
            raise ValueError(f"建筑 {building_id} 的原始轮廓无法无损写入 Shapefile，已停止生成")
        props["geometry"] = geometry
        rows.append(props)
    gdf = gpd.GeoDataFrame(rows, geometry="geometry", crs="EPSG:4326")
    gdf.to_file(shp_path, driver="ESRI Shapefile", encoding="UTF-8")


def _generate_cga(requirements):
    return f'''version "2025.0"\n\nattr ce_height = 12\nattr ce_modify = 0\nattr ce_setback = 0\nattr ce_color = "#b7c1c8"\n\n@StartRule\nLot --> case ce_setback > 0: setback(ce_setback) {{ all: Buildable }} else: Buildable\nBuildable --> extrude(ce_height) Building\nBuilding --> comp(f) {{ side: Facade | top: Roof }}\nFacade --> color(ce_color) split(y) {{ ~{requirements["floorHeight"]}: Floor }}*\nFloor --> color(ce_color)\nRoof --> color("#d9e2e8")\n'''


def _generated_script(job_id, layer_name, shp_workspace_path, rule_workspace_path, result_path, started_path, requirements):
    exports = requirements["exportFormats"]
    return f'''from scripting import *\nimport json\nimport os\nimport traceback\n\nce = CE()\nJOB_ID = {job_id!r}\nRESULT_PATH = {str(result_path)!r}\nSTARTED_PATH = {str(started_path)!r}\nEXPORTS = {exports!r}\n\ndef write_result(status, outputs, message):\n    result = {{"jobId": JOB_ID, "status": status, "outputs": outputs, "message": message}}\n    f = open(RESULT_PATH, "w")\n    f.write(json.dumps(result, ensure_ascii=False, indent=2))\n    f.close()\n\ndef run():\n    outputs = {{}}\n    try:\n        started = open(STARTED_PATH, "w")\n        started.write(JOB_ID)\n        started.close()\n        scene_path = "/automationProject/scenes/{job_id}.cej"\n        ce.newFile(scene_path)\n        settings = SHPImportSettings()\n        ce.importFile(ce.toFSPath({shp_workspace_path!r}), settings)\n        layers = ce.getObjectsFrom(ce.scene, ce.isShapeLayer, ce.withName({layer_name!r}))\n        if not layers:\n            layers = ce.getObjectsFrom(ce.scene, ce.isShapeLayer)\n        if not layers:\n            raise RuntimeError("CityEngine did not create a shape layer from the footprint shapefile")\n        shapes = ce.getObjectsFrom(layers[0], ce.isShape)\n        if not shapes:\n            raise RuntimeError("No footprint shapes were imported")\n        ce.setRuleFile(shapes, {rule_workspace_path!r})\n        ce.setStartRule(shapes, "Lot")\n        ce.generateModels(shapes)\n        ce.waitForUIIdle()\n        output_dir = ce.toFSPath("/automationProject/models/generated/{job_id}")\n        if not os.path.isdir(output_dir):\n            os.makedirs(output_dir)\n        if "obj" in EXPORTS:\n            obj = OBJExportModelSettings()\n            obj.setOutputPath(output_dir)\n            obj.setBaseName("{job_id}")\n            obj.setFileGranularity(OBJExportModelSettings.START_SHAPE)\n            obj.setExistingFiles(OBJExportModelSettings.OVERWRITE)\n            obj.setTerrainLayers(OBJExportModelSettings.TERRAIN_NONE)\n            ce.export(shapes, obj)\n            outputs["obj"] = output_dir\n        if "slpk" in EXPORTS:\n            slpk = SPKMeshExportModelSettings()\n            slpk.setOutputPath(output_dir)\n            slpk.setBaseName("{job_id}")\n            slpk.setSceneType("Local")\n            slpk.setExistingFiles(SPKMeshExportModelSettings.OVERWRITE)\n            slpk.setFileSize(SPKMeshExportModelSettings.MIDSIZE_FILE)\n            ce.export(shapes, slpk)\n            outputs["slpk"] = os.path.join(output_dir, "{job_id}.slpk")\n        if "fbx" in EXPORTS:\n            fbx = FBXExportModelSettings()\n            fbx.setOutputPath(output_dir)\n            fbx.setBaseName("{job_id}")\n            ce.export(shapes, fbx)\n            outputs["fbx"] = output_dir\n        if "gltf" in EXPORTS:\n            gltf = GLTFExportModelSettings()\n            gltf.setOutputPath(output_dir)\n            gltf.setBaseName("{job_id}")\n            ce.export(shapes, gltf)\n            outputs["gltf"] = output_dir\n        ce.saveFile(scene_path)\n        write_result("completed", outputs, "CityEngine generation and export completed")\n    except Exception as exc:\n        write_result("failed", outputs, str(exc) + "\\n" + traceback.format_exc())\n    finally:\n        try:\n            ce.waitForUIIdle()\n            ce.closeFile()\n        except:\n            pass\n        ce.exit()\n\nrun()\n'''


def submit_planning_job(case_data, rule_set, current_metrics, problem_buildings, requirements=None, rag_context="", user_request=""):
    job_id = f"ce-{datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S')}-{uuid.uuid4().hex[:8]}"
    normalized = normalize_requirements(requirements, rule_set)
    prepared_buildings, optimization_actions, geometry_summary = _prepare_buildings(
        case_data["buildings"], rule_set, problem_buildings, normalized
    )
    runtime = _prepare_runtime(job_id)
    job_path = JOBS_DIR / f"{job_id}.json"
    result_path = repository_result_path(job_id)
    cga_path = runtime["rules"] / f"{job_id}.cga"
    script_path = runtime["scripts"] / f"{job_id}.py"
    config_path = runtime["config"] / f"{job_id}.cfg"
    startup_source_path = runtime["config"] / f"{job_id}-startup.py"
    shp_path = runtime["data"] / f"{job_id}.shp"
    _write_shapefile(prepared_buildings, shp_path)
    cga_path.write_text(_generate_cga(normalized), encoding="utf-8")
    script_path.write_text(_generated_script(job_id, job_id, f"/automationProject/data/generated/{job_id}.shp", f"/automationProject/rules/generated/{job_id}.cga", runtime["result"], runtime["started"], normalized), encoding="utf-8")
    cfg = ConfigParser()
    cfg["config"] = {"scriptPath": str(script_path)}
    with config_path.open("w", encoding="utf-8") as stream:
        cfg.write(stream)
    startup_source_path.write_text(_startup_script(), encoding="ascii")
    job = {
        "jobId": job_id, "status": "queued", "engine": "ArcGIS CityEngine 2025.1",
        "createdAt": datetime.now(timezone.utc).isoformat(), "requirements": normalized,
        "requirementsSource": {"userRequest": user_request, "ragContext": rag_context},
        "case": case_data, "currentMetrics": current_metrics, "problemBuildings": problem_buildings,
        "optimizationActions": optimization_actions,
        "geometrySummary": geometry_summary,
        "footprints": str(shp_path), "generatedScript": str(script_path), "generatedRule": str(cga_path),
        "configFile": str(config_path), "startupSource": str(startup_source_path),
        "resultManifest": str(result_path),
        **_runtime_metadata(runtime),
    }
    _atomic_write_json(job_path, job)
    launch = launch_cityengine(job_id, runtime, config_path)
    job["launch"] = launch
    if launch.get("started"):
        job["status"] = "running"
        job["startedAt"] = datetime.now(timezone.utc).isoformat()
    else:
        job["status"] = "failed"
        job["message"] = launch.get("reason", "CityEngine failed to start")
        job["finishedAt"] = datetime.now(timezone.utc).isoformat()
    _atomic_write_json(job_path, job)
    if job["status"] == "failed":
        _atomic_write_json(result_path, job)
    return {
        "jobId": job_id, "status": job["status"], "jobFile": str(job_path), "footprints": str(shp_path),
        "generatedScript": str(script_path), "generatedRule": str(cga_path), "configFile": str(config_path),
        "startupSource": str(startup_source_path), "resultManifest": str(result_path),
        "requirements": normalized, "cityEngine": runtime_status(), "launch": launch,
        "optimizationActions": optimization_actions, "geometrySummary": geometry_summary,
        **_runtime_metadata(runtime),
    }


def _tail_log(path, maximum=12000):
    try:
        with path.open("rb") as stream:
            stream.seek(0, os.SEEK_END)
            size = stream.tell()
            stream.seek(max(0, size - maximum))
            return stream.read().decode("utf-8", errors="replace").strip()
    except OSError:
        return ""


def _failure_message(return_code, runtime):
    detail = _tail_log(runtime["log"])
    metadata_detail = _tail_log(runtime["workspaceMetadataLog"]) or _tail_log(runtime["metadataLog"])
    if metadata_detail and metadata_detail not in detail:
        detail = f"{detail}\n\nCityEngine metadata log:\n{metadata_detail}".strip()
    message = f"CityEngine exited with code {return_code} before producing a result"
    return f"{message}\n{detail}".strip()


def _write_process_failure(job_id, process, runtime, reason=None):
    result_path = repository_result_path(job_id)
    if runtime["result"].is_file() or result_path.is_file():
        return
    job = _read_json(JOBS_DIR / f"{job_id}.json") or {"jobId": job_id}
    if str(job.get("status", "")).lower() in TERMINAL_STATUSES:
        return
    job["status"] = "failed"
    job["message"] = reason or _failure_message(process.returncode, runtime)
    job["exitCode"] = process.returncode
    job["finishedAt"] = datetime.now(timezone.utc).isoformat()
    _atomic_write_json(result_path, job)


def _stop_process(process):
    if process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=10)


def _cleanup_runtime_cache(runtime):
    if KEEP_RUNTIME_CACHE:
        return
    workspace_metadata = runtime.get("workspaceMetadataLog")
    archived_metadata = runtime.get("metadataLog")
    if workspace_metadata and archived_metadata and workspace_metadata.is_file():
        try:
            archived_metadata.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(workspace_metadata, archived_metadata)
        except OSError:
            pass

    def remove_readonly(function, path, _error):
        os.chmod(path, stat.S_IWRITE | stat.S_IREAD)
        function(path)

    for key in ("home", "workspace"):
        path = runtime[key]
        try:
            if path.is_dir():
                shutil.rmtree(path, onerror=remove_readonly)
        except OSError:
            pass


def cleanup_finished_runtime(job_id):
    job = _read_json(JOBS_DIR / f"{job_id}.json") or {}
    if not job.get("runtimeRun"):
        return False
    run_dir = Path(job["runtimeRun"]).resolve()
    runs_root = RUNS_DIR.resolve()
    if runs_root not in run_dir.parents or run_dir.name != job_id:
        raise ValueError("CityEngine runtime path is outside the configured runs directory")
    runtime = {
        "home": run_dir / "home",
        "workspace": run_dir / "workspace",
        "workspaceMetadataLog": run_dir / "workspace" / ".metadata" / ".log",
        "metadataLog": run_dir / "logs" / "cityengine-metadata.log",
    }
    _cleanup_runtime_cache(runtime)
    metadata_path = str(runtime["metadataLog"])
    job["runtimeMetadataLog"] = metadata_path
    _atomic_write_json(JOBS_DIR / f"{job_id}.json", job)
    result_path = repository_result_path(job_id)
    result = _read_json(result_path)
    if result:
        result["runtimeMetadataLog"] = metadata_path
        _atomic_write_json(result_path, result)
    return True


def _monitor_cityengine(job_id, process, runtime, log_stream, startup_script):
    try:
        boot_deadline = time.monotonic() + CITYENGINE_BOOT_TIMEOUT_SECONDS
        automation_deadline = None
        job_deadline = time.monotonic() + JOB_TIMEOUT_SECONDS
        startup_installed = False
        while process.poll() is None and not runtime["result"].is_file():
            now = time.monotonic()
            if (
                not startup_installed
                and runtime["startup"].parent.is_dir()
                and any(runtime["startup"].parent.iterdir())
            ):
                runtime["startup"].write_text(startup_script, encoding="ascii")
                startup_installed = True
                automation_deadline = now + CITYENGINE_AUTOMATION_TIMEOUT_SECONDS
                log_stream.write(
                    (
                        "[GISAgent] CityEngine startup hook installed; "
                        f"automation timeout is {CITYENGINE_AUTOMATION_TIMEOUT_SECONDS} seconds.\n"
                    ).encode("utf-8")
                )
                log_stream.flush()
            elif startup_installed and not runtime["startup"].is_file() and not runtime["started"].is_file():
                runtime["startup"].write_text(startup_script, encoding="ascii")
                log_stream.write("[GISAgent] CityEngine startup hook was removed; reinstalling it.\n".encode("utf-8"))
                log_stream.flush()
            if not runtime["started"].is_file() and not startup_installed and now >= boot_deadline:
                _stop_process(process)
                reason = (
                    "CityEngine did not finish workspace initialization within "
                    f"{CITYENGINE_BOOT_TIMEOUT_SECONDS} seconds. See {runtime['log']} and "
                    f"{runtime['metadataLog']}"
                )
                _write_process_failure(job_id, process, runtime, reason)
                return
            if (
                not runtime["started"].is_file()
                and startup_installed
                and automation_deadline is not None
                and now >= automation_deadline
            ):
                _stop_process(process)
                reason = (
                    "CityEngine startup hook was installed but automation did not start within "
                    f"{CITYENGINE_AUTOMATION_TIMEOUT_SECONDS} seconds. See {runtime['log']} and "
                    f"{runtime['metadataLog']}"
                )
                _write_process_failure(job_id, process, runtime, reason)
                return
            if now >= job_deadline:
                _stop_process(process)
                reason = (
                    f"CityEngine job exceeded {JOB_TIMEOUT_SECONDS} seconds. "
                    f"See {runtime['log']} and {runtime['metadataLog']}"
                )
                _write_process_failure(job_id, process, runtime, reason)
                return
            time.sleep(0.5)
        if process.poll() is None:
            process.wait()
        for _ in range(20):
            if runtime["result"].is_file():
                break
            time.sleep(0.1)
        if not runtime["result"].is_file():
            _write_process_failure(job_id, process, runtime)
    finally:
        log_stream.close()
        _cleanup_runtime_cache(runtime)
        metadata_path = str(runtime["metadataLog"])
        job_path = JOBS_DIR / f"{job_id}.json"
        job = _read_json(job_path)
        if job:
            job["runtimeMetadataLog"] = metadata_path
            _atomic_write_json(job_path, job)
        result_path = repository_result_path(job_id)
        result = _read_json(result_path)
        if result:
            result["runtimeMetadataLog"] = metadata_path
            _atomic_write_json(result_path, result)
        with _monitor_lock:
            _monitor_threads.pop(job_id, None)


def launch_cityengine(job_id, runtime, config_path):
    if not CITYENGINE_EXE.is_file():
        return {"started": False, "reason": "CityEngine executable not found"}
    command = [
        str(CITYENGINE_EXE), "-nosplash", "-data", str(runtime["workspace"]), "-vmargs",
        f"-Duser.home={runtime['home']}", f"-DprojectFolder={runtime['project']}",
        f"-DconfigFilePath={config_path}",
    ]
    log_stream = None
    try:
        startup_script = _startup_script()
        log_stream = runtime["log"].open("ab")
        process = subprocess.Popen(
            command,
            cwd=str(runtime["workspace"]),
            stdout=log_stream,
            stderr=subprocess.STDOUT,
        )
        try:
            return_code = process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            monitor = threading.Thread(
                target=_monitor_cityengine,
                args=(job_id, process, runtime, log_stream, startup_script),
                name=f"cityengine-{job_id}",
                daemon=True,
            )
            with _monitor_lock:
                _monitor_threads[job_id] = monitor
            monitor.start()
            return {
                "started": True,
                "pid": process.pid,
                "command": command,
                "logFile": str(runtime["log"]),
            }
        log_stream.close()
        log_stream = None
        if runtime["result"].is_file():
            return {
                "started": True,
                "pid": process.pid,
                "command": command,
                "logFile": str(runtime["log"]),
                "exitCode": return_code,
            }
        return {
            "started": False,
            "pid": process.pid,
            "reason": _failure_message(return_code, runtime),
            "command": command,
            "logFile": str(runtime["log"]),
            "exitCode": return_code,
        }
    except Exception as exc:
        if log_stream is not None:
            log_stream.close()
        return {
            "started": False,
            "reason": str(exc),
            "command": command,
            "logFile": str(runtime["log"]),
        }


def read_job(job_id):
    job_path = JOBS_DIR / f"{job_id}.json"
    result_path = repository_result_path(job_id)
    job = _read_json(job_path)
    runtime_result_path = Path(job["runtimeResult"]) if job and job.get("runtimeResult") else None
    runtime_result = _read_json(runtime_result_path) if runtime_result_path else None
    if runtime_result:
        merged = dict(_read_json(result_path) or job or {})
        merged.update(runtime_result)
        merged.setdefault("resultManifest", str(result_path))
        _atomic_write_json(result_path, merged)
        return merged
    repository_result = _read_json(result_path)
    if repository_result:
        return repository_result
    if job:
        return job
    return {"status": "not_found", "jobId": job_id}
