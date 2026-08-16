# -*- coding: utf-8 -*-
"""Service layer: orchestrates model + adapter to fulfill analysis use-cases.

No FastAPI / route definitions here. These functions take plain payloads and
return plain dicts (or raise ValueError on bad input), so they are directly
unit-testable and reusable from the router.
"""
import json
import heapq
import math
import hashlib
import urllib.request
import urllib.error
import os
import time
import traceback
import zipfile
from datetime import date as calendar_date
from pathlib import Path

from cityengine_bridge import submit_planning_job

from . import adapter, model

pd = adapter.pd
gpd = adapter.gpd


# Public operation registry for dynamic analysis plans. The plan may select an
# operation and provide JSON parameters, but never supplies executable code.
SPATIAL_OPERATIONS = {
    "buffer": "buffer",
    "fetch_buildings": "fetch_buildings",
    "urban_metrics": "urban_metrics",
    "skyline": "skyline",
    "sunlight": "sunlight",
    "flood": "flood",
    "site_selection": "site_selection",
    "nearest_facility_distance": "nearest_facility_distance",
}


def _download_dem_tile(url, destination, attempts=3):
    """Download one public DEM tile directly, without proxy, and reject partial files."""
    destination = Path(destination)
    temporary = destination.with_suffix(destination.suffix + ".part")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    headers = {"User-Agent": "GISAgent/1.0", "Accept": "*/*", "Connection": "close"}
    last_error = None
    for attempt in range(1, attempts + 1):
        try:
            if temporary.exists():
                temporary.unlink()
            request = urllib.request.Request(url, headers=headers)
            with opener.open(request, timeout=45) as response, temporary.open("wb") as stream:
                expected = response.headers.get("Content-Length")
                expected_size = int(expected) if expected and expected.isdigit() else None
                copied = 0
                while True:
                    chunk = response.read(1024 * 1024)
                    if not chunk:
                        break
                    stream.write(chunk)
                    copied += len(chunk)
            if expected_size is not None and copied != expected_size:
                raise IOError(f"DEM tile truncated: received {copied} of {expected_size} bytes")
            temporary.replace(destination)
            return
        except Exception as exc:
            last_error = exc
            if temporary.exists():
                temporary.unlink()
            if attempt < attempts:
                time.sleep(attempt)
    raise IOError(f"direct DEM download failed after {attempts} attempts: {last_error}")


def fetch_public_dem_raster(aoi):
    """Download, mosaic and crop public GeoTIFF elevation tiles to a WGS84 AOI."""
    features = model._features_from_source(aoi)
    if not features:
        return {"status": "NoData", "message": "获取 DEM 栅格需要 AOI 范围。"}
    geometry = features[0].get("geometry") or {}
    coordinates = []
    def visit(value):
        if isinstance(value, (list, tuple)) and len(value) >= 2 and isinstance(value[0], (int, float)):
            coordinates.append((float(value[0]), float(value[1])))
        elif isinstance(value, (list, tuple)):
            for item in value: visit(item)
    visit(geometry.get("coordinates"))
    if len(coordinates) < 3:
        return {"status": "NoData", "message": "AOI 几何没有可用坐标。"}
    xmin, xmax = min(x for x, _ in coordinates), max(x for x, _ in coordinates)
    ymin, ymax = min(y for _, y in coordinates), max(y for _, y in coordinates)
    if xmin < -180 or xmax > 180 or ymin < -85 or ymax > 85:
        return {"status": "InvalidData", "message": "公共 DEM 瓦片源仅接受 WGS84 坐标的 AOI。"}
    def tile_xy(lon, lat, zoom):
        n = 2 ** zoom
        x = int((lon + 180.0) / 360.0 * n)
        lat_rad = math.radians(max(-85.05112878, min(85.05112878, lat)))
        y = int((1.0 - math.asinh(math.tan(lat_rad)) / math.pi) / 2.0 * n)
        return max(0, min(n - 1, x)), max(0, min(n - 1, y))
    zoom, tiles = 9, []
    # zoom 13 瓦片(4096²) merge/mask 耗时长，限到 12（~19m/px）换取更快完成。
    for candidate in range(12, 7, -1):
        left, top = tile_xy(xmin, ymax, candidate); right, bottom = tile_xy(xmax, ymin, candidate)
        proposed = [(candidate, x, y) for x in range(left, right + 1) for y in range(top, bottom + 1)]
        if len(proposed) <= 36:
            zoom, tiles = candidate, proposed
            break
    if not tiles:
        return {"status": "NoData", "message": "AOI 超出公共 DEM 瓦片请求的大小限制。"}
    try:
        import rasterio
        from rasterio.merge import merge
        from rasterio.mask import mask
        from rasterio.warp import transform_geom
    except ImportError:
        return {"status": "Unavailable", "message": "GIS 运行时没有可用的 Rasterio。"}
    root = Path(os.getenv("GIS_RASTER_ROOT", Path.cwd() / "cityengine-workspace" / "gis-inputs")).resolve()
    work = root / "public-dem"; work.mkdir(parents=True, exist_ok=True)
    tile_paths = []
    try:
        # 瓦片并行下载（每个 ~几秒；串行会让多瓦片 AOI 等很久）
        from concurrent.futures import ThreadPoolExecutor
        pending = []
        with ThreadPoolExecutor(max_workers=min(8, len(tiles))) as pool:
            for _, x, y in tiles:
                path = work / f"terrain-z{zoom}-{x}-{y}.tif"
                if not path.exists():
                    pending.append(pool.submit(
                        _download_dem_tile,
                        f"https://s3.amazonaws.com/elevation-tiles-prod/geotiff/{zoom}/{x}/{y}.tif",
                        path,
                    ))
                tile_paths.append(path)
            for future in pending:
                future.result()  # 传播下载异常，保持失败语义
        datasets = [rasterio.open(path) for path in tile_paths]
        mosaic, transform = merge(datasets)
        meta = datasets[0].meta.copy()
        for dataset in datasets: dataset.close()
        mosaic_path = work / (hashlib.sha256(str((xmin, ymin, xmax, ymax, zoom)).encode()).hexdigest()[:16] + ".tif")
        meta.update(driver="GTiff", height=mosaic.shape[1], width=mosaic.shape[2], transform=transform, count=1)
        with rasterio.open(mosaic_path, "w", **meta) as destination:
            destination.write(mosaic)
        with rasterio.open(mosaic_path) as source:
            projected = transform_geom("EPSG:4326", source.crs, geometry)
            cropped, cropped_transform = mask(source, [projected], crop=True)
            out_meta = source.meta.copy()
            out_meta.update(height=cropped.shape[1], width=cropped.shape[2], transform=cropped_transform)
        output = work / ("aoi-dem-" + hashlib.sha256(str((coordinates, zoom)).encode()).hexdigest()[:16] + ".tif")
        with rasterio.open(output, "w", **out_meta) as destination:
            destination.write(cropped)
        resolution_m = abs(out_meta["transform"].a)
        return {"status": "Success", "path": str(output), "source": "aws_elevation_tiles_srtm_gmted",
                "zoom": zoom, "tileCount": len(tiles), "resolutionMeters": round(resolution_m, 2),
                "width": out_meta["width"], "height": out_meta["height"], "crs": str(out_meta["crs"])}
    except Exception as exc:
        return {"status": "Error", "message": f"公共 DEM 获取失败：{exc}"}


def inspect_spatial_file(payload):
    """Read uploaded vector/raster metadata and normalize vector features to WGS84.

    Files are accepted only from the Java upload root.  This endpoint never
    accepts arbitrary filesystem paths from clients.
    """
    payload = payload or {}
    root = Path(os.getenv("GIS_RASTER_ROOT", Path.cwd() / "cityengine-workspace" / "gis-inputs")).resolve()
    path = Path(str(payload.get("path") or "")).resolve()
    extension = str(payload.get("extension") or path.suffix.lstrip(".")).lower()
    declared_crs = str(payload.get("sourceCrs") or "").strip()
    if root not in path.parents or not path.is_file():
        raise ValueError("Uploaded spatial file is outside the managed data root")
    if extension in {"asc", "tif", "tiff"}:
        return _inspect_raster_file(path, extension, declared_crs)
    if extension in {"geojson", "json", "gpkg", "zip", "shp"}:
        return _inspect_vector_file(path, extension, declared_crs)
    raise ValueError(f"Unsupported spatial file format: {extension}")


def _inspect_vector_file(path, extension, declared_crs):
    if extension in {"geojson", "json"}:
        collection = json.loads(path.read_text(encoding="utf-8-sig"))
        return _normalize_geojson_collection(collection, declared_crs, source_format=extension)
    if gpd is None:
        raise ValueError("Vector reader is unavailable. Install geopandas/pyogrio to import SHP or GeoPackage.")
    source = path
    if extension == "zip":
        source = _safe_extract_shapefile_zip(path)
    try:
        frame = gpd.read_file(source)
    except Exception as error:
        raise ValueError(f"Unable to read vector data: {error}") from error
    if frame.empty:
        raise ValueError("Vector file contains no features")
    if len(frame.index) > 50000:
        raise ValueError("Vector file exceeds the 50,000 feature import limit")
    if frame.crs is None:
        if not declared_crs:
            raise ValueError("Vector CRS is missing. Provide sourceCrs before importing this dataset.")
        frame = frame.set_crs(declared_crs, allow_override=True)
    source_crs = str(frame.crs)
    try:
        normalized = frame.to_crs("EPSG:4326")
    except Exception as error:
        raise ValueError(f"Unable to normalize vector CRS to EPSG:4326: {error}") from error
    geojson = json.loads(normalized.to_json(drop_id=True))
    if len(json.dumps(geojson, ensure_ascii=False).encode("utf-8")) > 25 * 1024 * 1024:
        raise ValueError("Normalized GeoJSON exceeds the 25 MB context limit")
    geometry_types = sorted({str(value) for value in normalized.geom_type.dropna().unique()})
    minx, miny, maxx, maxy = normalized.total_bounds
    return {
        "dataType": "vector", "sourceFormat": "shapefile_zip" if extension == "zip" else extension,
        "sourceCrs": source_crs, "normalizedCrs": "EPSG:4326", "metadataStatus": "ready",
        "featureCount": int(len(normalized.index)), "geometryTypes": geometry_types,
        "bbox": [_round(minx), _round(miny), _round(maxx), _round(maxy)],
        "normalizedAt": _now_iso(), "geoJson": geojson,
    }


def _safe_extract_shapefile_zip(path):
    output = path.parent / (path.stem + "-unpacked")
    output.mkdir(exist_ok=True)
    try:
        with zipfile.ZipFile(path) as archive:
            shp_members = [item for item in archive.infolist() if item.filename.lower().endswith(".shp")]
            if len(shp_members) != 1:
                raise ValueError("SHP archive must contain exactly one .shp file")
            for item in archive.infolist():
                if item.is_dir():
                    continue
                target = (output / Path(item.filename).name).resolve()
                if output.resolve() not in target.parents:
                    raise ValueError("SHP archive contains an unsafe path")
                if item.file_size > 50 * 1024 * 1024:
                    raise ValueError("SHP archive member is too large")
                with archive.open(item) as source, target.open("wb") as destination:
                    destination.write(source.read())
        candidate = output / Path(shp_members[0].filename).name
        if not candidate.is_file():
            raise ValueError("SHP archive did not produce a readable .shp file")
        return candidate
    except zipfile.BadZipFile as error:
        raise ValueError("Invalid SHP zip archive") from error


