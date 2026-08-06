# -*- coding: utf-8 -*-
"""Adapter layer: talks to optional GIS backends and external services.

Responsibilities:
- Detect which geometry backend is available (ArcPy / GeoPandas+Shapely / ArcGIS API / stdlib).
- Call the Overpass API for OSM building footprints.
- Wrap ArcPy / GeoPandas geometry operations.
- Wrap the CityEngine bridge and GeoScene publisher for SLPK hosting.

No FastAPI / route concerns live here.
"""
import importlib
import hashlib
import json
import os
import threading
import time
import traceback
import tempfile
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

from cityengine_bridge import read_job as read_cityengine_job
from cityengine_bridge import runtime_status as cityengine_runtime_status
from cityengine_bridge import submit_planning_job
from cityengine_bridge import write_job_result as write_cityengine_job_result
from geoscene_publisher import inspect_publication, publish_slpk, publishing_status, share_publication, verify_scene_service
from geoscene_publisher import _request_json as _geoscene_request
from geoscene_publisher import _token as _geoscene_token

from . import model

OVERPASS_ENDPOINTS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
    "https://overpass.private.coffee/api/interpreter",
    "https://overpass.nchc.org.tw/api/interpreter",
]
OVERPASS_CACHE_TTL_SECONDS = 900
OVERPASS_DISK_CACHE_TTL_SECONDS = max(
    OVERPASS_CACHE_TTL_SECONDS,
    int(os.environ.get("OVERPASS_DISK_CACHE_TTL_SECONDS", "604800")),
)
OVERPASS_CACHE_DIR = Path(os.environ.get(
    "GISAGENT_OVERPASS_CACHE_DIR",
    str(Path(tempfile.gettempdir()) / "GISAgent" / "overpass-cache"),
))
OVERPASS_REQUEST_TIMEOUT_SECONDS = max(1.0, float(os.environ.get("OVERPASS_REQUEST_TIMEOUT_SECONDS", "6")))
OVERPASS_TOTAL_TIMEOUT_SECONDS = max(OVERPASS_REQUEST_TIMEOUT_SECONDS, float(
    os.environ.get("OVERPASS_TOTAL_TIMEOUT_SECONDS", "14")
))
_overpass_cache = {}
_overpass_cache_lock = threading.Lock()


def _overpass_cache_path(query):
    digest = hashlib.sha256(query.encode("utf-8")).hexdigest()
    return OVERPASS_CACHE_DIR / f"{digest}.json"


def _read_overpass_disk_cache(query):
    path = _overpass_cache_path(query)
    try:
        cached = json.loads(path.read_text(encoding="utf-8"))
        stored_at = float(cached.get("storedAtEpoch", 0))
        payload = cached.get("payload")
        if time.time() - stored_at > OVERPASS_DISK_CACHE_TTL_SECONDS:
            return None
        return payload if isinstance(payload, dict) else None
    except (OSError, ValueError, TypeError, json.JSONDecodeError):
        return None


def _write_overpass_disk_cache(query, payload):
    if not isinstance(payload, dict):
        return
    path = _overpass_cache_path(query)
    temporary_path = path.with_name(f"{path.name}.{os.getpid()}.tmp")
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        temporary_path.write_text(json.dumps({
            "storedAtEpoch": time.time(),
            "payload": payload,
        }, ensure_ascii=False), encoding="utf-8")
        temporary_path.replace(path)
    except OSError:
        try:
            temporary_path.unlink(missing_ok=True)
        except OSError:
            pass


def _optional_import(module_name):
    try:
        return importlib.import_module(module_name), None
    except Exception as exc:
        return None, f"{type(exc).__name__}: {exc}"


pd, PANDAS_IMPORT_ERROR = _optional_import("pandas")
gpd, GEOPANDAS_IMPORT_ERROR = _optional_import("geopandas")
arcpy, ARCPY_IMPORT_ERROR = _optional_import("arcpy")
arcgis, ARCGIS_IMPORT_ERROR = _optional_import("arcgis")

