# -*- coding: utf-8 -*-
"""Router layer: FastAPI app and HTTP route handlers.

Handlers are thin adapters between HTTP and the service layer. They validate the
request body against a Pydantic model (so malformed input fails fast with 422
instead of 500), delegate to gis.service / gis.adapter, and translate errors
into HTTP responses. No geometry/business logic lives here.
"""
import asyncio
import shutil
import time
import traceback
from pathlib import Path
from typing import Any, Optional

from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse
from pydantic import BaseModel, ConfigDict, Field, model_validator

from cityengine_bridge import read_job as read_cityengine_job
from cityengine_bridge import cancel_job as cancel_cityengine_bridge_job
from cityengine_bridge import runtime_status as cityengine_runtime_status
from geoscene_publisher import publishing_status

from . import adapter, model, service


# ---------------------------------------------------------------------------
# Request models — every POST endpoint is validated at the boundary.
# `extra="allow"` keeps the contract forward-compatible with the service layer,
# while typed fields (lon/lat/radius) give us coercion + 422 on bad input.
# ---------------------------------------------------------------------------
class _BaseRequest(BaseModel):
    model_config = ConfigDict(extra="allow")


class PlanContextRequest(_BaseRequest):
    pass


class DemoCaseRequest(_BaseRequest):
    requirements: Optional[Any] = None
    ragContext: str = ""
    userRequest: str = ""


class UrbanMetricsRequest(_BaseRequest):
    buildings: Optional[Any] = None
    aoi: Optional[dict] = None


class SkylineRequest(_BaseRequest):
    buildings: Optional[Any] = None
    aoi: Optional[dict] = None
    analysis_type: Optional[str] = None


class SunlightRequest(_BaseRequest):
    buildings: Optional[Any] = None
    aoi: Optional[dict] = None
    analysis_type: Optional[str] = None


class FloodRequest(_BaseRequest):
    aoi: Optional[dict] = None
    buildings: Optional[Any] = None
    dem: Optional[Any] = None
    rainfall_scenario: Optional[Any] = None
    returnPeriodYears: int = Field(default=20, ge=1, le=1000)


class SiteSelectionRequest(_BaseRequest):
    """Multi-criteria site-screening inputs, supplied as GeoJSON features."""
    candidates: Optional[Any] = None
    facilities: Optional[Any] = None
    constraints: Optional[Any] = None
    aoi: Optional[dict] = None
    weights: Optional[dict] = None
    facilityInfluenceM: float = Field(default=1500, gt=0, le=50000)
    exclusionDistanceM: float = Field(default=200, ge=0, le=50000)


class NearestFacilityRequest(_BaseRequest):
    candidates: Optional[Any] = None
    facilities: Optional[Any] = None


class BufferRequest(_BaseRequest):
    lon: float = Field(..., ge=-180, le=180)
    lat: float = Field(..., ge=-90, le=90)
    radius: float = Field(default=500, gt=0, le=100000)


class _AoiOrCoordsRequest(_BaseRequest):
    aoi: Optional[dict] = None
    lon: Optional[float] = Field(default=None, ge=-180, le=180)
    lat: Optional[float] = Field(default=None, ge=-90, le=90)
    radius: float = Field(default=500, gt=0, le=100000)

    @model_validator(mode="after")
    def _require_aoi_or_coords(self):
        if self.aoi is None and (self.lon is None or self.lat is None):
            raise ValueError("Either 'aoi' or both 'lon' and 'lat' must be provided.")
        return self


class FetchBuildingsRequest(_AoiOrCoordsRequest):
    pass


class AnalyzeAreaRequest(_AoiOrCoordsRequest):
    # 会话上下文建筑（数据包加载后由 Agent 携带）：非空时优先按 AOI 裁剪，
    # 覆盖不到该范围再回退 OSM Overpass，避免数据包已加载仍拉取在线数据。
    buildings: Optional[dict] = None


class SpatialExecuteRequest(_BaseRequest):
    operation: str
    params: Optional[dict] = None


class SpatialFileInspectRequest(_BaseRequest):
    path: str
    extension: str
    sourceCrs: str = ""