def _normalize_geojson_collection(collection, declared_crs, source_format):
    if not isinstance(collection, dict) or not collection.get("type"):
        raise ValueError("Vector file must contain a GeoJSON object")
    if gpd is None:
        # GeoJSON produced in WGS84 can still be accepted without optional GIS
        # dependencies.  Java performs the coordinate bounds validation.
        return {"dataType": "vector", "sourceFormat": source_format, "sourceCrs": declared_crs or "EPSG:4326",
                "normalizedCrs": "EPSG:4326", "metadataStatus": "ready", "geoJson": collection,
                "normalizedAt": _now_iso()}
    try:
        frame = gpd.GeoDataFrame.from_features(collection.get("features", [collection]), crs=declared_crs or "EPSG:4326")
    except Exception as error:
        raise ValueError(f"Unable to read GeoJSON: {error}") from error
    if frame.empty:
        raise ValueError("Vector file contains no features")
    normalized = frame.to_crs("EPSG:4326")
    minx, miny, maxx, maxy = normalized.total_bounds
    return {"dataType": "vector", "sourceFormat": source_format, "sourceCrs": str(frame.crs),
            "normalizedCrs": "EPSG:4326", "metadataStatus": "ready", "featureCount": int(len(normalized.index)),
            "geometryTypes": sorted({str(value) for value in normalized.geom_type.dropna().unique()}),
            "bbox": [_round(minx), _round(miny), _round(maxx), _round(maxy)], "normalizedAt": _now_iso(),
            "geoJson": json.loads(normalized.to_json(drop_id=True))}


def _inspect_raster_file(path, extension, declared_crs):
    if extension == "asc":
        grid = _ascii_grid(path)
        if grid is None:
            raise ValueError("Unable to read ASCII Grid metadata")
        return {"dataType": "raster", "sourceFormat": "asc", "sourceCrs": declared_crs or "EPSG:4326",
                "normalizedCrs": "EPSG:4326", "metadataStatus": "ready", "width": grid["columns"],
                "height": grid["rows"], "resolution": [grid["dx"], grid["dy"]],
                "bbox": [grid["x0"], grid["y0"], grid["x0"] + grid["columns"] * grid["dx"], grid["y0"] + grid["rows"] * grid["dy"]],
                "normalizedAt": _now_iso(), "gridPolicy": "native_grid_preserved"}
    try:
        import rasterio
        from rasterio.warp import transform_bounds
        with rasterio.open(path) as dataset:
            source_crs = str(dataset.crs) if dataset.crs else declared_crs
            if not source_crs:
                raise ValueError("Raster CRS is missing. Provide sourceCrs before importing this DEM.")
            if str(source_crs).upper() == "EPSG:4326":
                left, bottom, right, top = dataset.bounds
            else:
                left, bottom, right, top = transform_bounds(source_crs, "EPSG:4326", *dataset.bounds, densify_pts=21)
            return {"dataType": "raster", "sourceFormat": extension, "sourceCrs": source_crs,
                    "normalizedCrs": "EPSG:4326", "metadataStatus": "ready", "width": dataset.width,
                    "height": dataset.height, "bandCount": dataset.count, "resolution": [abs(dataset.transform.a), abs(dataset.transform.e)],
                    "bbox": [_round(left), _round(bottom), _round(right), _round(top)],
                    "noData": dataset.nodata, "normalizedAt": _now_iso(), "gridPolicy": "native_grid_preserved"}
    except ImportError as error:
        raise ValueError("GeoTIFF reader is unavailable. Install rasterio.") from error
    except Exception as error:
        raise ValueError(f"Unable to read GeoTIFF metadata: {error}") from error


def _round(value):
    return round(float(value), 6)


def _now_iso():
    from datetime import datetime, timezone
    return datetime.now(timezone.utc).isoformat()


def execute_spatial_plan(payload):
    """Execute one validated spatial operation from a declarative plan."""
    payload = payload or {}
    operation = str(payload.get("operation") or "").strip().lower()
    if operation not in SPATIAL_OPERATIONS:
        return {
            "status": "Error",
            "stage": "spatial_plan",
            "message": f"Unsupported spatial operation: {operation}",
            "allowed_operations": sorted(SPATIAL_OPERATIONS),
        }
    args = payload.get("params") if isinstance(payload.get("params"), dict) else payload
    try:
        if operation == "buffer":
            feature = create_buffer_feature(args.get("lon"), args.get("lat"), args.get("radius", 500))
            return {
                "status": "Success", "analysis_type": "buffer",
                "geojson": model._feature_collection([feature]),
                "commands": [{"action": "renderAnalysisResult", "params": {
                    "geoJson": model._feature_collection([feature]),
                }}],
            }
        if operation == "fetch_buildings":
            return fetch_buildings_for_aoi(args.get("aoi"))
        if operation == "urban_metrics":
            return calculate_metrics(args)
        if operation == "skyline":
            return calculate_skyline(args)
        if operation == "sunlight":
            return calculate_sunlight(args)
        if operation == "flood":
            return calculate_flood_risk(args)
        if operation == "site_selection":
            return calculate_site_selection(args)
        if operation == "nearest_facility_distance":
            return calculate_nearest_facility_distance(args)
    except Exception as exc:
        return {"status": "Error", "stage": "spatial_plan", "message": str(exc)}
    return {"status": "Error", "stage": "spatial_plan", "message": "空间规划操作未产生结果"}


def calculate_nearest_facility_distance(payload):
    """Find the nearest facility for each candidate using straight-line metres.

    Geometry is normalized through the existing model helpers; distance and
    bearing use the same geodesic approximation as site-selection screening.
    """
    payload = payload or {}
    candidates = model._features_from_source(payload.get("candidates"))
    facilities = model._features_from_source(payload.get("facilities"))
    missing = []
    if not candidates:
        missing.append("candidates")
    if not facilities:
        missing.append("facilities")
    if missing:
        return {"status": "NoData", "stage": "nearest_facility_distance",
                "analysis_type": "nearest_facility_distance",
                "missing_data": missing,
                "message": "需要候选点/面与设施点/面要素。"}

    facility_rows = []
    for index, feature in enumerate(facilities, start=1):
        center = model._feature_centroid(feature)
        if center:
            props = dict(feature.get("properties") or {})
            facility_rows.append((center, props.get("id") or props.get("name") or f"Facility {index}", props))
    if not facility_rows:
        return {"status": "InvalidData", "stage": "nearest_facility_distance",
                "analysis_type": "nearest_facility_distance",
                "message": "没有设施要素包含可用几何。"}

    rows = []
    for index, candidate in enumerate(candidates, start=1):
        center = model._feature_centroid(candidate)
        if not center:
            continue
        distance, bearing, facility_id, facility_props = min(
            (model._distance_and_bearing(center, target[0])[0],
             model._distance_and_bearing(center, target[0])[1], target[1], target[2])
            for target in facility_rows
        )
        props = dict(candidate.get("properties") or {})
        props.update({
            "candidateId": props.get("id") or props.get("name") or f"Candidate {index}",
            "nearestFacilityId": facility_id,
            "nearestFacilityName": facility_props.get("name") or facility_id,
            "nearestFacilityDistanceM": round(distance, 2),
            "nearestFacilityBearingDeg": round(bearing, 1),
        })
        rows.append({"type": "Feature", "properties": props, "geometry": candidate.get("geometry")})
    if not rows:
        return {"status": "NoData", "stage": "nearest_facility_distance",
                "analysis_type": "nearest_facility_distance",
                "message": "没有候选要素包含可用几何。"}

    distances = [item["properties"]["nearestFacilityDistanceM"] for item in rows]
    result_features = model._feature_collection(rows)
    return {
        "status": "Success", "stage": "nearest_facility_distance",
        "analysis_type": "nearest_facility_distance",
        "candidate_count": len(rows), "facility_count": len(facility_rows),
        "min_distance_m": min(distances), "max_distance_m": max(distances),
        "average_distance_m": round(sum(distances) / len(distances), 2),
        "nearest_features": result_features,
        "method": "geodesic_straight_line_centroid_distance",
        "limitations": "仅按质心直线距离计算；路网出行距离与设施容量需以权威网络数据复核。",
        "commands": [
            {"action": "addGeoJsonLayer", "params": {"layerId": "nearest-facility-distance", "title": "最近设施距离", "style": "nearestFacility", "visible": True, "data": result_features}},
            {"action": "showAdvancedAnalysis", "params": {"analysisType": "nearest_facility_distance", "title": "最近设施距离", "candidateCount": len(rows), "facilityCount": len(facility_rows), "minDistanceM": min(distances), "averageDistanceM": round(sum(distances) / len(distances), 2), "limitations": "仅按质心直线距离计算。"}},
        ],
    }


# --------------------------------------------------------------------------- #
# Buffer creation (backend selection)
# --------------------------------------------------------------------------- #
def create_buffer_feature(lon, lat, radius):
    lon = model._safe_float(lon)
    lat = model._safe_float(lat)
    radius = model._safe_float(radius, 500)
    if lon is None or lat is None:
        raise ValueError("missing lon/lat")
    if abs(lon) < 0.1 and abs(lat) < 0.1:
        raise ValueError("invalid coordinate (0,0)")

    errors = []
    if adapter.HAS_ARCPY:
        try:
            return adapter._create_buffer_feature_arcpy(lon, lat, radius)
        except Exception as exc:
            errors.append(f"geoscene_arcpy: {exc}")
            print(f"ArcPy buffer failed, falling back: {exc}")

    if adapter.HAS_OPEN_SOURCE:
        try:
            feature = adapter._create_buffer_feature_open_source(lon, lat, radius)
            if errors:
                feature["properties"]["fallback_errors"] = errors
            return feature
        except Exception as exc:
            errors.append(f"open_source_geopandas: {exc}")
            print(f"GeoPandas buffer failed, falling back: {exc}")

    feature = model._create_buffer_feature_approx(lon, lat, radius)
    if errors:
        feature["properties"]["fallback_errors"] = errors
    return feature