try:
    from shapely.geometry import Point, Polygon, mapping, shape

    SHAPELY_IMPORT_ERROR = None
except Exception as exc:
    Point = None
    Polygon = None
    mapping = None
    shape = None
    SHAPELY_IMPORT_ERROR = f"{type(exc).__name__}: {exc}"

HAS_ARCPY = arcpy is not None
HAS_ARCGIS = arcgis is not None
HAS_OPEN_SOURCE = pd is not None and gpd is not None and Point is not None and Polygon is not None

GEOSCENE_PORTAL_URL = os.environ.get("GEOSCENE_PORTAL_URL", "").rstrip("/")
GEOSCENE_PORTAL_USERNAME = os.environ.get("GEOSCENE_PORTAL_USERNAME", "")
GEOSCENE_PORTAL_PASSWORD = os.environ.get("GEOSCENE_PORTAL_PASSWORD", "")
GEOSCENE_GEOMETRY_SERVICE_URL = os.environ.get(
    "GEOSCENE_GEOMETRY_SERVICE_URL",
    f"{GEOSCENE_PORTAL_URL}/rest/services/Utilities/Geometry/GeometryServer",
)
GEOSCENE_SERVER_METRICS_ENABLED = os.environ.get(
    "GEOSCENE_SERVER_METRICS_ENABLED", "false"
).lower() in {"1", "true", "yes", "on"}
HAS_GEOSCENE_SERVER = bool(
    GEOSCENE_SERVER_METRICS_ENABLED
    and GEOSCENE_PORTAL_URL and GEOSCENE_PORTAL_USERNAME and GEOSCENE_PORTAL_PASSWORD
)

if HAS_ARCPY:
    try:
        arcpy.env.overwriteOutput = True
    except Exception:
        pass


def _preferred_backend():
    if HAS_ARCPY:
        return "geoscene_arcpy"
    if HAS_OPEN_SOURCE:
        return "open_source_geopandas"
    if HAS_ARCGIS:
        return "arcgis_python_api_metadata_only"
    if HAS_GEOSCENE_SERVER:
        return "geoscene_server"
    return "standard_library_limited"


def _module_status(name, module, error):
    status = {"available": module is not None}
    if module is not None:
        version = getattr(module, "__version__", None)
        if version:
            status["version"] = str(version)
        if name == "arcpy":
            try:
                status["install_info"] = arcpy.GetInstallInfo()
            except Exception as exc:
                status["install_info_error"] = str(exc)
    elif error:
        status["error"] = error
    return status


def runtime_status():
    return {
        "status": "Success",
        "python_executable": __import__("sys").executable,
        "python_version": __import__("sys").version,
        "preferred_backend": _preferred_backend(),
        "backend_priority": [
            "geoscene_arcpy",
            "open_source_geopandas",
            "standard_library_limited",
        ],
        "capabilities": {
            "geoscene_server": {
                "available": HAS_GEOSCENE_SERVER,
                "role": "display_publishing",
                "portal_url": GEOSCENE_PORTAL_URL,
                "geometry_service_url": GEOSCENE_GEOMETRY_SERVICE_URL,
                "metrics_enabled": GEOSCENE_SERVER_METRICS_ENABLED,
            },
            "arcpy": _module_status("arcpy", arcpy, ARCPY_IMPORT_ERROR),
            "arcgis": _module_status("arcgis", arcgis, ARCGIS_IMPORT_ERROR),
            "geopandas": _module_status("geopandas", gpd, GEOPANDAS_IMPORT_ERROR),
            "pandas": _module_status("pandas", pd, PANDAS_IMPORT_ERROR),
            "shapely": {"available": Point is not None, "error": SHAPELY_IMPORT_ERROR},
        },
        "data_policy": "Metric computation prefers the professional ArcPy backend when available (ArcGIS Pro/GeoScene Pro interpreter), falling back to the open-source stack (GeoPandas/Shapely) and finally the pure standard library. GeoScene Enterprise serves as the display/publishing platform for hosted 3D scene services (SLPK/SceneServer) and is only used for metrics when explicitly enabled. OSM/Overpass HTTP remains the building-footprint acquisition source.",
    }


