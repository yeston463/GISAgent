import json
import os
import subprocess
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
SCRIPTS_DIR = PROJECT / "scripts" / "generated"
DATA_DIR = PROJECT / "data" / "generated"
RULES_DIR = PROJECT / "rules" / "generated"
MODELS_DIR = PROJECT / "models" / "generated"
CONFIG_DIR = PROJECT / "config" / "generated"
CITYENGINE_HOME = ROOT / "cityengine-home"
JYTHON_STARTUP = CITYENGINE_HOME / ".CityEngine" / "2025.1R.win32.win32.x86_64" / "jythonCache3" / "startup.py"
STARTUP_SCRIPT = JYTHON_STARTUP
ALLOWED_EXPORTS = {"slpk", "obj", "fbx", "gltf"}
ALLOWED_STYLES = {"modern", "residential", "commercial", "industrial", "institutional", "mixed_use"}


def runtime_status():
    return {
        "available": CITYENGINE_EXE.is_file(),
        "executable": str(CITYENGINE_EXE),
        "workspace": str(WORKSPACE),
        "project": str(PROJECT),
        "startupScript": str(STARTUP_SCRIPT),
        "automationConfigured": STARTUP_SCRIPT.is_file() and (PROJECT / ".project").is_file(),
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


def _ensure_project():
    for path in (WORKSPACE, PROJECT, CITYENGINE_HOME, JYTHON_STARTUP.parent, JOBS_DIR, RESULTS_DIR, SCRIPTS_DIR, DATA_DIR, RULES_DIR, MODELS_DIR, CONFIG_DIR, PROJECT / "scenes"):
        path.mkdir(parents=True, exist_ok=True)
    project_file = PROJECT / ".project"
    if not project_file.exists():
        project_file.write_text('''<?xml version="1.0" encoding="UTF-8"?>\n<projectDescription>\n  <name>GISAgentCityEngineAutomation</name>\n  <comment></comment>\n  <projects></projects>\n  <buildSpec></buildSpec>\n  <natures></natures>\n</projectDescription>\n''', encoding="utf-8")
    STARTUP_SCRIPT.write_text(_startup_script(), encoding="utf-8")


def _startup_script():
    return '''from scripting import *\nfrom java import lang\nimport ConfigParser\nimport sys\n\nif __name__ == '__startup__':\n    ce = CE()\n    projectFolder = lang.System.getProperty("projectFolder")\n    configFilePath = lang.System.getProperty("configFilePath")\n    if "automationProject" in ce.listProjects():\n        ce.removeProject("automationProject")\n    ce.importProject(projectFolder, False, "automationProject")\n    cp = ConfigParser.ConfigParser()\n    cp.read(configFilePath)\n    scriptPath = cp.get("config", "scriptPath")\n    sys.path.append(ce.toFSPath("/automationProject/scripts/generated"))\n    execfile(scriptPath)\n'''


def _prepare_buildings(buildings, rule_set, problem_buildings, requirements):
    problem_ids = {
        str((feature.get("properties") or {}).get("id"))
        for feature in (problem_buildings or {}).get("features", [])
    }
    rule_height = _number(rule_set.get("rules", {}).get("buildingHeight", {}).get("max"), 54.0, 3, 300)
    prepared = {"type": "FeatureCollection", "features": []}
    actions = []
    for feature in buildings.get("features", []):
        props = dict(feature.get("properties") or {})
        building_id = str(props.get("id", ""))
        current_height = _number(props.get("height"), 3.0, 3, 300)
        should_modify = building_id in problem_ids
        target_height = min(current_height, rule_height) if should_modify else current_height
        props["ce_height"] = target_height
        props["ce_modify"] = 1 if target_height != current_height else 0
        props["ce_setback"] = 0.0
        props["ce_color"] = requirements["primaryColor"] if should_modify else "#b7c1c8"
        if target_height != current_height:
            actions.append({
                "buildingId": building_id,
                "name": props.get("name"),
                "action": "reduce_height",
                "fromHeight": current_height,
                "toHeight": target_height,
            })
        prepared["features"].append({"type": "Feature", "properties": props, "geometry": feature.get("geometry")})
    return prepared, actions


def _write_shapefile(buildings, shp_path):
    import geopandas as gpd
    from shapely.geometry import shape
    rows = []
    for feature in buildings.get("features", []):
        props = dict(feature.get("properties") or {})
        props["geometry"] = shape(feature["geometry"])
        rows.append(props)
    gdf = gpd.GeoDataFrame(rows, geometry="geometry", crs="EPSG:4326")
    gdf.to_file(shp_path, driver="ESRI Shapefile", encoding="UTF-8")


def _generate_cga(requirements):
    return f'''version "2025.0"\n\nattr ce_height = 12\nattr ce_modify = 0\nattr ce_setback = 0\nattr ce_color = "#b7c1c8"\n\n@StartRule\nLot --> case ce_setback > 0: setback(ce_setback) {{ all: Buildable }} else: Buildable\nBuildable --> extrude(ce_height) Building\nBuilding --> comp(f) {{ side: Facade | top: Roof }}\nFacade --> color(ce_color) split(y) {{ ~{requirements["floorHeight"]}: Floor }}*\nFloor --> color(ce_color)\nRoof --> color("#d9e2e8")\n'''


def _generated_script(job_id, layer_name, shp_workspace_path, rule_workspace_path, result_path, requirements):
    exports = requirements["exportFormats"]
    return f'''from scripting import *\nimport json\nimport os\nimport traceback\n\nce = CE()\nJOB_ID = {job_id!r}\nRESULT_PATH = {str(result_path)!r}\nEXPORTS = {exports!r}\n\ndef write_result(status, outputs, message):\n    result = {{"jobId": JOB_ID, "status": status, "outputs": outputs, "message": message}}\n    f = open(RESULT_PATH, "w")\n    f.write(json.dumps(result, ensure_ascii=False, indent=2))\n    f.close()\n\ndef run():\n    outputs = {{}}\n    try:\n        scene_path = "/automationProject/scenes/{job_id}.cej"\n        ce.newFile(scene_path)\n        settings = SHPImportSettings()\n        ce.importFile(ce.toFSPath({shp_workspace_path!r}), settings)\n        layers = ce.getObjectsFrom(ce.scene, ce.isShapeLayer, ce.withName({layer_name!r}))\n        if not layers:\n            layers = ce.getObjectsFrom(ce.scene, ce.isShapeLayer)\n        if not layers:\n            raise RuntimeError("CityEngine did not create a shape layer from the footprint shapefile")\n        shapes = ce.getObjectsFrom(layers[0], ce.isShape)\n        if not shapes:\n            raise RuntimeError("No footprint shapes were imported")\n        ce.setRuleFile(shapes, {rule_workspace_path!r})\n        ce.setStartRule(shapes, "Lot")\n        ce.generateModels(shapes)\n        ce.waitForUIIdle()\n        output_dir = ce.toFSPath("/automationProject/models/generated/{job_id}")\n        if not os.path.isdir(output_dir):\n            os.makedirs(output_dir)\n        if "obj" in EXPORTS:\n            obj = OBJExportModelSettings()\n            obj.setOutputPath(output_dir)\n            obj.setBaseName("{job_id}")\n            obj.setFileGranularity(OBJExportModelSettings.START_SHAPE)\n            obj.setExistingFiles(OBJExportModelSettings.OVERWRITE)\n            obj.setTerrainLayers(OBJExportModelSettings.TERRAIN_NONE)\n            ce.export(shapes, obj)\n            outputs["obj"] = output_dir\n        if "slpk" in EXPORTS:\n            slpk = SPKMeshExportModelSettings()\n            slpk.setOutputPath(output_dir)\n            slpk.setBaseName("{job_id}")\n            slpk.setSceneType("Local")\n            slpk.setExistingFiles(SPKMeshExportModelSettings.OVERWRITE)\n            slpk.setFileSize(SPKMeshExportModelSettings.MIDSIZE_FILE)\n            ce.export(shapes, slpk)\n            outputs["slpk"] = os.path.join(output_dir, "{job_id}.slpk")\n        if "fbx" in EXPORTS:\n            fbx = FBXExportModelSettings()\n            fbx.setOutputPath(output_dir)\n            fbx.setBaseName("{job_id}")\n            ce.export(shapes, fbx)\n            outputs["fbx"] = output_dir\n        if "gltf" in EXPORTS:\n            gltf = GLTFExportModelSettings()\n            gltf.setOutputPath(output_dir)\n            gltf.setBaseName("{job_id}")\n            ce.export(shapes, gltf)\n            outputs["gltf"] = output_dir\n        ce.saveFile(scene_path)\n        write_result("completed", outputs, "CityEngine generation and export completed")\n    except Exception as exc:\n        write_result("failed", outputs, str(exc) + "\\n" + traceback.format_exc())\n    finally:\n        try:\n            ce.waitForUIIdle()\n            ce.closeFile()\n        except:\n            pass\n        ce.exit()\n\nrun()\n'''


def submit_planning_job(case_data, rule_set, current_metrics, problem_buildings, requirements=None, rag_context="", user_request=""):
    _ensure_project()
    job_id = f"ce-{datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S')}-{uuid.uuid4().hex[:8]}"
    normalized = normalize_requirements(requirements, rule_set)
    prepared_buildings, optimization_actions = _prepare_buildings(case_data["buildings"], rule_set, problem_buildings, normalized)
    job_path = JOBS_DIR / f"{job_id}.json"
    result_path = RESULTS_DIR / f"{job_id}.json"
    cga_path = RULES_DIR / f"{job_id}.cga"
    script_path = SCRIPTS_DIR / f"{job_id}.py"
    config_path = CONFIG_DIR / f"{job_id}.cfg"
    shp_path = DATA_DIR / f"{job_id}.shp"
    _write_shapefile(prepared_buildings, shp_path)
    cga_path.write_text(_generate_cga(normalized), encoding="utf-8")
    script_path.write_text(_generated_script(job_id, job_id, f"/automationProject/data/generated/{job_id}.shp", f"/automationProject/rules/generated/{job_id}.cga", result_path, normalized), encoding="utf-8")
    cfg = ConfigParser()
    cfg["config"] = {"scriptPath": str(script_path)}
    with config_path.open("w", encoding="utf-8") as stream:
        cfg.write(stream)
    job = {
        "jobId": job_id, "status": "queued", "engine": "ArcGIS CityEngine 2025.1",
        "createdAt": datetime.now(timezone.utc).isoformat(), "requirements": normalized,
        "requirementsSource": {"userRequest": user_request, "ragContext": rag_context},
        "case": case_data, "currentMetrics": current_metrics, "problemBuildings": problem_buildings,
        "optimizationActions": optimization_actions,
        "footprints": str(shp_path), "generatedScript": str(script_path), "generatedRule": str(cga_path),
        "configFile": str(config_path), "resultManifest": str(result_path),
    }
    job_path.write_text(json.dumps(job, ensure_ascii=False, indent=2), encoding="utf-8")
    launch = launch_cityengine(config_path)
    return {
        "jobId": job_id, "status": "queued", "jobFile": str(job_path), "footprints": str(shp_path),
        "generatedScript": str(script_path), "generatedRule": str(cga_path), "configFile": str(config_path),
        "resultManifest": str(result_path), "requirements": normalized, "cityEngine": runtime_status(), "launch": launch,
    }


def launch_cityengine(config_path):
    if not CITYENGINE_EXE.is_file():
        return {"started": False, "reason": "CityEngine executable not found"}
    command = [str(CITYENGINE_EXE), "-data", str(WORKSPACE), "-vmargs", f"-Duser.home={CITYENGINE_HOME}", f"-DprojectFolder={PROJECT}", f"-DconfigFilePath={config_path}"]
    try:
        process = subprocess.Popen(command, cwd=str(WORKSPACE), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        return {"started": True, "pid": process.pid, "command": command}
    except Exception as exc:
        return {"started": False, "reason": str(exc), "command": command}


def read_job(job_id):
    result_path = RESULTS_DIR / f"{job_id}.json"
    if result_path.is_file():
        return json.loads(result_path.read_text(encoding="utf-8"))
    job_path = JOBS_DIR / f"{job_id}.json"
    if job_path.is_file():
        return json.loads(job_path.read_text(encoding="utf-8"))
    return {"status": "not_found", "jobId": job_id}