# --------------------------------------------------------------------------- #
# OSM building fetch + clip
# --------------------------------------------------------------------------- #
def fetch_buildings_for_aoi(aoi_geojson):
    started = time.time()
    bbox = model._bounds_from_aoi(aoi_geojson)
    query = model._overpass_query(bbox)

    raw = None
    try:
        raw = adapter._call_overpass(query)
    except Exception as exc:
        # Overpass unreachable (offline demo / proxy / overloaded node) is
        # treated as "no external data" so the synthetic fallback can kick in.
        raw = {"elements": [], "overpass_error": str(exc)[:200]}
    raw_features = model._elements_to_features(raw.get("elements", []))
    if not raw_features:
        synthetic = model._synthetic_buildings(aoi_geojson)
        if synthetic:
            overpass_error = raw.get("overpass_error")
            reason = "OSM Overpass 不可达" if overpass_error else "OSM Overpass 未返回建筑数据"
            return {
                "status": "Success",
                "stage": "fetch_buildings",
                "building_count": int(len(synthetic["features"])),
                "buildings": synthetic,
                "aoi": aoi_geojson,
                "source": "synthetic",
                "message": reason + "，已生成合成建筑（source=synthetic，仅供演示，不代表真实测绘成果）。",
                "query_bbox": bbox,
                "gis_backend": adapter._preferred_backend(),
                "elapsed_ms": round((time.time() - started) * 1000),
            }
        return {
            "status": "NoData",
            "stage": "fetch_buildings",
            "building_count": 0,
            "message": "Overpass 未返回该 AOI 范围内的建筑轮廓。",
            "query_bbox": bbox,
            "gis_backend": adapter._preferred_backend(),
            "elapsed_ms": round((time.time() - started) * 1000),
        }

    clipped_features = None
    clip_backend = None
    fallback_errors = []

    if adapter.HAS_ARCPY:
        try:
            clipped_features = adapter._clip_features_arcpy(raw_features, aoi_geojson)
            clip_backend = "geoscene_arcpy"
        except Exception as exc:
            fallback_errors.append(f"geoscene_arcpy clip: {exc}")
            print(f"ArcPy clip failed, falling back: {traceback.format_exc()}")

    if (clipped_features is None or not clipped_features) and adapter.HAS_OPEN_SOURCE:
        try:
            open_source_features = adapter._clip_features_open_source(raw_features, aoi_geojson)
            clipped_features = open_source_features
            clip_backend = "open_source_geopandas"
        except Exception as exc:
            fallback_errors.append(f"open_source_geopandas clip: {exc}")
            print(f"GeoPandas clip failed, falling back: {traceback.format_exc()}")

    if clipped_features is None:
        clipped_features = adapter._clip_features_bbox(raw_features, aoi_geojson)
        clip_backend = "standard_library_bbox"

    if not clipped_features:
        return {
            "status": "NoData",
            "stage": "fetch_buildings",
            "building_count": 0,
            "message": "已获取建筑数据，但裁剪后没有建筑与 AOI 相交。",
            "raw_count": len(raw_features),
            "query_bbox": bbox,
            "gis_backend": clip_backend or adapter._preferred_backend(),
            "fallback_errors": fallback_errors,
        }

    usable_features, rejected_footprints = model._filter_usable_building_footprints(clipped_features)
    if not usable_features:
        return {
            "status": "NoData",
            "stage": "fetch_buildings",
            "building_count": 0,
            "message": "AOI 仅与退化或极小的建筑碎片相交；不会使用不可靠轮廓计算指标或生成 CityEngine 模型。",
            "raw_count": len(raw_features),
            "clipped_count": len(clipped_features),
            "rejected_footprint_count": len(rejected_footprints),
            "rejected_footprints": rejected_footprints[:10],
            "query_bbox": bbox,
            "gis_backend": clip_backend or adapter._preferred_backend(),
            "fallback_errors": fallback_errors,
        }

    buildings_geojson = model._feature_collection(usable_features)
    result = {
        "status": "Success",
        "stage": "fetch_buildings",
        "building_count": int(len(usable_features)),
        "raw_count": int(len(raw_features)),
        "clipped_count": int(len(clipped_features)),
        "rejected_footprint_count": int(len(rejected_footprints)),
        "buildings": buildings_geojson,
        "aoi": aoi_geojson,
        "source": "openstreetmap_overpass",
        "gis_backend": clip_backend or adapter._preferred_backend(),
        "query_bbox": bbox,
        "elapsed_ms": round((time.time() - started) * 1000),
    }
    if fallback_errors:
        result["fallback_errors"] = fallback_errors
    return result


# --------------------------------------------------------------------------- #
# Urban metrics
# --------------------------------------------------------------------------- #
def _calculate_metrics_open_source(payload):
    if not adapter.HAS_OPEN_SOURCE:
        raise RuntimeError("GeoPandas/Shapely/Pandas are not available.")

    buildings_raw = payload.get("buildings")
    aoi_raw = payload.get("aoi")

    buildings = adapter.dict_to_gdf(buildings_raw)
    if buildings is None or buildings.empty:
        return {
            "status": "Fail",
            "stage": "urban_metrics",
            "far": 0,
            "building_count": 0,
            "message": "未提供有效的建筑多边形。",
            "gis_backend": "open_source_geopandas",
        }

    aoi = adapter.dict_to_gdf(aoi_raw) if aoi_raw else None
    metric = model._metric_crs_for_gdf(aoi if aoi is not None and not aoi.empty else buildings)
    buildings_m = buildings.to_crs(metric)

    buffer_area = 0.0
    if aoi is not None and not aoi.empty:
        aoi_m = aoi.to_crs(metric)
        buffer_area = float(aoi_m.geometry.area.sum())
        site_area = buffer_area
        final = gpd.clip(buildings_m, aoi_m)
    else:
        final = buildings_m
        try:
            site_area = float(final.unary_union.convex_hull.area) if len(final) > 1 else float(final.geometry.area.sum())
        except Exception:
            site_area = float(final.geometry.area.sum())

    final = final[final.geometry.notna() & ~final.geometry.is_empty]
    records = model._records_from_gdf(final)
    return model._build_metrics_result(records, site_area, buffer_area, "open_source_geopandas", metric_crs=metric)


def _calculate_metrics_geoscene_server(payload):
    """Urban metrics whose spatial math runs on the GeoScene Enterprise server.

    AOI area, AOI union and building-to-AOI clipping are delegated to the
    Enterprise Geometry Service (areasAndLengths / intersect / union); the
    aggregation formula stays in model._build_metrics_result. An intersecting
    building contributes its full footprint, mirroring the standard-library
    bbox-clip semantics, so results are identical across backends
    (determinism guarantee, verified by mock tests).
    """
    if not adapter.HAS_GEOSCENE_SERVER:
        raise RuntimeError(
            "GeoScene Enterprise is not configured. Set GEOSCENE_PORTAL_URL, "
            "GEOSCENE_PORTAL_USERNAME and GEOSCENE_PORTAL_PASSWORD."
        )

    buildings_raw = payload.get("buildings")
    aoi_raw = payload.get("aoi")
    building_features = model._features_from_source(buildings_raw)
    if not building_features:
        return {
            "status": "Fail",
            "stage": "urban_metrics",
            "far": 0,
            "building_count": 0,
            "message": "未提供有效的建筑多边形。",
            "gis_backend": "geoscene_server",
        }

    aoi_features = model._features_from_source(aoi_raw) if aoi_raw else []
    metric = model._metric_crs_for_features(aoi_features or building_features)

    site_area = 0.0
    aoi_parts = []
    for feature in aoi_features:
        geometry = feature.get("geometry") or {}
        site_area += adapter._server_area(geometry, metric)
        if geometry.get("type") == "MultiPolygon":
            aoi_parts.extend(geometry.get("coordinates") or [])
        else:
            aoi_parts.append(geometry)

    clip_geometry = None
    if len(aoi_parts) == 1:
        clip_geometry = aoi_parts[0]
    elif len(aoi_parts) > 1:
        merged = adapter._server_union(aoi_parts)
        clip_geometry = merged[0] if merged else None

    records = []
    for feature in building_features:
        geometry = feature.get("geometry") or {}
        footprint_area = 0.0
        if clip_geometry is not None:
            parts = adapter._server_intersect(geometry, clip_geometry)
            if not parts:
                continue
            footprint_area = adapter._server_area(geometry, metric)
        else:
            footprint_area = adapter._server_area(geometry, metric)
        if footprint_area <= 0:
            continue
        records.append({
            "id": _extract_building_id(feature),
            "properties": dict(feature.get("properties") or {}),
            "footprint_area": footprint_area,
        })

    return model._build_metrics_result(
        records, site_area=site_area, buffer_area=site_area,
        backend="geoscene_server",
        metric_crs=metric,
    )


def _calculate_metrics_arcpy(payload):
    if not adapter.HAS_ARCPY:
        raise RuntimeError("ArcPy is not available.")

    buildings_raw = payload.get("buildings")
    aoi_raw = payload.get("aoi")
    building_features = model._features_from_source(buildings_raw)
    if not building_features:
        return {
            "status": "Fail",
            "stage": "urban_metrics",
            "far": 0,
            "building_count": 0,
            "message": "未提供有效的建筑多边形。",
            "gis_backend": "geoscene_arcpy",
        }

    aoi_features = model._features_from_source(aoi_raw) if aoi_raw else []
    metric = model._metric_crs_for_features(aoi_features or building_features)
    building_records = adapter._arcpy_records(model._feature_collection(building_features), metric)
    if not building_records:
        return {
            "status": "Fail",
            "stage": "urban_metrics",
            "far": 0,
            "building_count": 0,
            "message": "未提供有效的建筑多边形。",
            "gis_backend": "geoscene_arcpy",
        }

    buffer_area = 0.0
    final_records = []
    if aoi_features:
        aoi_records = adapter._arcpy_records(model._feature_collection(aoi_features), metric)
        aoi_union = adapter._union_arcpy_geometries(aoi_records)
        site_area = adapter._arcpy_area(aoi_union)
        buffer_area = site_area
        for record in building_records:
            clipped = adapter._intersect_arcpy_polygon(record["geometry"], aoi_union)
            area = adapter._arcpy_area(clipped)
            if area <= 0:
                continue
            final_records.append({
                "properties": record["properties"],
                "footprint_area": area,
            })
    else:
        site_area = sum(adapter._arcpy_area(record["geometry"]) for record in building_records)
        for record in building_records:
            final_records.append({
                "properties": record["properties"],
                "footprint_area": adapter._arcpy_area(record["geometry"]),
            })

    return model._build_metrics_result(final_records, site_area, buffer_area, "geoscene_arcpy", metric_crs=metric)


def calculate_metrics(payload):
    fallback_errors = []

    if adapter.HAS_ARCPY:
        try:
            result = _calculate_metrics_arcpy(payload)
            if result.get("status") == "Success" or (not adapter.HAS_OPEN_SOURCE):
                return result
            fallback_errors.append(f"geoscene_arcpy returned {result.get('status')}: {result.get('message')}")
        except Exception as exc:
            fallback_errors.append(f"geoscene_arcpy: {exc}")
            print(f"ArcPy metrics failed, falling back: {traceback.format_exc()}")

    if adapter.HAS_OPEN_SOURCE:
        try:
            result = _calculate_metrics_open_source(payload)
            if result.get("status") == "Success":
                return result
            fallback_errors.append(f"open_source_geopandas returned {result.get('status')}: {result.get('message')}")
        except Exception as exc:
            fallback_errors.append(f"open_source_geopandas: {exc}")
            print(f"GeoPandas metrics failed, falling back: {traceback.format_exc()}")

    if adapter.HAS_GEOSCENE_SERVER:
        try:
            result = _calculate_metrics_geoscene_server(payload)
            if result.get("status") == "Success" or (not adapter.HAS_ARCPY and not adapter.HAS_OPEN_SOURCE):
                return result
            fallback_errors.append(f"geoscene_server returned {result.get('status')}: {result.get('message')}")
        except Exception as exc:
            fallback_errors.append(f"geoscene_server: {exc}")
            print(f"GeoScene server metrics failed, falling back: {traceback.format_exc()}")

    try:
        result = extract_urban_metrics(payload.get("aoi"), payload.get("buildings"))
        if fallback_errors:
            result["fallback_errors"] = fallback_errors
        return result
    except Exception as exc:
        fallback_errors.append(f"standard_library: {exc}")

    return {
        "status": "Fail",
        "stage": "urban_metrics",
        "far": 0,
        "building_count": 0,
        "message": "没有可用的 GIS 几何后端。请安装 geopandas/shapely/pandas，或在 GeoScene/ArcPy 解释器上运行本服务。",
        "gis_backend": adapter._preferred_backend(),
        "fallback_errors": fallback_errors,
        "runtime": adapter.runtime_status(),
    }