# --------------------------------------------------------------------------- #
# Overpass (OSM building footprints)
# --------------------------------------------------------------------------- #
def _call_overpass(query):
    now = time.monotonic()
    with _overpass_cache_lock:
        cached = _overpass_cache.get(query)
        if cached and now - cached["stored_at"] < OVERPASS_CACHE_TTL_SECONDS:
            return cached["payload"]

    disk_cached = _read_overpass_disk_cache(query)
    if disk_cached is not None:
        with _overpass_cache_lock:
            _overpass_cache[query] = {"stored_at": now, "payload": disk_cached}
        return disk_cached

    encoded = urllib.parse.urlencode({"data": query}).encode("utf-8")
    errors = []
    deadline = time.monotonic() + OVERPASS_TOTAL_TIMEOUT_SECONDS
    # Public Overpass nodes can be overloaded. Bound the total acquisition
    # time so the Agent can promptly fall back to the visible SceneLayer.
    for pass_number in range(2):
        for endpoint in OVERPASS_ENDPOINTS:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            req = urllib.request.Request(
                endpoint,
                data=encoded,
                headers={"User-Agent": "esri-cup-gis-agent/1.0"},
                method="POST",
            )
            try:
                with urllib.request.urlopen(req, timeout=min(OVERPASS_REQUEST_TIMEOUT_SECONDS, remaining)) as response:
                    payload = json.loads(response.read().decode("utf-8"))
                    with _overpass_cache_lock:
                        _overpass_cache[query] = {"stored_at": time.monotonic(), "payload": payload}
                    _write_overpass_disk_cache(query, payload)
                    return payload
            except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
                errors.append(f"{endpoint}: {exc}")
        if time.monotonic() >= deadline:
            break
        if pass_number == 0:
            time.sleep(min(0.5, max(0, deadline - time.monotonic())))
    detail = "; ".join(errors[-3:]) or "no Overpass endpoint responded"
    raise RuntimeError(f"Overpass unavailable within {OVERPASS_TOTAL_TIMEOUT_SECONDS:g}s: {detail}")


# --------------------------------------------------------------------------- #
# GeoPandas adapter
# --------------------------------------------------------------------------- #
def dict_to_gdf(data_source, target_crs=None):
    """Convert GeoJSON/ArcGIS polygon features to a GeoDataFrame when open-source GIS is available."""
    if not HAS_OPEN_SOURCE:
        return None

    features = model._features_from_source(data_source)
    if not features:
        return None

    geoms = []
    attributes = []
    for feature in features:
        try:
            geom = shape(feature.get("geometry"))
            if geom is None or geom.is_empty:
                continue
            if not geom.is_valid:
                geom = geom.buffer(0)
            if geom.is_empty:
                continue
            geoms.append(geom)
            attributes.append(model._json_safe_dict(feature.get("properties", {})))
        except Exception as exc:
            print(f"feature parse failed: {exc}")

    if not geoms:
        return None

    source_epsg = model._source_epsg_from_features(features)
    gdf_obj = gpd.GeoDataFrame(attributes, geometry=geoms, crs=f"EPSG:{source_epsg}")
    if target_crs:
        gdf_obj = gdf_obj.to_crs(target_crs)
    return gdf_obj