class PublicDemRequest(_BaseRequest):
    aoi: dict


app = FastAPI(title="Esri Cup Professional GIS Engine")


def _server_error(stage: str, exc: Exception, **extra: Any) -> dict:
    """Return a sanitized error payload to the client.

    The full exception detail (traceback, paths, credentials) is only written
    to the server log, never echoed to the caller. ``message`` carries a
    generic, user-safe string so internal details are not leaked to the API.
    """
    print(f"{stage} failed: {traceback.format_exc()}")
    response = {"status": "Error", "stage": stage, "message": "服务内部错误，请稍后重试。"}
    response.update(extra)
    return response


def _cityengine_pipeline_terminal(result):
    status = str(result.get("status", "")).lower()
    if status in {"failed", "not_found", "error"}:
        return True
    if status != "completed":
        return False
    outputs = result.get("outputs") or {}
    if not outputs.get("slpk"):
        return True
    progress = result.get("publicationProgress") or {}
    return bool(result.get("sceneServiceUrl")) or progress.get("status") == "error"


@app.get("/health")
async def health():
    """容器健康检查用：进程存活即返回 200，不做重量级后端探测。"""
    return {"status": "ok"}


@app.post("/analysis/cityengine/plan-context")
async def plan_current_context(payload: PlanContextRequest):
    try:
        return service.evaluate_context_case(payload.model_dump())
    except Exception as exc:
        return _server_error("context_planning", exc)


@app.post("/analysis/demo_case/evaluate")
async def evaluate_planning_demo(payload: DemoCaseRequest):
    try:
        return service.evaluate_demo_case(payload.requirements, payload.ragContext, payload.userRequest)
    except Exception as exc:
        return _server_error("planning_evaluation", exc)


@app.get("/analysis/geoscene/publishing")
async def get_geoscene_publishing_status():
    return publishing_status()


@app.get("/analysis/cityengine/runtime")
async def get_cityengine_runtime():
    return cityengine_runtime_status()


@app.get("/analysis/cityengine/jobs/{job_id}")
async def get_cityengine_job(job_id: str):
    try:
        # Polling must be side-effect free. Publishing is explicit via POST.
        return read_cityengine_job(job_id)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.post("/analysis/cityengine/jobs/{job_id}/publish")
async def retry_cityengine_publication(job_id: str):
    try:
        result = read_cityengine_job(job_id)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    if result.get("status") != "completed":
        raise HTTPException(status_code=409, detail="CityEngine job is not completed")
    if not (result.get("outputs") or {}).get("slpk"):
        raise HTTPException(status_code=404, detail="CityEngine job has no SLPK output")
    result.pop("publication", None)
    result.pop("publicationShareError", None)
    # 重发布需清掉旧发布地址，否则发布线程会视为"已发布"直接返回。
    result.pop("sceneServiceUrl", None)
    result["publicationProgress"] = {
        "stage": "publication_retrying",
        "status": "running",
        "message": "正在重新发布到 GeoScene Portal",
        "updatedAt": int(time.time()),
    }
    adapter._write_cityengine_result(job_id, result)
    return adapter._start_cityengine_publication(job_id, result)


@app.post("/analysis/cityengine/jobs/{job_id}/cancel")
async def cancel_cityengine_job(job_id: str):
    try:
        return cancel_cityengine_bridge_job(job_id)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc


@app.get("/analysis/cityengine/jobs/{job_id}/wait")
async def wait_cityengine_job(job_id: str, timeout: int = 180):
    try:
        read_cityengine_job(job_id)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    deadline = time.time() + max(1, min(timeout, 600))
    while time.time() < deadline:
        result = read_cityengine_job(job_id)
        if _cityengine_pipeline_terminal(result):
            return result
        await asyncio.sleep(2)
    result = read_cityengine_job(job_id)
    result["waitTimedOut"] = True
    return result