# --------------------------------------------------------------------------- #
# Offline / deterministic urban metrics (pure standard library)
# --------------------------------------------------------------------------- #
def extract_urban_metrics(aoi, buildings=None, include_records=False):
    """Compute urban metrics with the standard library only.

    Deterministic, offline path for the sealed-offline demo and for
    tests/test_offline_demo_metrics.py. It never calls Overpass/ArcGIS and
    never requires arcpy/geopandas/shapely: geometry math is limited to
    model._ring_area_sqm (WGS84 approximate metre projection), bbox clipping
    (adapter._clip_features_bbox, itself pure) and the pure aggregator
    model._build_metrics_result. The returned structure is identical to the
    backend paths of calculate_metrics, and the estimation heuristics for
    missing levels/heights are the same model functions.

    Semantics mirror calculate_metrics:
    - aoi defines the site; exterior-ring areas are summed in square metres.
    - buildings are bbox-clipped against the AOI; an intersecting building
      contributes its full footprint area (same rule as the standard-library
      fallback adapter._clip_features_bbox).
    - buildings=None or an empty collection is tolerated -> NoData result.
    - unparseable input raises ValueError so the router can map it to an error.

    When include_records=True, the returned dict carries a "building_records"
    key (per-building records used to derive the metrics) so callers can cache
    them and reuse them for incremental recomputation.
    """
    if aoi is None:
        raise ValueError("AOI is required to compute urban metrics")

    aoi_features = model._features_from_source(aoi)
    if not aoi_features:
        raise ValueError("AOI has no usable geometry")

    site_area = 0.0
    for feature in aoi_features:
        geometry = feature.get("geometry") or {}
        if geometry.get("type") == "Polygon":
            rings = geometry.get("coordinates") or []
            if rings:
                site_area += model._ring_area_sqm(rings[0])
        elif geometry.get("type") == "MultiPolygon":
            for polygon in geometry.get("coordinates") or []:
                if polygon:
                    site_area += model._ring_area_sqm(polygon[0])

    if buildings is not None:
        try:
            features = model._features_from_source(buildings)
        except Exception as exc:
            raise ValueError(f"buildings GeoJSON is not parseable: {exc}") from exc
    else:
        features = []

    if not features:
        return model._build_metrics_result(
            [], site_area=site_area, buffer_area=site_area,
            backend="standard_library_metrics",
            metric_crs=model._metric_crs_for_features(aoi_features),
        )

    clipped = adapter._clip_features_bbox(features, aoi)
    records = []
    for feature in clipped:
        geometry = feature.get("geometry") or {}
        footprint_area = 0.0
        if geometry.get("type") == "Polygon":
            rings = geometry.get("coordinates") or []
            if rings:
                footprint_area = model._ring_area_sqm(rings[0])
        elif geometry.get("type") == "MultiPolygon":
            for polygon in geometry.get("coordinates") or []:
                if polygon:
                    footprint_area += model._ring_area_sqm(polygon[0])
        if footprint_area <= 0:
            continue
        records.append({
            "id": _extract_building_id(feature),
            "properties": dict(feature.get("properties") or {}),
            "footprint_area": footprint_area,
        })

    result = model._build_metrics_result(
        records, site_area=site_area, buffer_area=site_area,
        backend="standard_library_metrics",
        metric_crs=model._metric_crs_for_features(aoi_features),
    )
    if include_records:
        result["building_records"] = records
    return result


# --------------------------------------------------------------------------- #
# Incremental analysis
# --------------------------------------------------------------------------- #
def _geometry_hash(geom):
    """Compute a stable hash for a geometry dict.

    Uses SHA-256 over the JSON-serialized geometry with sorted keys so that
    geometrically identical inputs produce the same hash regardless of key
    ordering or serialization noise.
    """
    normalized = json.dumps(geom, sort_keys=True, ensure_ascii=False)
    return hashlib.sha256(normalized.encode("utf-8")).hexdigest()


def _extract_building_id(feature):
    """Extract the stable identifier from a building feature."""
    props = (feature or {}).get("properties") or {}
    return model._building_identifier(props, 0)


def _diff_buildings(old_list, new_list):
    """Compare two building feature lists by id.

    Returns {"added": [], "removed": [], "modified": []} where each entry
    is a building feature. A building is "modified" if its id exists in both
    lists but its geometry or properties differ.
    """
    old_by_id = {}
    for feature in old_list:
        bid = _extract_building_id(feature)
        old_by_id[bid] = feature

    new_by_id = {}
    for feature in new_list:
        bid = _extract_building_id(feature)
        new_by_id[bid] = feature

    added = []
    removed = []
    modified = []

    for bid, feature in new_by_id.items():
        if bid not in old_by_id:
            added.append(feature)
        elif json.dumps(feature, sort_keys=True, ensure_ascii=False) != json.dumps(
            old_by_id[bid], sort_keys=True, ensure_ascii=False
        ):
            modified.append(feature)

    for bid, feature in old_by_id.items():
        if bid not in new_by_id:
            removed.append(feature)

    return {"added": added, "removed": removed, "modified": modified}


def _compute_building_records(buildings, aoi):
    """Compute metric records for a set of buildings against an AOI.

    Reuses the same clipping + area logic as extract_urban_metrics so
    incremental records are consistent with full computation.
    """
    if not buildings:
        return []
    features = model._features_from_source(buildings)
    clipped = adapter._clip_features_bbox(features, aoi)
    records = []
    for feature in clipped:
        geometry = feature.get("geometry") or {}
        footprint_area = 0.0
        if geometry.get("type") == "Polygon":
            rings = geometry.get("coordinates") or []
            if rings:
                footprint_area = model._ring_area_sqm(rings[0])
        elif geometry.get("type") == "MultiPolygon":
            for polygon in geometry.get("coordinates") or []:
                if polygon:
                    footprint_area += model._ring_area_sqm(polygon[0])
        if footprint_area <= 0:
            continue
        records.append({
            "id": _extract_building_id(feature),
            "properties": dict(feature.get("properties") or {}),
            "footprint_area": footprint_area,
        })
    return records


def _merge_delta_metrics(prev_buildings, curr_buildings, aoi, cached_records=None):
    """Merge prior building records with only the delta-building records.

    cached_records (from a previous extract_urban_metrics/compute_delta_metrics
    call with the same AOI) lets unchanged buildings be reused without
    recomputation. Only added + modified buildings go through
    _compute_building_records. Records whose id is missing from the cache are
    recomputed as a correctness fallback.

    Returns (metrics, building_records) so the caller can persist the merged
    records for the next incremental call.
    """
    aoi_features = model._features_from_source(aoi)
    if not aoi_features:
        raise ValueError("AOI has no usable geometry")

    site_area = 0.0
    for feature in aoi_features:
        geometry = feature.get("geometry") or {}
        if geometry.get("type") == "Polygon":
            rings = geometry.get("coordinates") or []
            if rings:
                site_area += model._ring_area_sqm(rings[0])
        elif geometry.get("type") == "MultiPolygon":
            for polygon in geometry.get("coordinates") or []:
                if polygon:
                    site_area += model._ring_area_sqm(polygon[0])

    prev_features = model._features_from_source(prev_buildings) if prev_buildings else []
    curr_features = model._features_from_source(curr_buildings) if curr_buildings else []
    delta = _diff_buildings(prev_features, curr_features)

    changed_ids = {_extract_building_id(x) for x in delta["removed"]}
    changed_ids |= {_extract_building_id(x) for x in delta["modified"]}

    unchanged_features = [
        f for f in prev_features if _extract_building_id(f) not in changed_ids
    ]

    reused_count = 0
    if cached_records:
        cache_by_id = {
            r.get("id"): r for r in cached_records if r.get("id") is not None
        }
        wanted = {_extract_building_id(f) for f in unchanged_features}
        hit_ids = wanted & set(cache_by_id)
        unchanged_records = [cache_by_id[i] for i in hit_ids]
        reused_count = len(unchanged_records)
        missing_ids = wanted - hit_ids
        if missing_ids:
            missing_features = [
                f for f in unchanged_features if _extract_building_id(f) in missing_ids
            ]
            unchanged_records += _compute_building_records(
                {"type": "FeatureCollection", "features": missing_features}, aoi
            )
    else:
        unchanged_records = _compute_building_records(
            {"type": "FeatureCollection", "features": unchanged_features}, aoi
        )

    added_modified = delta["added"] + delta["modified"]
    delta_records = _compute_building_records(
        {"type": "FeatureCollection", "features": added_modified}, aoi
    )

    merged_records = unchanged_records + delta_records

    metrics = model._build_metrics_result(
        merged_records, site_area=site_area, buffer_area=site_area,
        backend="standard_library_metrics",
        metric_crs=model._metric_crs_for_features(aoi_features),
    )
    metrics["building_records"] = merged_records
    metrics["incremental_reused"] = reused_count
    metrics["incremental_computed"] = len(delta_records)
    return metrics, merged_records


def compute_delta_metrics(previous_state, current_state):
    """Compute urban metrics incrementally based on AOI/building changes.

    previous_state / current_state structure:
        {
            "aoi": {...},         # GeoJSON geometry or FeatureCollection
            "buildings": {...},   # GeoJSON FeatureCollection
            "metrics": {...},     # previous extract_urban_metrics output
            "building_records": [...],  # optional; enables true incremental reuse
        }

    The returned result carries a "building_records" key; the caller is
    expected to persist it back into its state so the next incremental call
    reuses unchanged buildings instead of recomputing them.

    Returns:
        {
            "status": "incremental" | "full",
            "metrics": {...},
            "delta": {"added": [...], "removed": [...], "modified": [...]},
            "computationSaved": "86%",     # fraction of records reused, not fabricated
            "building_records": [...],      # persist into state for next call
        }
    """
    if previous_state is None:
        metrics = extract_urban_metrics(
            current_state.get("aoi"), current_state.get("buildings"),
            include_records=True,
        )
        return {
            "status": "full",
            "metrics": metrics,
            "delta": {"added": [], "removed": [], "modified": []},
            "computationSaved": "0%",
            "building_records": metrics.get("building_records", []),
        }

    prev_aoi = previous_state.get("aoi") or {}
    curr_aoi = current_state.get("aoi") or {}
    prev_buildings = previous_state.get("buildings") or {}
    curr_buildings = current_state.get("buildings") or {}

    prev_aoi_geom = prev_aoi.get("geometry", prev_aoi)
    curr_aoi_geom = curr_aoi.get("geometry", curr_aoi)
    aoi_changed = _geometry_hash(prev_aoi_geom) != _geometry_hash(curr_aoi_geom)

    prev_features = model._features_from_source(prev_buildings)
    curr_features = model._features_from_source(curr_buildings)
    delta = _diff_buildings(prev_features, curr_features)

    changed_count = len(delta["added"]) + len(delta["removed"]) + len(delta["modified"])

    if aoi_changed:
        metrics = extract_urban_metrics(curr_aoi, curr_buildings, include_records=True)
        return {
            "status": "full",
            "metrics": metrics,
            "delta": delta,
            "computationSaved": "0%",
            "building_records": metrics.get("building_records", []),
        }

    if changed_count == 0:
        prev_metrics = previous_state.get("metrics", {})
        records = previous_state.get("building_records") or prev_metrics.get("building_records") or []
        return {
            "status": "incremental",
            "metrics": prev_metrics,
            "delta": delta,
            "computationSaved": "100%",
            "building_records": records,
        }

    cached = previous_state.get("building_records")
    merged_metrics, merged_records = _merge_delta_metrics(
        prev_buildings, curr_buildings, curr_aoi, cached
    )

    total_records = len(merged_records) or 1
    reused = len(merged_records) - merged_metrics.get("incremental_computed", 0)
    saved_pct = round(reused / total_records * 100)

    return {
        "status": "incremental",
        "metrics": merged_metrics,
        "delta": delta,
        "computationSaved": f"{saved_pct}%",
        "building_records": merged_records,
    }