def _create_buffer_feature_open_source(lon, lat, radius):
    metric = model._metric_crs(lon, lat)
    point = gpd.GeoDataFrame(geometry=[Point(lon, lat)], crs="EPSG:4326").to_crs(metric)
    buffered = gpd.GeoDataFrame(geometry=point.buffer(radius), crs=metric).to_crs("EPSG:4326")
    feature = json.loads(buffered.to_json())["features"][0]
    feature["properties"] = {
        "source": "server_buffer",
        "backend": "open_source_geopandas",
        "lon": lon,
        "lat": lat,
        "radius": radius,
    }
    return feature


def _clip_features_open_source(raw_features, aoi_geojson):
    buildings = dict_to_gdf(model._feature_collection(raw_features))
    aoi = dict_to_gdf(aoi_geojson)
    if buildings is None or buildings.empty or aoi is None or aoi.empty:
        return []

    metric = model._metric_crs_for_gdf(aoi)
    buildings_m = buildings.to_crs(metric)
    aoi_m = aoi.to_crs(metric)
    clipped = gpd.clip(buildings_m, aoi_m)
    clipped = clipped[clipped.geometry.notna() & ~clipped.geometry.is_empty]
    if clipped.empty:
        return []
    clipped = clipped.to_crs("EPSG:4326")
    return json.loads(clipped.to_json()).get("features", [])


# --------------------------------------------------------------------------- #
# GeoScene Enterprise server adapter (Geometry Service)
# --------------------------------------------------------------------------- #
# The spatial math below is executed on the GeoScene Enterprise server through
# its stock Geometry Service (areasAndLengths / intersect / union). This makes
# the server the authority for geometry, not the local interpreter. The result
# structure mirrors adapter._clip_features_bbox semantics (an intersecting
# building contributes its full footprint) so downstream aggregation and the
# determinism guarantee stay identical to the standard-library path.


def _geoscene_server_geometry_url():
    return GEOSCENE_GEOMETRY_SERVICE_URL


def _to_server_geometry(geometry):
    """Normalize a geometry to esri polygon JSON for the Geometry Service.

    The GeoScene Enterprise Geometry Service parses esri polygon JSON
    (``{"rings": [...]}``), not GeoJSON (``{"type": "Polygon",
    "coordinates": [...]}``). Geometries that are already in esri ring form
    (e.g. the results of a prior server union) pass through unchanged.
    """
    if not isinstance(geometry, dict):
        return None
    if "rings" in geometry:
        return geometry
    return _geojson_to_esri_json(geometry, 4326)


def _server_area(geometry, out_crs=3395):
    """Area (square metres) of a polygon computed by the server.

    GeoScene's Enterprise Geometry Service uses a ``calculationType`` enum
    (``geodesic`` / ``planar`` / ``preserveshape``) rather than ArcGIS's boolean
    ``geodesic`` flag, and it parses esri polygon JSON (``{rings: [...]}``).
    """
    if not HAS_GEOSCENE_SERVER:
        raise RuntimeError(
            "GeoScene Enterprise is not configured. Set GEOSCENE_PORTAL_URL, "
            "GEOSCENE_PORTAL_USERNAME and GEOSCENE_PORTAL_PASSWORD."
        )
    esri_geometry = _to_server_geometry(geometry)
    if not esri_geometry:
        return 0.0
    payload = _geoscene_request(
        f"{_geoscene_server_geometry_url()}/areasAndLengths",
        {
            "token": _geoscene_token(),
            "f": "json",
            "calculationType": "geodesic",
            "polygons": json.dumps([esri_geometry]),
            "lengthUnit": "esriMeters",
            "areaUnit": "esriSquareMeters",
        },
        timeout=60,
    )
    areas = (payload or {}).get("areas") or []
    return float(areas[0]) if areas else 0.0