@app.get("/analysis/cityengine/jobs/{job_id}/download/{format_name}")
async def download_cityengine_output(job_id: str, format_name: str):
    try:
        result = read_cityengine_job(job_id)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    if result.get("status") != "completed":
        raise HTTPException(status_code=409, detail="CityEngine job is not completed")
    outputs = result.get("outputs") or {}
    output = outputs.get(format_name.lower())
    if not output:
        raise HTTPException(status_code=404, detail="Requested output format was not generated")
    output_path = Path(output).resolve()
    allowed_roots = []
    runtime_output_root = result.get("runtimeOutputRoot")
    if runtime_output_root:
        runtime_root = Path(cityengine_runtime_status()["runtimeRoot"]).resolve()
        candidate_root = Path(runtime_output_root).resolve()
        if runtime_root in candidate_root.parents:
            allowed_roots.append(candidate_root)
    allowed_roots.append(Path(cityengine_runtime_status()["project"]).resolve() / "models" / "generated")
    if not any(root == output_path or root in output_path.parents for root in allowed_roots):
        raise HTTPException(status_code=403, detail="Output path is outside the CityEngine project")
    if output_path.is_dir():
        archive_base = output_path.parent / f"{job_id}-{format_name.lower()}"
        archive_path = Path(shutil.make_archive(str(archive_base), "zip", root_dir=output_path))
        return FileResponse(archive_path, filename=archive_path.name, media_type="application/zip")
    if not output_path.is_file():
        raise HTTPException(status_code=404, detail="Generated output file is missing")
    media_type = "application/octet-stream"
    return FileResponse(output_path, filename=output_path.name, media_type=media_type)


@app.get("/analysis/runtime")
async def get_runtime_status():
    return adapter.runtime_status()


@app.post("/analysis/urban_metrics")
async def calculate_urban_metrics(payload: UrbanMetricsRequest):
    try:
        return service.calculate_metrics(payload.model_dump())
    except Exception as exc:
        return _server_error("urban_metrics", exc, far=0)


@app.post("/analysis/skyline")
async def execute_skyline_analysis(payload: SkylineRequest):
    try:
        return service.calculate_skyline(payload.model_dump())
    except Exception as exc:
        return _server_error("skyline_analysis", exc, analysis_type="skyline")


@app.post("/analysis/sunlight")
async def execute_sunlight_analysis(payload: SunlightRequest):
    try:
        return service.calculate_sunlight(payload.model_dump())
    except Exception as exc:
        return _server_error("sunlight_analysis", exc, analysis_type="sunlight")


@app.post("/analysis/flood")
async def execute_flood_analysis(payload: FloodRequest):
    try:
        return service.calculate_flood_risk(payload.model_dump())
    except Exception as exc:
        return _server_error("flood_analysis", exc, analysis_type="flood")


@app.post("/analysis/site-selection")
async def execute_site_selection(payload: SiteSelectionRequest):
    try:
        return service.calculate_site_selection(payload.model_dump())
    except Exception as exc:
        return _server_error("site_selection", exc, analysis_type="site_selection")


@app.post("/analysis/nearest-facility")
async def execute_nearest_facility(payload: NearestFacilityRequest):
    try:
        return service.calculate_nearest_facility_distance(payload.model_dump())
    except Exception as exc:
        return _server_error("nearest_facility_distance", exc, analysis_type="nearest_facility_distance")


@app.post("/analysis/dem/public-raster")
async def fetch_public_dem(payload: PublicDemRequest):
    return service.fetch_public_dem_raster(payload.aoi)


@app.post("/analysis/buffer")
async def execute_buffer(payload: BufferRequest):
    try:
        feature = service.create_buffer_feature(payload.lon, payload.lat, payload.radius)
        return model._feature_collection([feature])
    except Exception as exc:
        return _server_error("buffer", exc)


@app.post("/analysis/execute")
async def execute_spatial_plan(payload: SpatialExecuteRequest):
    """Run a declarative, whitelist-only spatial analysis plan."""
    try:
        return service.execute_spatial_plan(payload.model_dump())
    except Exception as exc:
        return _server_error("spatial_plan", exc)