# --------------------------------------------------------------------------- #
# Skyline / sunlight analysis
# --------------------------------------------------------------------------- #
def _site_selection_weights(raw_weights):
    raw_weights = raw_weights if isinstance(raw_weights, dict) else {}
    defaults = {"access": 0.6, "avoidance": 0.4}
    weights = {}
    for name, default in defaults.items():
        value = model._safe_float(raw_weights.get(name), default)
        if value is None or value < 0:
            raise ValueError(f"weights.{name} must be a non-negative number")
        weights[name] = value
    total = sum(weights.values())
    if total <= 0:
        raise ValueError("At least one site-selection weight must be greater than zero")
    return {name: value / total for name, value in weights.items()}


def calculate_site_selection(payload):
    """Rank candidate sites using transparent proximity and avoidance criteria.

    This is deliberately a screening tool: it does not infer land ownership,
    zoning, terrain, environmental approval, road-network travel time, or
    infrastructure capacity from absent data.
    """
    payload = payload or {}
    candidates = model._features_from_source(payload.get("candidates"))
    if not candidates:
        return {
            "status": "NoData", "stage": "site_selection", "analysis_type": "site_selection",
            "missing_data": ["candidates"],
            "message": "选址需要候选点或面要素。",
        }
    geometry_kinds = set()
    for candidate in candidates:
        geometry_type = str((candidate.get("geometry") or {}).get("type") or "")
        if geometry_type == "Point":
            geometry_kinds.add("point")
        elif geometry_type in {"Polygon", "MultiPolygon"}:
            geometry_kinds.add("polygon")
        else:
            return {"status": "InvalidData", "stage": "site_selection", "analysis_type": "site_selection",
                    "message": "候选要素必须使用点、面或多面几何。"}
    if len(geometry_kinds) != 1:
        return {"status": "InvalidData", "stage": "site_selection", "analysis_type": "site_selection",
                "message": "一次选址请求中只能使用点候选或面候选中的一种。"}
    facilities = model._features_from_source(payload.get("facilities"))
    constraints = model._features_from_source(payload.get("constraints"))
    weights = _site_selection_weights(payload.get("weights"))
    influence_m = model._safe_float(payload.get("facilityInfluenceM"), 1500.0)
    exclusion_m = model._safe_float(payload.get("exclusionDistanceM"), 200.0)
    if influence_m is None or influence_m <= 0:
        raise ValueError("facilityInfluenceM must be greater than zero")
    if exclusion_m is None or exclusion_m < 0:
        raise ValueError("exclusionDistanceM must be non-negative")

    facility_centers = [model._feature_centroid(item) for item in facilities]
    facility_centers = [center for center in facility_centers if center]
    constraint_centers = [model._feature_centroid(item) for item in constraints]
    constraint_centers = [center for center in constraint_centers if center]
    ranked = []
    for index, candidate in enumerate(candidates, start=1):
        center = model._feature_centroid(candidate)
        if not center:
            continue
        props = dict(candidate.get("properties") or {})
        nearest_facility = min((model._distance_and_bearing(center, target)[0] for target in facility_centers), default=None)
        nearest_constraint = min((model._distance_and_bearing(center, target)[0] for target in constraint_centers), default=None)
        access_score = None if nearest_facility is None else max(0.0, 1.0 - nearest_facility / influence_m)
        avoidance_score = None if nearest_constraint is None else min(1.0, nearest_constraint / max(exclusion_m, 1.0))
        excluded = nearest_constraint is not None and nearest_constraint < exclusion_m
        active = {}
        if access_score is not None:
            active["access"] = access_score
        if avoidance_score is not None:
            active["avoidance"] = avoidance_score
        active_weight = sum(weights[name] for name in active)
        score = 0.0 if not active else sum(active[name] * weights[name] for name in active) / active_weight
        props.update({
            "siteRank": 0, "siteScore": round(score * 100.0, 1), "screeningStatus": "excluded" if excluded else "candidate",
            "accessScore": None if access_score is None else round(access_score * 100.0, 1),
            "avoidanceScore": None if avoidance_score is None else round(avoidance_score * 100.0, 1),
            "nearestFacilityM": None if nearest_facility is None else round(nearest_facility, 1),
            "nearestConstraintM": None if nearest_constraint is None else round(nearest_constraint, 1),
            "name": props.get("name") or props.get("id") or f"候选地块 {index}",
        })
        ranked.append({"type": "Feature", "properties": props, "geometry": candidate.get("geometry")})
    if not ranked:
        return {"status": "NoData", "stage": "site_selection", "analysis_type": "site_selection",
                "message": "没有候选要素包含可用几何。"}
    ranked.sort(key=lambda item: (item["properties"]["screeningStatus"] == "excluded", -item["properties"]["siteScore"], item["properties"]["name"]))
    for rank, item in enumerate(ranked, start=1):
        item["properties"]["siteRank"] = rank
    result_features = model._feature_collection(ranked)
    eligible = [item for item in ranked if item["properties"]["screeningStatus"] == "candidate"]
    return {
        "status": "Success", "stage": "site_selection", "analysis_type": "site_selection",
        "candidate_count": len(ranked), "eligible_count": len(eligible), "excluded_count": len(ranked) - len(eligible),
        "weights": weights, "facility_influence_m": round(influence_m, 1), "exclusion_distance_m": round(exclusion_m, 1),
        "facility_count": len(facility_centers), "constraint_count": len(constraint_centers),
        "candidate_geometry": next(iter(geometry_kinds)),
        "ranked_sites": result_features, "best_site": eligible[0] if eligible else None,
        "method": "weighted_euclidean_proximity_screening",
        "limitations": "仅按直线距离筛查；选址前请以权威数据复核土地权属、用地性质、地块面积、路网出行时间、环境约束、地形、市政条件与法定审批。",
        "commands": [
            {"action": "addGeoJsonLayer", "params": {"layerId": "site-selection-ranked", "title": "选址候选地块", "style": "siteSelection", "visible": True, "data": result_features}},
            {"action": "showAdvancedAnalysis", "params": {"analysisType": "site_selection", "title": "多准则选址筛查", "candidateCount": len(ranked), "eligibleCount": len(eligible), "bestSite": eligible[0]["properties"] if eligible else None, "weights": weights, "limitations": "仅按直线距离筛查，需以权威规划与工程约束复核。"}},
        ],
    }


def _advanced_analysis_features(payload):
    buildings = payload.get("buildings")
    features = model._features_from_source(buildings)
    source = "current_context"
    if not features and payload.get("aoi"):
        fetched = fetch_buildings_for_aoi(payload["aoi"])
        if fetched.get("status") == "Success":
            buildings = fetched.get("buildings")
            features = model._features_from_source(buildings)
            source = fetched.get("source", "osm_overpass")
    return features, buildings, source


def _analysis_center(payload, features):
    aoi_features = model._features_from_source(payload.get("aoi")) if payload.get("aoi") else []
    centers = [center for center in (model._feature_centroid(f) for f in aoi_features) if center]
    if not centers:
        centers = [center for center in (model._feature_centroid(f) for f in features) if center]
    if not centers:
        raise ValueError("No valid building or AOI geometry was provided")
    return (
        sum(center[0] for center in centers) / len(centers),
        sum(center[1] for center in centers) / len(centers),
    )


def calculate_skyline(payload):
    features, buildings, source = _advanced_analysis_features(payload)
    if not features:
        return {"status": "NoData", "analysis_type": "skyline", "message": "当前范围内没有可用于天际线分析的建筑"}
    center = _analysis_center(payload, features)
    bin_count = max(12, min(int(payload.get("bin_count", 24) or 24), 72))
    profile = [{
        "angle": round((index + 0.5) * 360.0 / bin_count, 1),
        "height": 0.0,
        "distance": None,
        "building": None,
    } for index in range(bin_count)]
    heights = []
    measured = 0
    for index, feature in enumerate(features):
        feature_center = model._feature_centroid(feature)
        if not feature_center:
            continue
        height, height_source = model._feature_height(feature)
        heights.append(height)
        if height_source != "default_estimate":
            measured += 1
        distance, bearing = model._distance_and_bearing(center, feature_center)
        bin_index = min(int(bearing / 360.0 * bin_count), bin_count - 1)
        if height > profile[bin_index]["height"]:
            props = feature.get("properties") or {}
            profile[bin_index].update({
                "height": round(height, 1),
                "distance": round(distance, 1),
                "building": str(props.get("name") or props.get("id") or f"building-{index + 1}"),
            })
    result = {
        "status": "Success",
        "stage": "skyline_analysis",
        "analysis_type": "skyline",
        "building_count": len(features),
        "max_height": round(max(heights), 1),
        "mean_height": round(sum(heights) / len(heights), 1),
        "height_attribute_ratio": round(measured / len(heights), 3),
        "center": {"longitude": round(center[0], 6), "latitude": round(center[1], 6)},
        "skyline_profile": profile,
        "data_source": source,
        "method": "directional_max_height_profile",
        "limitations": "筛查剖面基于建筑质心与属性高度；未包含地形与真实视线遮挡。",
        "buildings": buildings,
    }
    result["commands"] = [{"action": "showAdvancedAnalysis", "params": {
        "analysisType": "skyline", "title": "天际线方向剖面", "profile": profile,
        "maxHeight": result["max_height"], "meanHeight": result["mean_height"],
        "buildingCount": result["building_count"], "dataSource": source,
        "limitations": result["limitations"],
    }}]
    return result


def calculate_sunlight(payload):
    features, buildings, source = _advanced_analysis_features(payload)
    if not features:
        return {"status": "NoData", "analysis_type": "sunlight", "message": "当前范围内没有可用于日照分析的建筑"}
    center = _analysis_center(payload, features)
    try:
        analysis_date = calendar_date.fromisoformat(str(payload.get("date"))) if payload.get("date") else calendar_date.today()
    except ValueError:
        return {"status": "Error", "analysis_type": "sunlight", "message": "日期必须使用 YYYY-MM-DD 格式"}
    raw_hours = payload.get("hours") if isinstance(payload.get("hours"), list) else [8, 10, 12, 14, 16]
    hours = sorted(set(max(0, min(int(hour), 23)) for hour in raw_hours))
    heights = [model._feature_height(feature)[0] for feature in features]
    samples = []
    max_shadow_length = 0.0
    for hour in hours:
        altitude, azimuth = model._solar_position(center[1], analysis_date.timetuple().tm_yday, hour)
        if altitude <= 0:
            avg_shadow = None
            sample_max = None
        else:
            tangent = max(math.tan(math.radians(altitude)), 0.01)
            lengths = [height / tangent for height in heights]
            avg_shadow = round(sum(lengths) / len(lengths), 1)
            sample_max = round(max(lengths), 1)
            max_shadow_length = max(max_shadow_length, max(lengths))
        samples.append({"hour": hour, "sun_altitude": round(altitude, 1), "sun_azimuth": round(azimuth, 1),
                        "average_shadow_length_m": avg_shadow, "max_shadow_length_m": sample_max})
    qualified = sum(1 for sample in samples if sample["sun_altitude"] >= 15.0)
    shadow_hour = max(0, min(int(payload.get("shadow_hour", 15) or 15), 23))
    selected = min(samples, key=lambda sample: abs(sample["hour"] - shadow_hour))
    shadows = []
    if selected["sun_altitude"] > 0:
        shadow_bearing = (selected["sun_azimuth"] + 180.0) % 360.0
        tangent = max(math.tan(math.radians(selected["sun_altitude"])), 0.01)
        for feature, height in zip(features, heights):
            shadow = model._shadow_feature(feature, min(height / tangent, 500.0), shadow_bearing, center[1], selected["hour"])
            if shadow:
                shadows.append(shadow)
    shadow_collection = model._feature_collection(shadows)
    result = {
        "status": "Success", "stage": "sunlight_analysis", "analysis_type": "sunlight",
        "date": analysis_date.isoformat(), "building_count": len(features), "sample_count": len(samples),
        "samples": samples, "sunlight_window_percent": round(qualified / len(samples) * 100.0, 1) if samples else 0.0,
        "max_shadow_length_m": round(max_shadow_length, 1), "shadow_hour": selected["hour"],
        "shadows": shadow_collection, "data_source": source,
        "method": "solar_position_and_height_based_shadow_screening",
        "limitations": "按本地太阳时与建筑属性高度估算；未包含地形、立面开窗与法定日照时长规则。",
        "buildings": buildings,
    }
    commands = []
    if shadows:
        commands.append({"action": "addGeoJsonLayer", "params": {
            "layerId": "sunlight-screening-shadows", "title": f"{selected['hour']}:00 阴影筛查",
            "style": "shadow", "visible": True, "data": shadow_collection,
        }})
    commands.append({"action": "showAdvancedAnalysis", "params": {
        "analysisType": "sunlight", "title": "日照与阴影筛查", "date": result["date"],
        "samples": samples, "sunlightWindowPercent": result["sunlight_window_percent"],
        "maxShadowLengthM": result["max_shadow_length_m"], "buildingCount": result["building_count"],
        "dataSource": source, "limitations": result["limitations"],
    }})
    result["commands"] = commands
    return result