def _server_intersect(geometry, clip_geometry):
    """Ask the server to clip a building geometry against the AOI geometry.

    Returns a list of server-side intersection geometries (empty when the
    building does not overlap the AOI). GeoScene's Geometry Service names these
    parameters ``geometries`` (input) and ``geometry`` (overlay), not ArcGIS's
    ``geometries1``/``geometries2``.
    """
    esri_geometry = _to_server_geometry(geometry)
    esri_clip = _to_server_geometry(clip_geometry)
    if not esri_geometry or not esri_clip:
        return []
    payload = _geoscene_request(
        f"{_geoscene_server_geometry_url()}/intersect",
        {
            "token": _geoscene_token(),
            "f": "json",
            "sr": 4326,
            "geometries": json.dumps([esri_geometry]),
            "geometry": json.dumps([esri_clip]),
        },
        timeout=60,
    )
    return (payload or {}).get("geometries") or []


def _server_union(geometries):
    """Ask the server to union multiple AOI polygons into one clip geometry."""
    esri_geometries = [g for g in map(_to_server_geometry, geometries) if g]
    if not esri_geometries:
        return []
    payload = _geoscene_request(
        f"{_geoscene_server_geometry_url()}/union",
        {
            "token": _geoscene_token(),
            "f": "json",
            "sr": 4326,
            "geometries": json.dumps(esri_geometries),
        },
        timeout=60,
    )
    return (payload or {}).get("geometries") or []


# --------------------------------------------------------------------------- #
# ArcPy adapter
# --------------------------------------------------------------------------- #
def _arcpy_spatial_reference(epsg):
    return arcpy.SpatialReference(model._epsg_number(epsg))


def _geojson_to_esri_json(geom, epsg=4326):
    geom = model._normalize_geometry(geom)
    if not geom:
        return None
    sr = {"wkid": model._epsg_number(epsg)}
    geom_type = geom.get("type")
    coords = geom.get("coordinates") or []
    if geom_type == "Point":
        return {"x": coords[0], "y": coords[1], "spatialReference": sr}
    if geom_type == "Polygon":
        return {"rings": coords, "spatialReference": sr}
    if geom_type == "MultiPolygon":
        rings = []
        for polygon in coords:
            rings.extend(polygon)
        return {"rings": rings, "spatialReference": sr}
    return None


def _arcpy_from_geojson_geometry(geom, source_epsg=4326):
    if not HAS_ARCPY:
        return None
    geom = model._normalize_geometry(geom)
    if not geom:
        return None

    if source_epsg == 4326:
        try:
            return arcpy.AsShape(geom, False)
        except Exception:
            pass

    esri_json = _geojson_to_esri_json(geom, source_epsg)
    if not esri_json:
        return None
    return arcpy.AsShape(esri_json, True)


def _arcpy_records(data_source, target_crs=None):
    features = model._features_from_source(data_source)
    if not features:
        return []

    source_epsg = model._source_epsg_from_features(features)
    target_epsg = model._epsg_number(target_crs) if target_crs else source_epsg
    target_sr = _arcpy_spatial_reference(target_epsg)
    records = []
    for feature in features:
        try:
            geom = _arcpy_from_geojson_geometry(feature.get("geometry"), source_epsg)
            if geom is None:
                continue
            if target_epsg != source_epsg:
                geom = geom.projectAs(target_sr)
            if abs(float(getattr(geom, "area", 0) or 0)) <= 0:
                continue
            records.append({
                "geometry": geom,
                "properties": model._json_safe_dict(feature.get("properties", {})),
            })
        except Exception as exc:
            print(f"arcpy geometry parse failed: {exc}")
    return records


def _arcpy_area(geom):
    if geom is None:
        return 0.0
    try:
        if getattr(geom, "isEmpty", False):
            return 0.0
    except Exception:
        pass
    try:
        return abs(float(geom.area))
    except Exception:
        return 0.0


def _union_arcpy_geometries(records):
    geoms = [record["geometry"] for record in records if _arcpy_area(record.get("geometry")) > 0]
    if not geoms:
        return None
    merged = geoms[0]
    for geom in geoms[1:]:
        merged = merged.union(geom)
    return merged