@app.post("/analysis/data/inspect")
async def inspect_spatial_file(payload: SpatialFileInspectRequest):
    """Inspect a managed upload and return normalized metadata/data only."""
    try:
        return service.inspect_spatial_file(payload.model_dump())
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    except Exception as exc:
        print(f"spatial file inspection failed: {traceback.format_exc()}")
        raise HTTPException(status_code=500, detail="Spatial file inspection failed") from exc


@app.post("/analysis/fetch_buildings")
async def fetch_buildings(payload: FetchBuildingsRequest):
    try:
        data = payload.model_dump()
        aoi = data.get("aoi")
        if not aoi:
            aoi = {
                "type": "Feature",
                "geometry": service.create_buffer_feature(
                    payload.lon, payload.lat, payload.radius
                )["geometry"],
                "properties": {"source": "server_buffer"},
            }
        return service.fetch_buildings_for_aoi(aoi)
    except Exception as exc:
        return _server_error("fetch_buildings", exc, building_count=0)


@app.post("/analysis/analyze_area")
async def analyze_area(payload: AnalyzeAreaRequest):
    """Create/accept an AOI, fetch real OSM buildings, then calculate metrics."""
    try:
        data = payload.model_dump()
        explicit_aoi = data.get("aoi")
        radius = float(data.get("radius", 500) or 500)
        context_buildings = data.get("buildings")
        # The requested radius is a user constraint. Do not silently enlarge
        # the AOI when a footprint provider is unavailable.
        radii = [radius]
        last_fetch = None

        for attempt, current_radius in enumerate(radii, start=1):
            if explicit_aoi:
                aoi = explicit_aoi
            else:
                feature = service.create_buffer_feature(payload.lon, payload.lat, current_radius)
                aoi = {
                    "type": "Feature",
                    "geometry": feature["geometry"],
                    "properties": feature["properties"],
                }

            # 会话上下文已有建筑（数据包/上传数据）时优先使用：按 AOI 裁剪计算，
            # 不请求 OSM；数据包覆盖不到该范围（裁剪结果为空）再回退在线拉取。
            if context_buildings:
                metrics = service.calculate_metrics({
                    "buildings": context_buildings,
                    "aoi": aoi,
                })
                if (metrics.get("building_count") or 0) > 0:
                    metrics["building_source"] = "context_data_pack"
                    metrics.update({
                        "aoi": aoi,
                        "buildings": context_buildings,
                        "data_source": "context_data_pack",
                        "radius": current_radius,
                        "attempt": attempt,
                        "action": "addBuffer",
                        "params": {
                            "longitude": payload.lon,
                            "latitude": payload.lat,
                            "radius": current_radius,
                        },
                    })
                    return metrics
                last_fetch = {
                    "status": "NoData",
                    "message": "上下文建筑未覆盖该范围，回退 OSM 在线数据。",
                }

            fetch_result = service.fetch_buildings_for_aoi(aoi)
            last_fetch = fetch_result
            if fetch_result.get("status") != "Success":
                continue

            metrics = service.calculate_metrics({
                "buildings": fetch_result.get("buildings"),
                "aoi": aoi,
            })
            metrics["building_source"] = fetch_result.get("source", "osm_overpass")
            if metrics.get("status") == "Success" and int(metrics.get("building_count", 0)) > 0:
                metrics.update({
                    "aoi": aoi,
                    "buildings": fetch_result.get("buildings"),
                    "data_source": fetch_result.get("source"),
                    "fetch_stage": fetch_result,
                    "radius": current_radius,
                    "attempt": attempt,
                    "action": "addBuffer",
                    "params": {
                        "longitude": payload.lon,
                        "latitude": payload.lat,
                        "radius": current_radius,
                    },
                })
                return metrics

        fallback = last_fetch or {"status": "NoData", "message": "No fetch attempt was completed."}
        fallback.update({
            "far": 0,
            "stage": "analyze_area",
            "message": fallback.get("message", "No valid building data was found after retries."),
            "runtime": adapter.runtime_status(),
        })
        return fallback
    except Exception as exc:
        return _server_error("analyze_area", exc, far=0, building_count=0)