def _number_from_mapping(value, keys):
    if not isinstance(value, dict):
        return None
    for key in keys:
        try:
            number = float(value.get(key))
            if math.isfinite(number):
                return number
        except (TypeError, ValueError):
            continue
    return None


def _dem_samples(dem):
    samples = []
    for feature in model._features_from_source(dem):
        center = model._feature_centroid(feature)
        elevation = _number_from_mapping(feature.get("properties") or {},
                                         ("elevation_m", "elevation", "elev", "z", "value"))
        if center and elevation is not None:
            samples.append((center[0], center[1], elevation))
    if isinstance(dem, dict):
        for row in dem.get("samples", []):
            if not isinstance(row, dict):
                continue
            lon = _number_from_mapping(row, ("longitude", "lon", "x"))
            lat = _number_from_mapping(row, ("latitude", "lat", "y"))
            elevation = _number_from_mapping(row, ("elevation_m", "elevation", "elev", "z", "value"))
            if lon is not None and lat is not None and elevation is not None:
                samples.append((lon, lat, elevation))
        samples.extend(_raster_dem_samples(dem))
    return samples


def _raster_dem_samples(dem):
    if not isinstance(dem, dict) or dem.get("kind") != "raster" or not dem.get("path"):
        return []
    root = Path(os.getenv("GIS_RASTER_ROOT", Path.cwd() / "cityengine-workspace" / "gis-inputs")).resolve()
    path = Path(str(dem["path"])).resolve()
    if root not in path.parents or not path.is_file():
        return []
    suffix = path.suffix.lower()
    if suffix == ".asc":
        return _ascii_grid_samples(path)
    if suffix in (".tif", ".tiff"):
        return _geotiff_samples(path)
    return []


def _ascii_grid_samples(path):
    try:
        with path.open("r", encoding="utf-8-sig") as stream:
            header = {}
            for _ in range(6):
                key, value = stream.readline().split(maxsplit=1)
                header[key.lower()] = float(value)
            ncols, nrows = int(header["ncols"]), int(header["nrows"])
            x0, y0, cell_size = header["xllcorner"], header["yllcorner"], header["cellsize"]
            no_data = header.get("nodata_value")
            stride = max(1, int(math.sqrt(max(1, ncols * nrows / 1024))))
            samples = []
            for row_index, line in enumerate(stream):
                if row_index % stride:
                    continue
                for column_index, value in enumerate(line.split()):
                    if column_index % stride:
                        continue
                    elevation = float(value)
                    if no_data is not None and elevation == no_data:
                        continue
                    longitude = x0 + (column_index + 0.5) * cell_size
                    latitude = y0 + (nrows - row_index - 0.5) * cell_size
                    samples.append((longitude, latitude, elevation))
            return samples
    except (OSError, ValueError, KeyError):
        return []


def _geotiff_samples(path):
    try:
        import rasterio
        from rasterio.warp import transform
    except ImportError:
        return []
    try:
        with rasterio.open(path) as dataset:
            stride = max(1, int(math.sqrt(max(1, dataset.width * dataset.height / 1024))))
            band = dataset.read(1, masked=True)
            samples = []
            for row in range(0, dataset.height, stride):
                for column in range(0, dataset.width, stride):
                    value = band[row, column]
                    if getattr(value, "mask", False):
                        continue
                    x, y = dataset.xy(row, column)
                    if dataset.crs and str(dataset.crs).upper() != "EPSG:4326":
                        x, y = transform(dataset.crs, "EPSG:4326", [x], [y])
                        x, y = x[0], y[0]
                    samples.append((float(x), float(y), float(value)))
            return samples
    except Exception:
        return []


def _risk_cell(lon, lat, score, level, depth_m, elevation, cell_width_m, cell_height_m):
    """Render a risk cell at the DEM grid resolution, never at a fixed display size."""
    half_width_m = max(1.0, cell_width_m * 0.5)
    half_height_m = max(1.0, cell_height_m * 0.5)
    lon_delta = half_width_m / (111320.0 * max(math.cos(math.radians(lat)), 0.01))
    lat_delta = half_height_m / 111320.0
    ring = [
        [lon - lon_delta, lat - lat_delta], [lon + lon_delta, lat - lat_delta],
        [lon + lon_delta, lat + lat_delta], [lon - lon_delta, lat + lat_delta],
        [lon - lon_delta, lat - lat_delta],
    ]
    return {
        "type": "Feature",
        "properties": {
            "name": {"high": "高风险洪涝格网", "medium": "中风险洪涝格网", "low": "低风险洪涝格网"}.get(level, f"{level} 风险洪涝格网"),
            "riskLevel": level,
            "riskScore": round(score, 1),
            "estimatedDepthM": round(depth_m, 3),
            "elevationM": round(elevation, 2),
            "gridCellWidthM": round(cell_width_m, 1),
            "gridCellHeightM": round(cell_height_m, 1),
        },
        "geometry": {"type": "Polygon", "coordinates": [ring]},
    }


_D8_NEIGHBORS = ((-1, 0), (-1, 1), (0, 1), (1, 1), (1, 0), (1, -1), (0, -1), (-1, -1))


def _ascii_grid(path):
    """Read an ESRI ASCII DEM without discarding its cell topology."""
    try:
        with path.open("r", encoding="utf-8-sig") as stream:
            header = {}
            for _ in range(6):
                key, value = stream.readline().split(maxsplit=1)
                header[key.lower()] = float(value)
            rows, columns = int(header["nrows"]), int(header["ncols"])
            nodata = header.get("nodata_value")
            values = []
            for _ in range(rows):
                row = [float(value) for value in stream.readline().split()]
                if len(row) != columns:
                    return None
                values.append([None if nodata is not None and value == nodata else value for value in row])
            return {
                "values": values, "rows": rows, "columns": columns,
                "x0": header["xllcorner"], "y0": header["yllcorner"],
                "dx": header["cellsize"], "dy": header["cellsize"],
                "source": "esri_ascii_grid", "derived": False,
            }
    except (OSError, ValueError, KeyError):
        return None


def _geotiff_grid(path):
    try:
        import rasterio
        from rasterio.warp import transform
    except ImportError:
        return None
    try:
        with rasterio.open(path) as dataset:
            from affine import Affine
            from rasterio.enums import Resampling
            # Interactive priority-flood/D8 routing is O(n log n); retain a
            # responsive online analysis budget and leave finer runs to jobs.
            maximum_cells = 30000
            columns, rows = dataset.width, dataset.height
            transform_matrix = dataset.transform
            if columns * rows > maximum_cells:
                scale = math.sqrt((columns * rows) / maximum_cells)
                columns, rows = max(3, int(columns / scale)), max(3, int(rows / scale))
                band = dataset.read(1, out_shape=(rows, columns), masked=True, resampling=Resampling.average)
                transform_matrix = dataset.transform * Affine.scale(dataset.width / columns, dataset.height / rows)
            else:
                band = dataset.read(1, masked=True)
            # Hydrologic routing needs a regular grid. We retain the raster's
            # topology and convert its centres to WGS84 only for rendering.
            if not dataset.crs or str(dataset.crs).upper() == "EPSG:4326":
                def coordinate(row, column):
                    return rasterio.transform.xy(transform_matrix, row, column)
            else:
                def coordinate(row, column):
                    x, y = rasterio.transform.xy(transform_matrix, row, column)
                    lon, lat = transform(dataset.crs, "EPSG:4326", [x], [y])
                    return lon[0], lat[0]
            origin_x, origin_y = coordinate(0, 0)
            x_next, _ = coordinate(0, min(1, columns - 1))
            _, y_next = coordinate(min(1, rows - 1), 0)
            values = [[None if getattr(band[row, column], "mask", False) else float(band[row, column])
                       for column in range(columns)] for row in range(rows)]
            return {
                "values": values, "rows": rows, "columns": columns,
                "x0": origin_x, "y0": origin_y,
                "dx": abs(x_next - origin_x) or 1e-9, "dy": abs(y_next - origin_y) or 1e-9,
                "source": "geotiff", "derived": False,
            }
    except Exception:
        return None


def _grid_from_samples(samples):
    """Build a grid only when DEM samples are genuinely a regular lattice."""
    if len(samples) < 9:
        return None
    xs = sorted({round(point[0], 10) for point in samples})
    ys = sorted({round(point[1], 10) for point in samples}, reverse=True)
    if len(xs) < 3 or len(ys) < 3 or len(xs) * len(ys) > 250000:
        return None
    dxs = [xs[index + 1] - xs[index] for index in range(len(xs) - 1)]
    dys = [ys[index] - ys[index + 1] for index in range(len(ys) - 1)]
    dx, dy = sum(dxs) / len(dxs), sum(dys) / len(dys)
    if dx <= 0 or dy <= 0 or max(dxs) - min(dxs) > dx * 0.02 or max(dys) - min(dys) > dy * 0.02:
        return None
    lookup = {(round(x, 10), round(y, 10)): elevation for x, y, elevation in samples}
    values = [[lookup.get((x, y)) for x in xs] for y in ys]
    if sum(value is not None for row in values for value in row) < len(samples) * 0.95:
        return None
    return {"values": values, "rows": len(ys), "columns": len(xs), "x0": xs[0], "y0": ys[0],
            "dx": dx, "dy": dy, "source": "regular_grid_reconstructed_from_dem_samples", "derived": True}


def _hydrologic_grid(dem):
    # Use the managed path as the authority; session metadata can vary by provider.
    if isinstance(dem, dict) and dem.get("path"):
        root = Path(os.getenv("GIS_RASTER_ROOT", Path.cwd() / "cityengine-workspace" / "gis-inputs")).resolve()
        path = Path(str(dem["path"])).resolve()
        if root not in path.parents or not path.is_file():
            return None
        if path.suffix.lower() == ".asc":
            return _ascii_grid(path)
        if path.suffix.lower() in (".tif", ".tiff"):
            return _geotiff_grid(path)
    return _grid_from_samples(_dem_samples(dem))