def _intersect_arcpy_polygon(geom, clip_geom):
    if geom is None or clip_geom is None:
        return None
    for dimension in (4, "POLYGON"):
        try:
            return geom.intersect(clip_geom, dimension)
        except Exception:
            continue
    return None


def _arcpy_to_geojson_geometry(geom):
    data = json.loads(geom.JSON)
    if "curveRings" in data:
        for distance in (5, 10, 25):
            try:
                dense = geom.densify("DISTANCE", distance, 0.1)
                data = json.loads(dense.JSON)
                if "rings" in data:
                    break
            except Exception:
                continue
    if "rings" in data:
        return {"type": "Polygon", "coordinates": data.get("rings", [])}
    if "x" in data and "y" in data:
        return {"type": "Point", "coordinates": [data["x"], data["y"]]}
    return None


def _create_buffer_feature_arcpy(lon, lat, radius):
    metric = model._metric_crs(lon, lat)
    sr_wgs = _arcpy_spatial_reference(4326)
    sr_metric = _arcpy_spatial_reference(metric)
    point = arcpy.PointGeometry(arcpy.Point(lon, lat), sr_wgs)
    buffered = point.projectAs(sr_metric).buffer(radius).projectAs(sr_wgs)
    geometry = _arcpy_to_geojson_geometry(buffered)
    if not geometry:
        raise RuntimeError("ArcPy returned an unsupported buffer geometry.")
    return {
        "type": "Feature",
        "geometry": geometry,
        "properties": {
            "source": "server_buffer",
            "backend": "geoscene_arcpy",
            "lon": lon,
            "lat": lat,
            "radius": radius,
        },
    }


def _clip_features_arcpy(raw_features, aoi_geojson):
    metric = model._metric_crs_for_features(model._features_from_source(aoi_geojson))
    buildings = _arcpy_records(model._feature_collection(raw_features), metric)
    aoi = _arcpy_records(aoi_geojson, metric)
    if not buildings or not aoi:
        return []
    aoi_union = _union_arcpy_geometries(aoi)
    sr_wgs = _arcpy_spatial_reference(4326)
    clipped_features = []
    for record in buildings:
        clipped = _intersect_arcpy_polygon(record["geometry"], aoi_union)
        if _arcpy_area(clipped) <= 0:
            continue
        clipped_wgs = clipped.projectAs(sr_wgs)
        geometry = _arcpy_to_geojson_geometry(clipped_wgs)
        if geometry:
            clipped_features.append({
                "type": "Feature",
                "geometry": geometry,
                "properties": record["properties"],
            })
    return clipped_features


# --------------------------------------------------------------------------- #
# Bounding-box clipping (pure, stdlib fallback)
# --------------------------------------------------------------------------- #
def _feature_bounds(feature):
    try:
        return model._bounds_from_features([feature])
    except Exception:
        return None


def _bbox_intersects(a, b):
    return not (a[2] < b[0] or a[0] > b[2] or a[3] < b[1] or a[1] > b[3])


def _clip_features_bbox(raw_features, aoi_geojson):
    aoi_bbox = model._bounds_from_features(model._features_from_source(aoi_geojson))
    clipped = []
    for feature in raw_features:
        bbox = _feature_bounds(feature)
        if bbox and _bbox_intersects(bbox, aoi_bbox):
            clipped.append(feature)
    return clipped


# --------------------------------------------------------------------------- #
# CityEngine publishing wrappers (threaded)
# --------------------------------------------------------------------------- #
_publication_lock = threading.Lock()
_publication_threads = {}


def _write_cityengine_result(job_id, result):
    write_cityengine_job_result(job_id, result)


def _update_publication_progress(job_id, stage, status, message, details=None):
    with _publication_lock:
        result = read_cityengine_job(job_id)
        event = {
            "stage": stage,
            "status": status,
            "message": message,
            "updatedAt": int(time.time()),
        }
        if details:
            event.update(details)
        result["publicationProgress"] = event
        result.setdefault("publicationTimeline", []).append(dict(event))
        _write_cityengine_result(job_id, result)


def _publish_cityengine_result(job_id):
    try:
        result = read_cityengine_job(job_id)
        outputs = result.get("outputs") or {}
        publication = publish_slpk(
            outputs["slpk"],
            job_id,
            lambda stage, status, message, details: _update_publication_progress(
                job_id, stage, status, message, details
            ),
        )
        _update_publication_progress(
            job_id,
            "geoscene_hosting",
            "running",
            "正在验证 GeoScene 托管的 SceneServer",
            {"sceneServiceUrl": publication.get("sceneServiceUrl")},
        )
        hosted_service = verify_scene_service(publication["sceneServiceUrl"])
        publication["hostedService"] = hosted_service
        result = read_cityengine_job(job_id)
        result["publication"] = inspect_publication(publication)
        result["sceneServiceUrl"] = result["publication"].get(
            "sceneServiceUrl", publication["sceneServiceUrl"]
        )
        hosted_event = {
            "stage": "geoscene_hosted",
            "status": "success",
            "message": "GeoScene 托管完成，SceneServer 已可访问",
            "sceneServiceUrl": result["sceneServiceUrl"],
            "updatedAt": int(time.time()),
        }
        result["publicationProgress"] = hosted_event
        result.setdefault("publicationTimeline", []).append(dict(hosted_event))
        _write_cityengine_result(job_id, result)
    except Exception as exc:
        with _publication_lock:
            result = read_cityengine_job(job_id)
            previous_progress = result.get("publicationProgress") or {}
            result["publication"] = {"status": "failed", "message": str(exc)}
            failed_event = {
                "stage": "publication_failed",
                "failedStage": previous_progress.get("stage", "portal_uploading"),
                "status": "error",
                "message": str(exc),
                "updatedAt": int(time.time()),
            }
            result["publicationProgress"] = failed_event
            result.setdefault("publicationTimeline", []).append(dict(failed_event))
            _write_cityengine_result(job_id, result)
    finally:
        with _publication_lock:
            _publication_threads.pop(job_id, None)


def _start_cityengine_publication(job_id, result):
    if result.get("sceneServiceUrl"):
        return result
    outputs = result.get("outputs") or {}
    if not outputs.get("slpk"):
        return result
    progress = result.get("publicationProgress") or {}
    if progress.get("status") == "error":
        return result
    with _publication_lock:
        active = _publication_threads.get(job_id)
        if active and active.is_alive():
            return result
        exported_event = {
            "stage": "slpk_exported",
            "status": "success",
            "message": "SLPK 导出完成，准备上传 GeoScene Portal",
            "updatedAt": int(time.time()),
        }
        result["publicationProgress"] = exported_event
        result.setdefault("publicationTimeline", []).append(dict(exported_event))
        _write_cityengine_result(job_id, result)
        worker = threading.Thread(
            target=_publish_cityengine_result,
            args=(job_id,),
            name=f"geoscene-publish-{job_id}",
            daemon=True,
        )
        _publication_threads[job_id] = worker
        worker.start()
    return read_cityengine_job(job_id)


def ensure_cityengine_published(job_id, result):
    if result.get("status") != "completed":
        return result
    outputs = result.get("outputs") or {}
    if result.get("sceneServiceUrl"):
        publication = result.get("publication") or {}
        if not publication.get("sharedWithEveryone") or not publication.get("itemDetails"):
            try:
                result["publication"] = inspect_publication(share_publication(publication))
                result["sceneServiceUrl"] = result["publication"].get("sceneServiceUrl", result.get("sceneServiceUrl"))
                _write_cityengine_result(job_id, result)
            except Exception as exc:
                result["publicationShareError"] = str(exc)
        return result
    if not outputs.get("slpk"):
        return result
    return _start_cityengine_publication(job_id, result)