def _cell_coordinate(grid, row, column):
    return grid["x0"] + column * grid["dx"], grid["y0"] - row * grid["dy"]


def _fill_depressions(values):
    """Priority-flood fill. Border cells drain; enclosed sinks rise to spill elevation."""
    rows, columns = len(values), len(values[0])
    filled = [[None for _ in range(columns)] for _ in range(rows)]
    parents = [[None for _ in range(columns)] for _ in range(rows)]
    visited, queue = set(), []
    for row in range(rows):
        for column in range(columns):
            if row not in (0, rows - 1) and column not in (0, columns - 1):
                continue
            elevation = values[row][column]
            if elevation is not None:
                visited.add((row, column)); filled[row][column] = elevation
                heapq.heappush(queue, (elevation, row, column))
    while queue:
        elevation, row, column = heapq.heappop(queue)
        for dr, dc in _D8_NEIGHBORS:
            nr, nc = row + dr, column + dc
            if not (0 <= nr < rows and 0 <= nc < columns) or (nr, nc) in visited or values[nr][nc] is None:
                continue
            visited.add((nr, nc))
            filled[nr][nc] = max(values[nr][nc], elevation)
            parents[nr][nc] = (row, column)
            heapq.heappush(queue, (filled[nr][nc], nr, nc))
    return filled, parents


def _flow_routing(filled, fill_parents=None):
    rows, columns = len(filled), len(filled[0])
    downstream = [[None for _ in range(columns)] for _ in range(rows)]
    directions = [[None for _ in range(columns)] for _ in range(rows)]
    for row in range(rows):
        for column in range(columns):
            elevation = filled[row][column]
            if elevation is None:
                continue
            candidates = []
            for code, (dr, dc) in enumerate(_D8_NEIGHBORS, 1):
                nr, nc = row + dr, column + dc
                if 0 <= nr < rows and 0 <= nc < columns and filled[nr][nc] is not None:
                    distance = math.sqrt(2) if dr and dc else 1.0
                    drop = (elevation - filled[nr][nc]) / distance
                    if drop > 1e-9:
                        candidates.append((drop, code, nr, nc))
            if candidates:
                _, code, nr, nc = max(candidates)
                directions[row][column], downstream[row][column] = code, (nr, nc)
            elif fill_parents and fill_parents[row][column]:
                # Priority-flood establishes a spill path across flats.  Use
                # that parent path when equal filled elevations have no strict
                # D8 gradient, instead of leaving a filled sink disconnected.
                nr, nc = fill_parents[row][column]
                dr, dc = nr - row, nc - column
                directions[row][column] = _D8_NEIGHBORS.index((dr, dc)) + 1
                downstream[row][column] = (nr, nc)
    accumulation = [[0 if filled[row][column] is None else 1 for column in range(columns)] for row in range(rows)]
    cells = sorted(((filled[row][column], row, column) for row in range(rows) for column in range(columns)
                    if filled[row][column] is not None), reverse=True)
    for _, row, column in cells:
        target = downstream[row][column]
        if target:
            accumulation[target[0]][target[1]] += accumulation[row][column]
    return directions, accumulation


def _drainage_reduction(grid, drainage_network):
    lines = []
    for feature in model._features_from_source(drainage_network):
        geometry = feature.get("geometry") or {}
        coordinates = list(model._iter_coordinates(geometry))
        if not coordinates and geometry.get("type") in ("LineString", "MultiLineString"):
            raw = geometry.get("coordinates") or []
            if geometry.get("type") == "LineString":
                coordinates = [(float(point[0]), float(point[1])) for point in raw if len(point) >= 2]
            else:
                coordinates = [(float(point[0]), float(point[1])) for line in raw for point in line if len(point) >= 2]
        lines.extend(zip(coordinates, coordinates[1:]))
    reductions = [[0.0 for _ in range(grid["columns"])] for _ in range(grid["rows"])]
    if not lines:
        return reductions, False
    influence_m = max(grid["dx"] * 111320, grid["dy"] * 111320) * 2.5
    for row in range(grid["rows"]):
        for column in range(grid["columns"]):
            point = _cell_coordinate(grid, row, column)
            distance = min(min(model._distance_and_bearing(point, endpoint)[0] for endpoint in segment)
                           for segment in lines)
            reductions[row][column] = 0.55 * max(0.0, 1.0 - distance / max(influence_m, 1.0))
    return reductions, True


def calculate_flood_risk(payload):
    """Hydrologic DEM screening: depression fill, D8 routing and drainage reduction."""
    payload = payload or {}
    if not model._features_from_source(payload.get("aoi")):
        return {"status": "NoData", "stage": "flood_analysis", "analysis_type": "flood",
                "missing_data": ["aoi"], "message": "洪涝风险筛查需要 AOI 范围。"}
    grid = _hydrologic_grid(payload.get("dem"))
    if not grid or grid["rows"] < 3 or grid["columns"] < 3:
        return {"status": "NoData", "stage": "flood_analysis", "analysis_type": "flood",
                "missing_data": ["hydrologic_dem_grid"],
                "message": "需要 3×3 及以上规格的规则 DEM 网格。请上传 ASC/GeoTIFF，或先采样规则地面网格。"}
    scenario = payload.get("rainfall_scenario")
    rainfall_mm = _number_from_mapping(scenario, ("rainfallMm", "rainfall_mm", "rainfall", "depthMm"))
    if rainfall_mm is None or rainfall_mm <= 0:
        return {"status": "NoData", "stage": "flood_analysis", "analysis_type": "flood",
                "missing_data": ["rainfall_scenario"], "message": "需要大于 0 的降雨情景。"}

    values = grid["values"]
    elevations = [value for row in values for value in row if value is not None]
    minimum, maximum = min(elevations), max(elevations)
    filled, fill_parents = _fill_depressions(values)
    directions, accumulation = _flow_routing(filled, fill_parents)
    drainage, has_drainage = _drainage_reduction(grid, payload.get("drainage_network"))
    valid_accumulation = sorted(accumulation[row][column] for row in range(grid["rows"])
                                for column in range(grid["columns"]) if values[row][column] is not None)
    accumulation_reference = valid_accumulation[len(valid_accumulation) // 2] or 1
    dem_quality = {
        "sample_count": len(elevations), "grid_rows": grid["rows"], "grid_columns": grid["columns"],
        "minimum_elevation_m": round(minimum, 2),
        "maximum_elevation_m": round(maximum, 2), "elevation_span_m": round(maximum - minimum, 3),
        "source": grid["source"], "derived_grid": grid["derived"],
    }
    if maximum - minimum < 0.5:
        dem_quality["warning"] = "采样范围内地形高差小于 0.5 米，相对风险分级对高程噪声敏感。"
    risk_features, risk_by_sample = [], []
    for row in range(grid["rows"]):
        for column in range(grid["columns"]):
            elevation = values[row][column]
            if elevation is None:
                continue
            lon, lat = _cell_coordinate(grid, row, column)
            depression_depth = max(0.0, filled[row][column] - elevation)
            contributing_cells = accumulation[row][column]
            routing_factor = min(2.5, 0.35 * math.log1p(contributing_cells / accumulation_reference))
            runoff_depth = rainfall_mm / 1000.0 * 0.65 * (1.0 + routing_factor) * (1.0 - drainage[row][column])
            # A filled depression denotes storage capacity, not instant water
            # depth. Event runoff can only occupy that available capacity.
            # This keeps a 120 mm storm from becoming a multi-metre depth just
            # because a DEM contains a deep closed pit.
            event_depth = runoff_depth * min(1.0, 0.20 + 0.16 * routing_factor)
            depth_m = min(depression_depth, event_depth) if depression_depth > 0 else event_depth * 0.15
            score = min(100.0, depth_m / 0.30 * 100.0)
            level = "high" if depth_m >= 0.020 else "medium" if depth_m >= 0.008 else "low"
            cell_width_m = abs(grid["dx"]) * 111320.0 * max(math.cos(math.radians(lat)), 0.01)
            cell_height_m = abs(grid["dy"]) * 111320.0
            feature = _risk_cell(lon, lat, score, level, depth_m, elevation, cell_width_m, cell_height_m)
            feature["properties"].update({"filledElevationM": round(filled[row][column], 3),
                                             "depressionFillDepthM": round(depression_depth, 3),
                                             "d8Direction": directions[row][column],
                                             "flowAccumulationCells": contributing_cells,
                                             "drainageReduction": round(drainage[row][column], 3)})
            risk_features.append(feature)
            risk_by_sample.append((lon, lat, score, level, depth_m))

    building_features = model._features_from_source(payload.get("buildings"))
    building_exposure_available = bool(building_features)
    affected_buildings = 0
    exposed_buildings = []
    for building in building_features:
        center = model._feature_centroid(building)
        if not center:
            continue
        nearest = min(risk_by_sample, key=lambda item: model._distance_and_bearing(center, item[:2])[0])
        if nearest[3] in ("high", "medium"):
            affected_buildings += 1
            exposed = dict(building)
            exposed["properties"] = dict(building.get("properties") or {})
            exposed["properties"].update({
                "name": exposed["properties"].get("name") or exposed["properties"].get("id") or "受影响建筑",
                "floodExposure": nearest[3], "riskScore": round(nearest[2], 1),
                "estimatedDepthM": round(nearest[4], 3),
            })
            exposed_buildings.append(exposed)
    levels = [feature["properties"]["riskLevel"] for feature in risk_features]
    risk_cells = model._feature_collection(risk_features)
    scenario_name = scenario.get("name") if isinstance(scenario, dict) else None
    return_period = int(payload.get("returnPeriodYears") or (scenario or {}).get("returnPeriodYears") or 20)
    result = {
        "status": "Success", "stage": "flood_analysis", "analysis_type": "flood",
        "rainfall_mm": round(rainfall_mm, 1), "return_period_years": return_period,
        "dem_sample_count": len(elevations), "high_risk_cell_count": levels.count("high"),
        "medium_risk_cell_count": levels.count("medium"), "low_risk_cell_count": levels.count("low"),
        "max_estimated_depth_m": round(max(feature["properties"]["estimatedDepthM"] for feature in risk_features), 3),
        "affected_building_count": affected_buildings if building_exposure_available else None, "risk_cells": risk_cells,
        "affected_buildings": model._feature_collection(exposed_buildings), "dem_quality": dem_quality,
        "building_exposure_available": building_exposure_available,
        "grid_cell_size_m": {"width": round(abs(grid["dx"]) * 111320.0, 1),
                             "height": round(abs(grid["dy"]) * 111320.0, 1)},
        "data_source": "current_context", "method": "hydrologic_dem_priority_flood_d8_flow_accumulation",
        "hydrology": {"depressionFill": "priority_flood", "flowDirection": "D8",
                       "drainageNetworkApplied": has_drainage, "runoffCoefficient": 0.65},
        "limitations": "水文地形筛查基于 DEM 洼地填平、D8 流向与邻近排水削减，非标定的二维水动力模型，不代表管渠容量、河道水位、边界入流、糙率或随时间变化的淹没过程。"
                       + ("" if building_exposure_available else " 当前 AOI 无完整建筑数据集，未计算建筑暴露情况。"),
    }
    result["commands"] = [{"action": "addGeoJsonLayer", "params": {
        "layerId": "flood-risk-screening", "title": "洪涝风险筛查", "style": "floodRisk",
        "visible": True, "data": risk_cells,
    }}]
    if exposed_buildings:
        result["commands"].append({"action": "addGeoJsonLayer", "params": {
            "layerId": "flood-exposed-buildings", "title": "可能受淹建筑", "style": "floodExposure",
            "visible": True, "data": result["affected_buildings"],
        }})
    result["commands"].append({"action": "showAdvancedAnalysis", "params": {
        "analysisType": "flood", "title": "洪涝风险筛查", "rainfallMm": result["rainfall_mm"],
        "returnPeriodYears": return_period, "highRiskCellCount": result["high_risk_cell_count"],
        "mediumRiskCellCount": result["medium_risk_cell_count"],
        # Do not collapse missing building data into a zero-exposure claim.
        "affectedBuildingCount": result["affected_building_count"],
        "buildingExposureAvailable": building_exposure_available,
        "maxEstimatedDepthM": result["max_estimated_depth_m"], "scenarioName": scenario_name,
        "demQuality": dem_quality, "hydrology": result["hydrology"], "limitations": result["limitations"],
    }})
    return result


# --------------------------------------------------------------------------- #
# Demo / context planning (orchestration that submits to CityEngine)
# --------------------------------------------------------------------------- #
def _load_demo_json(file_name):
    base_dir = Path(__file__).resolve().parent.parent
    configured_dir = os.getenv("GIS_DEMO_CASE_DIR")
    candidates = []
    if configured_dir:
        candidates.append(Path(configured_dir) / file_name)
    candidates.extend([
        base_dir / "demo-case" / file_name,
        base_dir / "src" / "main" / "resources" / "static" / "demo-case" / file_name,
    ])

    for path in candidates:
        if path.is_file():
            with path.open("r", encoding="utf-8-sig") as stream:
                return json.loads(stream.read())

    searched = ", ".join(str(path) for path in candidates)
    raise FileNotFoundError(f"Demo case file {file_name} was not found. Searched: {searched}")


def _evaluate_demo_metrics(metrics, green_rate, rule_set):
    rules = rule_set["rules"]
    height_stats = metrics.get("height_stats") or {}
    max_height = model._safe_float(height_stats.get("max"), 0.0) or 0.0
    values = {
        "far": model._safe_float(metrics.get("far"), 0.0) or 0.0,
        "buildingDensity": model._safe_float(metrics.get("building_density"), 0.0) or 0.0,
        "buildingHeight": max_height,
        "greenRate": green_rate,
    }
    evaluations = [model._evaluate_rule(name, values[name], rule) for name, rule in rules.items()]
    return evaluations, all(item["passed"] for item in evaluations)


def _problem_buildings(buildings, rules):
    max_height = model._safe_float(rules.get("buildingHeight", {}).get("max"), None)
    far_excess = False
    results = []
    for feature in model._features_from_source(buildings):
        props = dict(feature.get("properties") or {})
        height = model._safe_float(props.get("height"), None)
        levels = model._safe_float(props.get("building:levels"), model._safe_float(props.get("levels"), None))
        reasons = []
        if max_height is not None and height is not None and height > max_height:
            reasons.append("建筑高度超过演示规则上限")
        if levels is not None and levels >= 20:
            far_excess = True
            reasons.append("高层建筑对容积率贡献较高")
        if reasons:
            props["problem"] = True
            props["problemReasons"] = reasons
            results.append({"type": "Feature", "properties": props, "geometry": feature.get("geometry")})
    return model._feature_collection(results), far_excess


def _optimized_buildings(buildings, rules):
    max_height = model._safe_float(rules.get("buildingHeight", {}).get("max"), 54.0) or 54.0
    max_levels = max(1, int(max_height // 3.0))
    optimized = []
    changes = []
    for feature in model._features_from_source(buildings):
        props = dict(feature.get("properties") or {})
        original_levels = int(model._safe_float(props.get("building:levels"), model._safe_float(props.get("levels"), 1)) or 1)
        original_height = model._safe_float(props.get("height"), original_levels * 3.0) or original_levels * 3.0
        new_levels = min(original_levels, max_levels)
        new_height = min(original_height, max_height)
        if new_levels != original_levels or new_height != original_height:
            changes.append({
                "buildingId": props.get("id"),
                "name": props.get("name"),
                "action": "reduce_height",
                "fromLevels": original_levels,
                "toLevels": new_levels,
                "fromHeight": original_height,
                "toHeight": new_height,
            })
        props["building:levels"] = new_levels
        props["height"] = new_height
        props["heightAdjusted"] = 1 if new_levels != original_levels or new_height != original_height else 0
        props["originalHeight"] = original_height
        props["scenario"] = "optimized"
        optimized.append({"type": "Feature", "properties": props, "geometry": feature.get("geometry")})
    return model._feature_collection(optimized), changes


def _proposed_green_space():
    return {
        "type": "FeatureCollection",
        "features": [{
            "type": "Feature",
            "properties": {"id": "G02", "name": "建议新增公共绿地", "scenario": "optimized"},
            "geometry": {
                "type": "Polygon",
                "coordinates": [[[121.47215, 31.23089], [121.47585, 31.23089], [121.47585, 31.23121], [121.47215, 31.23121], [121.47215, 31.23089]]],
            },
        }],
    }


def evaluate_demo_case(requirements=None, rag_context="", user_request=""):
    case_data = _load_demo_json("case.json")
    rule_set = _load_demo_json("rules.json")
    aoi = case_data["aoi"]
    current_buildings = case_data["buildings"]
    current_metrics = calculate_metrics({"aoi": aoi, "buildings": current_buildings})
    if current_metrics.get("status") != "Success":
        return current_metrics

    current_evaluations, current_passed = _evaluate_demo_metrics(current_metrics, 0.0, rule_set)
    problems, _ = _problem_buildings(current_buildings, rule_set["rules"])
    cityengine_job = submit_planning_job(case_data, rule_set, current_metrics, problems, requirements, rag_context, user_request)

    return {
        "status": "Queued",
        "stage": "cityengine_generation",
        "executionMode": "cityengine_only",
        "case": {key: case_data[key] for key in ("caseId", "name", "city", "landUse", "landUseName", "description")},
        "ruleSet": rule_set,
        "current": {"metrics": current_metrics, "evaluations": current_evaluations, "passed": current_passed},
        "problemBuildings": problems,
        "cityEngineJob": cityengine_job,
        "commands": [],
        "dataQuality": {
            "mode": "cityengine_external_generation",
            "source": "bundled competition demo dataset",
            "estimatedFields": [],
            "warning": rule_set["source"],
        },
        "message": "现状诊断已完成，建筑优化任务已提交给 CityEngine。前端不再实时修改建筑，也不生成绿地方案；请等待 CityEngine 导出结果。",
    }


CITYENGINE_MIN_CONTEXT_BUILDINGS = 3


def _resolve_cityengine_context_buildings(aoi, buildings):
    """Recover a complete footprint set before creating a CityEngine job.

    SceneLayer queries are viewport/tile dependent.  A small, otherwise valid
    result must not silently become a sparse CityEngine scene, so low-count
    contexts are checked against the AOI-wide OSM footprint source.
    """
    context_features, _ = model._filter_usable_building_footprints(
        model._features_from_source(buildings)
    )
    context_buildings = model._feature_collection(context_features)
    context_count = len(context_features)
    if context_count >= CITYENGINE_MIN_CONTEXT_BUILDINGS:
        return context_buildings, {
            "source": "current_map_context",
            "contextBuildingCount": context_count,
            "recoveryAttempted": False,
        }

    fetched = fetch_buildings_for_aoi(aoi)
    fetched_features = (fetched.get("buildings") or {}).get("features") or []
    fetched_count = len(fetched_features) if fetched.get("status") == "Success" else 0
    if fetched_count > context_count:
        return fetched["buildings"], {
            "source": "openstreetmap_overpass_recovery",
            "contextBuildingCount": context_count,
            "recoveredBuildingCount": fetched_count,
            "recoveryAttempted": True,
        }

    source_status = fetched.get("status", "Error")
    source_message = fetched.get("message", "no additional usable footprints")
    raise ValueError(
        "当前三维上下文仅有 " + str(context_count) + " 栋有效建筑；"
        "已核验 AOI 全量建筑数据但未发现更多可用轮廓（" + source_status + ": "
        + str(source_message) + "）。已停止生成，避免导出退化的少量建筑成果。"
        "请扩大或重新绘制 AOI 后重试。"
    )


def evaluate_context_case(payload):
    aoi = payload.get("aoi")
    buildings = payload.get("buildings")
    if not aoi:
        raise ValueError("当前地图上下文缺少 AOI，请先绘制地块或选择地点周边范围")
    buildings, building_context = _resolve_cityengine_context_buildings(aoi, buildings)

    current_metrics = calculate_metrics({"aoi": aoi, "buildings": buildings})
    if current_metrics.get("status") != "Success":
        return current_metrics

    requirements = payload.get("requirements") or {}
    max_height = model._safe_float(requirements.get("maxBuildingHeight"), None)
    rules = {}
    if max_height is not None:
        rules["buildingHeight"] = {"max": max_height, "unit": "m"}
    rule_set = {
        "ruleSetId": "runtime-context",
        "effective": False,
        "source": "当前地图上下文与用户/RAG参数；未提供的法定指标不会自动推断",
        "rules": rules,
    }
    problems, _ = _problem_buildings(buildings, rules)
    case_data = {
        "caseId": f"runtime-{int(time.time())}",
        "name": payload.get("name") or "当前地图规划范围",
        "city": payload.get("city") or "",
        "landUse": payload.get("landUse") or "",
        "landUseName": payload.get("landUseName") or "",
        "description": "由当前地图 AOI 和建筑上下文生成",
        "aoi": aoi,
        "buildings": buildings,
    }
    cityengine_job = submit_planning_job(
        case_data,
        rule_set,
        current_metrics,
        problems,
        requirements,
        payload.get("ragContext", ""),
        payload.get("userRequest", ""),
    )
    return {
        "status": "Queued",
        "stage": "cityengine_generation",
        "executionMode": "runtime_context",
        "case": {key: case_data.get(key) for key in ("caseId", "name", "city", "landUse", "landUseName", "description")},
        "ruleSet": rule_set,
        "current": {"metrics": current_metrics},
        "planned": {"metrics": cityengine_job.get("plannedMetrics") or {}},
        "problemBuildings": problems,
        "cityEngineJob": cityengine_job,
        "aoi": aoi,
        "buildings": buildings,
        "commands": [],
        "dataQuality": {
            "mode": "runtime_context",
            "source": building_context["source"],
            "estimatedFields": [],
            "warning": rule_set["source"],
            "buildingContext": building_context,
        },
        "message": "当前地图 AOI 与真实建筑已提交给 CityEngine。",
    }


def _polygon_area_sqm(feature_collection, aoi):
    features = model._features_from_source(feature_collection)
    if not features:
        return 0.0
    if adapter.HAS_OPEN_SOURCE:
        gdf_obj = adapter.dict_to_gdf(model._feature_collection(features))
        aoi_gdf = adapter.dict_to_gdf(aoi)
        metric = model._metric_crs_for_gdf(aoi_gdf if aoi_gdf is not None and not aoi_gdf.empty else gdf_obj)
        result = gdf_obj.to_crs(metric)
        if aoi_gdf is not None and not aoi_gdf.empty:
            result = gpd.clip(result, aoi_gdf.to_crs(metric))
        return float(result.geometry.area.sum())
    if adapter.HAS_ARCPY:
        metric = model._metric_crs_for_features(model._features_from_source(aoi) or features)
        records = adapter._arcpy_records(model._feature_collection(features), metric)
        return sum(adapter._arcpy_area(record["geometry"]) for record in records)
    return 0.0
