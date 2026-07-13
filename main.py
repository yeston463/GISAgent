# -*- coding: utf-8 -*-
from fastapi import FastAPI, Body, HTTPException
import importlib
import json
import math
import re
import shutil
import sys
import time
import traceback
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter
from fastapi.responses import FileResponse
from pathlib import Path

from cityengine_bridge import read_job as read_cityengine_job
from cityengine_bridge import runtime_status as cityengine_runtime_status
from cityengine_bridge import submit_planning_job
from geoscene_publisher import inspect_publication, publish_slpk, publishing_status, share_publication

app = FastAPI(title="Esri Cup Professional GIS Engine")


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
                result_path = Path(cityengine_runtime_status()["workspace"]) / "automation" / "results" / f"{job_id}.json"
                result_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
            except Exception as exc:
                result["publicationShareError"] = str(exc)
        return result
    if not outputs.get("slpk"):
        return result
    result_path = Path(cityengine_runtime_status()["workspace"]) / "automation" / "results" / f"{job_id}.json"
    try:
        publication = publish_slpk(outputs["slpk"], job_id)
        result["publication"] = publication
        result["sceneServiceUrl"] = publication["sceneServiceUrl"]
    except Exception as exc:
        result["publication"] = {"status": "failed", "message": str(exc)}
    result_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    return result

OVERPASS_ENDPOINTS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
]
MAX_BUILDINGS = 3000


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
        "python_executable": sys.executable,
        "python_version": sys.version,
        "preferred_backend": _preferred_backend(),
        "backend_priority": [
            "geoscene_arcpy",
            "open_source_geopandas",
            "standard_library_limited",
        ],
        "capabilities": {
            "arcpy": _module_status("arcpy", arcpy, ARCPY_IMPORT_ERROR),
            "arcgis": _module_status("arcgis", arcgis, ARCGIS_IMPORT_ERROR),
            "geopandas": _module_status("geopandas", gpd, GEOPANDAS_IMPORT_ERROR),
            "pandas": _module_status("pandas", pd, PANDAS_IMPORT_ERROR),
            "shapely": {"available": Point is not None, "error": SHAPELY_IMPORT_ERROR},
        },
        "data_policy": "ArcPy/GeoScene is preferred for local geometry work. OSM/Overpass HTTP remains the building-footprint acquisition source, with GeoPandas/Shapely kept as fallback.",
    }


def _is_missing_value(value):
    if value is None:
        return True
    if isinstance(value, (list, tuple, dict, set)):
        return False
    try:
        if isinstance(value, float) and math.isnan(value):
            return True
    except Exception:
        pass
    if pd is not None:
        try:
            missing = pd.isna(value)
            if isinstance(missing, bool):
                return missing
        except Exception:
            pass
    return False


def _json_safe_dict(data):
    result = {}
    if not data:
        return result
    for key, value in dict(data).items():
        if _is_missing_value(value):
            continue
        if hasattr(value, "item"):
            try:
                value = value.item()
            except Exception:
                pass
        try:
            json.dumps(value, ensure_ascii=False)
            result[str(key)] = value
        except TypeError:
            result[str(key)] = str(value)
    return result


def _parse_number(value):
    if value is None:
        return None
    if isinstance(value, (int, float)):
        try:
            if math.isnan(float(value)):
                return None
        except Exception:
            pass
        return float(value)
    match = re.search(r"-?\d+(?:\.\d+)?", str(value))
    return float(match.group(0)) if match else None


def _safe_float(value, default=None):
    parsed = _parse_number(value)
    return parsed if parsed is not None else default


def _metric_crs(lon, lat):
    if lon is None or lat is None:
        return "EPSG:3857"
    zone = max(1, min(60, int((lon + 180) / 6) + 1))
    epsg = 32600 + zone if lat >= 0 else 32700 + zone
    return f"EPSG:{epsg}"


def _epsg_number(crs):
    if crs is None:
        return 4326
    if isinstance(crs, int):
        return crs
    match = re.search(r"(\d+)$", str(crs))
    return int(match.group(1)) if match else 4326


def _feature_collection(features):
    return {"type": "FeatureCollection", "features": features or []}


def _normalize_geometry(geom_data):
    if not geom_data:
        return None
    if isinstance(geom_data, str):
        geom_data = json.loads(geom_data)
    if not isinstance(geom_data, dict):
        return None
    if "geometry" in geom_data and not geom_data.get("type"):
        geom_data = geom_data.get("geometry") or {}
    if "rings" in geom_data:
        rings = geom_data.get("rings") or []
        return {"type": "Polygon", "coordinates": rings} if rings else None
    if "x" in geom_data and "y" in geom_data:
        return {"type": "Point", "coordinates": [geom_data.get("x"), geom_data.get("y")]}
    geom_type = geom_data.get("type")
    coords = geom_data.get("coordinates")
    if geom_type and coords is not None:
        return {"type": geom_type, "coordinates": coords}
    return None


def _features_from_source(data_source):
    if not data_source:
        return []

    if isinstance(data_source, str):
        data_source = json.loads(data_source)

    if isinstance(data_source, dict) and data_source.get("type") == "FeatureCollection":
        raw_features = data_source.get("features", [])
    elif isinstance(data_source, dict) and data_source.get("type") == "Feature":
        raw_features = [data_source]
    elif isinstance(data_source, list):
        raw_features = data_source
    elif isinstance(data_source, dict) and (
        "rings" in data_source or "coordinates" in data_source or "x" in data_source
    ):
        raw_features = [{"type": "Feature", "geometry": data_source, "properties": {}}]
    else:
        return []

    features = []
    for feature in raw_features:
        try:
            if not isinstance(feature, dict):
                continue
            geom_data = feature.get("geometry") if "geometry" in feature else feature
            geom = _normalize_geometry(geom_data)
            if not geom:
                continue
            props = feature.get("properties")
            attrs = feature.get("attributes")
            if props is None:
                props = attrs or {}
            elif attrs:
                merged = dict(attrs)
                merged.update(props)
                props = merged
            features.append({
                "type": "Feature",
                "geometry": geom,
                "properties": _json_safe_dict(props),
            })
        except Exception as exc:
            print(f"feature parse failed: {exc}")
    return features


def _iter_coordinates(geom):
    if not geom:
        return
    geom = _normalize_geometry(geom)
    if not geom:
        return
    geom_type = geom.get("type")
    coords = geom.get("coordinates") or []
    if geom_type == "Point":
        if len(coords) >= 2:
            yield float(coords[0]), float(coords[1])
    elif geom_type == "Polygon":
        for ring in coords:
            for coord in ring:
                if len(coord) >= 2:
                    yield float(coord[0]), float(coord[1])
    elif geom_type == "MultiPolygon":
        for polygon in coords:
            for ring in polygon:
                for coord in ring:
                    if len(coord) >= 2:
                        yield float(coord[0]), float(coord[1])


def _bounds_from_features(features):
    coords = []
    for feature in features or []:
        coords.extend(_iter_coordinates(feature.get("geometry")))
    if not coords:
        raise ValueError("invalid geometry bounds")
    xs = [c[0] for c in coords]
    ys = [c[1] for c in coords]
    return min(xs), min(ys), max(xs), max(ys)


def _bounds_from_aoi(aoi_geojson):
    features = _features_from_source(aoi_geojson)
    if not features:
        raise ValueError("invalid AOI")
    minx, miny, maxx, maxy = _bounds_from_features(features)
    pad = 0.0003 if max(abs(minx), abs(maxx), abs(miny), abs(maxy)) <= 1000 else 30
    return minx - pad, miny - pad, maxx + pad, maxy + pad


def _source_epsg_from_features(features):
    try:
        minx, miny, maxx, maxy = _bounds_from_features(features)
        if max(abs(minx), abs(maxx)) > 180 or max(abs(miny), abs(maxy)) > 90:
            return 3857
    except Exception:
        pass
    return 4326


def _metric_crs_for_features(features):
    if not features:
        return "EPSG:3857"
    try:
        minx, miny, maxx, maxy = _bounds_from_features(features)
        source_epsg = _source_epsg_from_features(features)
        if source_epsg == 4326:
            return _metric_crs((minx + maxx) / 2, (miny + maxy) / 2)
    except Exception:
        pass
    return "EPSG:3857"


def _metric_crs_for_gdf(gdf_obj):
    if gdf_obj is None or gdf_obj.empty:
        return "EPSG:3857"
    wgs = gdf_obj if str(gdf_obj.crs).upper().endswith("4326") else gdf_obj.to_crs("EPSG:4326")
    centroid = wgs.unary_union.centroid
    return _metric_crs(centroid.x, centroid.y)


def dict_to_gdf(data_source, target_crs=None):
    """Convert GeoJSON/ArcGIS polygon features to a GeoDataFrame when open-source GIS is available."""
    if not HAS_OPEN_SOURCE:
        return None

    features = _features_from_source(data_source)
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
            attributes.append(_json_safe_dict(feature.get("properties", {})))
        except Exception as exc:
            print(f"feature parse failed: {exc}")

    if not geoms:
        return None

    source_epsg = _source_epsg_from_features(features)
    gdf_obj = gpd.GeoDataFrame(attributes, geometry=geoms, crs=f"EPSG:{source_epsg}")
    if target_crs:
        gdf_obj = gdf_obj.to_crs(target_crs)
    return gdf_obj


def _arcpy_spatial_reference(epsg):
    return arcpy.SpatialReference(_epsg_number(epsg))


def _geojson_to_esri_json(geom, epsg=4326):
    geom = _normalize_geometry(geom)
    if not geom:
        return None
    sr = {"wkid": _epsg_number(epsg)}
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
    geom = _normalize_geometry(geom)
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
    features = _features_from_source(data_source)
    if not features:
        return []

    source_epsg = _source_epsg_from_features(features)
    target_epsg = _epsg_number(target_crs) if target_crs else source_epsg
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
                "properties": _json_safe_dict(feature.get("properties", {})),
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
    metric = _metric_crs(lon, lat)
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


def _create_buffer_feature_open_source(lon, lat, radius):
    metric = _metric_crs(lon, lat)
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


def _create_buffer_feature_approx(lon, lat, radius):
    lat_rad = math.radians(lat)
    meters_per_deg_lat = 111_320.0
    meters_per_deg_lon = max(1.0, 111_320.0 * math.cos(lat_rad))
    coords = []
    for i in range(96):
        angle = 2 * math.pi * i / 96
        coords.append([
            lon + math.cos(angle) * radius / meters_per_deg_lon,
            lat + math.sin(angle) * radius / meters_per_deg_lat,
        ])
    coords.append(coords[0])
    return {
        "type": "Feature",
        "geometry": {"type": "Polygon", "coordinates": [coords]},
        "properties": {
            "source": "server_buffer",
            "backend": "standard_library_approx",
            "lon": lon,
            "lat": lat,
            "radius": radius,
            "warning": "Approximate lon/lat buffer because neither ArcPy nor GeoPandas is available.",
        },
    }


def create_buffer_feature(lon, lat, radius):
    lon = _safe_float(lon)
    lat = _safe_float(lat)
    radius = _safe_float(radius, 500)
    if lon is None or lat is None:
        raise ValueError("missing lon/lat")
    if abs(lon) < 0.1 and abs(lat) < 0.1:
        raise ValueError("invalid coordinate (0,0)")

    errors = []
    if HAS_ARCPY:
        try:
            return _create_buffer_feature_arcpy(lon, lat, radius)
        except Exception as exc:
            errors.append(f"geoscene_arcpy: {exc}")
            print(f"ArcPy buffer failed, falling back: {exc}")

    if HAS_OPEN_SOURCE:
        try:
            feature = _create_buffer_feature_open_source(lon, lat, radius)
            if errors:
                feature["properties"]["fallback_errors"] = errors
            return feature
        except Exception as exc:
            errors.append(f"open_source_geopandas: {exc}")
            print(f"GeoPandas buffer failed, falling back: {exc}")

    feature = _create_buffer_feature_approx(lon, lat, radius)
    if errors:
        feature["properties"]["fallback_errors"] = errors
    return feature


def _overpass_query(bbox):
    minx, miny, maxx, maxy = bbox
    # Overpass bbox order is south, west, north, east.
    box = f"{miny},{minx},{maxy},{maxx}"
    return f"""
    [out:json][timeout:30];
    (
      way["building"]({box});
      relation["building"]({box});
    );
    out body geom;
    """


def _call_overpass(query):
    encoded = urllib.parse.urlencode({"data": query}).encode("utf-8")
    errors = []
    for endpoint in OVERPASS_ENDPOINTS:
        req = urllib.request.Request(
            endpoint,
            data=encoded,
            headers={"User-Agent": "esri-cup-gis-agent/1.0"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(req, timeout=40) as response:
                return json.loads(response.read().decode("utf-8"))
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
            errors.append(f"{endpoint}: {exc}")
    raise RuntimeError("; ".join(errors))


def _ring_area(coords):
    if len(coords) < 4:
        return 0.0
    total = 0.0
    for idx in range(len(coords) - 1):
        total += coords[idx][0] * coords[idx + 1][1] - coords[idx + 1][0] * coords[idx][1]
    return total / 2


def _elements_to_features(elements):
    features = []
    for element in elements:
        if len(features) >= MAX_BUILDINGS:
            break
        geom = element.get("geometry")
        if not geom:
            continue

        coords = [[float(p["lon"]), float(p["lat"])] for p in geom if "lon" in p and "lat" in p]
        if len(coords) < 3:
            continue
        if coords[0] != coords[-1]:
            coords.append(coords[0])

        geometry = {"type": "Polygon", "coordinates": [coords]}
        if Polygon is not None:
            try:
                poly = Polygon(coords)
                if not poly.is_valid:
                    poly = poly.buffer(0)
                if poly.is_empty or poly.area <= 0:
                    continue
                geometry = mapping(poly)
            except Exception:
                continue
        elif abs(_ring_area(coords)) <= 0:
            continue

        props = _json_safe_dict(element.get("tags", {}))
        props["osm_id"] = element.get("id")
        props["osm_type"] = element.get("type")
        features.append({
            "type": "Feature",
            "geometry": geometry,
            "properties": props,
        })
    return features


def _clip_features_arcpy(raw_features, aoi_geojson):
    metric = _metric_crs_for_features(_features_from_source(aoi_geojson))
    buildings = _arcpy_records(_feature_collection(raw_features), metric)
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


def _clip_features_open_source(raw_features, aoi_geojson):
    buildings = dict_to_gdf(_feature_collection(raw_features))
    aoi = dict_to_gdf(aoi_geojson)
    if buildings is None or buildings.empty or aoi is None or aoi.empty:
        return []

    metric = _metric_crs_for_gdf(aoi)
    buildings_m = buildings.to_crs(metric)
    aoi_m = aoi.to_crs(metric)
    clipped = gpd.clip(buildings_m, aoi_m)
    clipped = clipped[clipped.geometry.notna() & ~clipped.geometry.is_empty]
    if clipped.empty:
        return []
    clipped = clipped.to_crs("EPSG:4326")
    return json.loads(clipped.to_json()).get("features", [])


def _feature_bounds(feature):
    try:
        return _bounds_from_features([feature])
    except Exception:
        return None


def _bbox_intersects(a, b):
    return not (a[2] < b[0] or a[0] > b[2] or a[3] < b[1] or a[1] > b[3])


def _clip_features_bbox(raw_features, aoi_geojson):
    aoi_bbox = _bounds_from_features(_features_from_source(aoi_geojson))
    clipped = []
    for feature in raw_features:
        bbox = _feature_bounds(feature)
        if bbox and _bbox_intersects(bbox, aoi_bbox):
            clipped.append(feature)
    return clipped


def fetch_buildings_for_aoi(aoi_geojson):
    started = time.time()
    bbox = _bounds_from_aoi(aoi_geojson)
    query = _overpass_query(bbox)

    raw = _call_overpass(query)
    raw_features = _elements_to_features(raw.get("elements", []))
    if not raw_features:
        return {
            "status": "NoData",
            "stage": "fetch_buildings",
            "building_count": 0,
            "message": "Overpass returned no building footprints for this AOI.",
            "query_bbox": bbox,
            "gis_backend": _preferred_backend(),
            "elapsed_ms": round((time.time() - started) * 1000),
        }

    clipped_features = None
    clip_backend = None
    fallback_errors = []

    if HAS_ARCPY:
        try:
            clipped_features = _clip_features_arcpy(raw_features, aoi_geojson)
            clip_backend = "geoscene_arcpy"
        except Exception as exc:
            fallback_errors.append(f"geoscene_arcpy clip: {exc}")
            print(f"ArcPy clip failed, falling back: {traceback.format_exc()}")

    if (clipped_features is None or not clipped_features) and HAS_OPEN_SOURCE:
        try:
            open_source_features = _clip_features_open_source(raw_features, aoi_geojson)
            clipped_features = open_source_features
            clip_backend = "open_source_geopandas"
        except Exception as exc:
            fallback_errors.append(f"open_source_geopandas clip: {exc}")
            print(f"GeoPandas clip failed, falling back: {traceback.format_exc()}")

    if clipped_features is None:
        clipped_features = _clip_features_bbox(raw_features, aoi_geojson)
        clip_backend = "standard_library_bbox"

    if not clipped_features:
        return {
            "status": "NoData",
            "stage": "fetch_buildings",
            "building_count": 0,
            "message": "Buildings were fetched, but none intersected the AOI after clipping.",
            "raw_count": len(raw_features),
            "query_bbox": bbox,
            "gis_backend": clip_backend or _preferred_backend(),
            "fallback_errors": fallback_errors,
        }

    buildings_geojson = _feature_collection(clipped_features)
    result = {
        "status": "Success",
        "stage": "fetch_buildings",
        "building_count": int(len(clipped_features)),
        "raw_count": int(len(raw_features)),
        "buildings": buildings_geojson,
        "aoi": aoi_geojson,
        "source": "openstreetmap_overpass",
        "gis_backend": clip_backend or _preferred_backend(),
        "query_bbox": bbox,
        "elapsed_ms": round((time.time() - started) * 1000),
    }
    if fallback_errors:
        result["fallback_errors"] = fallback_errors
    return result


def _estimate_missing_floors(row, footprint_area):
    btype = str(row.get("building") or "").lower()

    if btype in {"apartments", "residential", "dormitory", "hotel"}:
        base = 12
    elif btype in {"office", "commercial", "retail"}:
        base = 10
    elif btype in {"university", "college", "hospital", "school"}:
        base = 7
    elif btype in {"kindergarten"}:
        base = 4
    elif btype in {"house", "bungalow", "detached", "semidetached_house", "garage", "garages", "hut", "shed"}:
        base = 1
    elif btype in {"industrial", "warehouse", "service", "public"}:
        base = 4
    else:
        base = 7

    if footprint_area >= 15000:
        base = max(base, 14)
    elif footprint_area >= 8000:
        base = max(base, 11)
    elif footprint_area >= 3000:
        base = max(base, 8)
    elif footprint_area >= 1000:
        base = max(base, 6)
    elif footprint_area >= 300:
        base = max(base, 5)
    elif footprint_area <= 80:
        base = min(base, 2)

    return base


def _floor_for_record(props, footprint_area):
    for field in ("floors", "building:levels", "levels"):
        value = _parse_number(props.get(field))
        if value is not None and value > 0:
            return min(max(value, 1), 80), field

    for field in ("height", "render_height", "HEIGHT", "H_AVG"):
        height = _parse_number(props.get(field))
        if height is not None and height > 0:
            return min(max(math.ceil(height / 3.5), 1), 80), field

    return min(max(_estimate_missing_floors(props, footprint_area), 1), 80), "estimated"


def _value_counts_records(records, field, limit=12):
    values = []
    for record in records:
        value = record.get("properties", {}).get(field)
        values.append("unknown" if _is_missing_value(value) else str(value))
    return {str(k): int(v) for k, v in Counter(values).most_common(limit)}


def _height_stats_records(records):
    for field in ("height", "render_height", "HEIGHT", "H_AVG"):
        values = [
            _parse_number(record.get("properties", {}).get(field))
            for record in records
        ]
        values = [value for value in values if value is not None]
        if values:
            return {
                "avg": round(sum(values) / len(values), 1),
                "max": round(max(values), 1),
                "min": round(min(values), 1),
            }
    return {}


def _build_metrics_result(records, site_area, buffer_area, backend, metric_crs=None, fallback_errors=None):
    building_count = int(len(records))
    if building_count == 0:
        return {
            "status": "NoData",
            "stage": "urban_metrics",
            "far": 0,
            "building_count": 0,
            "site_area": round(site_area, 2),
            "site_area_sqm": round(site_area, 2),
            "message": "No buildings intersected the AOI.",
            "gis_backend": backend,
        }
    if site_area <= 0:
        return {
            "status": "Fail",
            "stage": "urban_metrics",
            "far": 0,
            "building_count": building_count,
            "message": "AOI area is zero or invalid.",
            "gis_backend": backend,
        }

    floors = []
    floor_sources = []
    footprint_areas = []
    for record in records:
        footprint_area = float(record.get("footprint_area") or 0)
        floor, source = _floor_for_record(record.get("properties", {}), footprint_area)
        footprint_areas.append(footprint_area)
        floors.append(floor)
        floor_sources.append(source)

    total_const_area = sum(area * floor for area, floor in zip(footprint_areas, floors))
    lower_bound_const_area = sum(
        area * (1 if source == "estimated" else floor)
        for area, floor, source in zip(footprint_areas, floors, floor_sources)
    )
    footprint_total = sum(footprint_areas)
    far = total_const_area / site_area
    lower_bound_far = lower_bound_const_area / site_area

    source_counts = Counter(floor_sources)
    measured_count = sum(int(source_counts.get(field, 0)) for field in ("floors", "building:levels", "levels"))
    height_count = sum(int(source_counts.get(field, 0)) for field in ("height", "render_height", "HEIGHT", "H_AVG"))
    estimated_count = int(source_counts.get("estimated", 0))
    measured_ratio = measured_count / building_count if building_count else 0
    height_ratio = height_count / building_count if building_count else 0
    estimated_ratio = estimated_count / building_count if building_count else 0
    confidence = "high" if measured_ratio + height_ratio >= 0.7 else "medium" if measured_ratio + height_ratio >= 0.3 else "low"
    rounded_floor_counts = Counter(str(int(round(value))) for value in floors)

    floor_stats = {
        "avg": round(sum(floors) / len(floors), 1),
        "max": int(max(floors)),
        "min": int(min(floors)),
        "source": "mixed",
        "source_counts": {str(k): int(v) for k, v in source_counts.items()},
        "measured_ratio": round(measured_ratio, 3),
        "height_inferred_ratio": round(height_ratio, 3),
        "estimated_ratio": round(estimated_ratio, 3),
        "confidence": confidence,
        "counts": {str(k): int(v) for k, v in sorted(rounded_floor_counts.items(), key=lambda item: int(item[0]))[:12]},
    }

    warnings = []
    if far <= 0:
        warnings.append("FAR is zero")
    if far > 12:
        warnings.append("FAR is unusually high; check AOI size and floor attributes")
    if footprint_total > site_area * 1.05:
        warnings.append("building footprint area exceeds AOI area; check geometry clipping")
    if estimated_ratio > 0.5:
        warnings.append("More than half of building floors were estimated because OSM level/height attributes are sparse")
    if backend == "standard_library_bbox":
        warnings.append("Only bbox filtering was available; install/use ArcPy or GeoPandas for exact clipping")

    result = {
        "status": "Success",
        "stage": "urban_metrics",
        "far": round(far, 3),
        "far_method": "regional_gross_far_with_geoscene_priority_and_missing_floor_estimation",
        "lower_bound_far": round(lower_bound_far, 3),
        "building_count": building_count,
        "site_area": round(site_area, 2),
        "site_area_sqm": round(site_area, 2),
        "building_area": round(total_const_area, 2),
        "total_const_area_sqm": round(total_const_area, 2),
        "lower_bound_building_area_sqm": round(lower_bound_const_area, 2),
        "footprint_area_sqm": round(footprint_total, 2),
        "building_density": round(footprint_total / site_area * 100.0, 2),
        "height_stats": _height_stats_records(records),
        "floor_stats": floor_stats,
        "floor_confidence": confidence,
        "building_types": _value_counts_records(records, "building"),
        "roof_types": _value_counts_records(records, "roof:shape"),
        "materials": _value_counts_records(records, "building:material"),
        "warnings": warnings,
        "gis_backend": backend,
    }
    if metric_crs:
        result["metric_crs"] = metric_crs
    if buffer_area > 0:
        result["buffer_area_sqm"] = round(buffer_area, 2)
    if fallback_errors:
        result["fallback_errors"] = fallback_errors
    return result


def _records_from_gdf(gdf_obj):
    records = []
    for _, row in gdf_obj.iterrows():
        geom = row.geometry
        if geom is None or geom.is_empty:
            continue
        props = _json_safe_dict({key: value for key, value in row.items() if key != "geometry"})
        records.append({
            "properties": props,
            "footprint_area": float(geom.area),
        })
    return records


def _calculate_metrics_open_source(payload):
    if not HAS_OPEN_SOURCE:
        raise RuntimeError("GeoPandas/Shapely/Pandas are not available.")

    buildings_raw = payload.get("buildings")
    aoi_raw = payload.get("aoi")

    buildings = dict_to_gdf(buildings_raw)
    if buildings is None or buildings.empty:
        return {
            "status": "Fail",
            "stage": "urban_metrics",
            "far": 0,
            "building_count": 0,
            "message": "No valid building polygons were provided.",
            "gis_backend": "open_source_geopandas",
        }

    aoi = dict_to_gdf(aoi_raw) if aoi_raw else None
    metric = _metric_crs_for_gdf(aoi if aoi is not None and not aoi.empty else buildings)
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
    records = _records_from_gdf(final)
    return _build_metrics_result(records, site_area, buffer_area, "open_source_geopandas", metric_crs=metric)


def _calculate_metrics_arcpy(payload):
    if not HAS_ARCPY:
        raise RuntimeError("ArcPy is not available.")

    buildings_raw = payload.get("buildings")
    aoi_raw = payload.get("aoi")
    building_features = _features_from_source(buildings_raw)
    if not building_features:
        return {
            "status": "Fail",
            "stage": "urban_metrics",
            "far": 0,
            "building_count": 0,
            "message": "No valid building polygons were provided.",
            "gis_backend": "geoscene_arcpy",
        }

    aoi_features = _features_from_source(aoi_raw) if aoi_raw else []
    metric = _metric_crs_for_features(aoi_features or building_features)
    building_records = _arcpy_records(_feature_collection(building_features), metric)
    if not building_records:
        return {
            "status": "Fail",
            "stage": "urban_metrics",
            "far": 0,
            "building_count": 0,
            "message": "No valid building polygons were provided.",
            "gis_backend": "geoscene_arcpy",
        }

    buffer_area = 0.0
    final_records = []
    if aoi_features:
        aoi_records = _arcpy_records(_feature_collection(aoi_features), metric)
        aoi_union = _union_arcpy_geometries(aoi_records)
        site_area = _arcpy_area(aoi_union)
        buffer_area = site_area
        for record in building_records:
            clipped = _intersect_arcpy_polygon(record["geometry"], aoi_union)
            area = _arcpy_area(clipped)
            if area <= 0:
                continue
            final_records.append({
                "properties": record["properties"],
                "footprint_area": area,
            })
    else:
        site_area = sum(_arcpy_area(record["geometry"]) for record in building_records)
        for record in building_records:
            final_records.append({
                "properties": record["properties"],
                "footprint_area": _arcpy_area(record["geometry"]),
            })

    return _build_metrics_result(final_records, site_area, buffer_area, "geoscene_arcpy", metric_crs=metric)


def calculate_metrics(payload):
    fallback_errors = []

    if HAS_ARCPY:
        try:
            result = _calculate_metrics_arcpy(payload)
            if result.get("status") == "Success" or not HAS_OPEN_SOURCE:
                return result
            fallback_errors.append(f"geoscene_arcpy returned {result.get('status')}: {result.get('message')}")
        except Exception as exc:
            fallback_errors.append(f"geoscene_arcpy: {exc}")
            print(f"ArcPy metrics failed, falling back: {traceback.format_exc()}")

    if HAS_OPEN_SOURCE:
        try:
            result = _calculate_metrics_open_source(payload)
            if fallback_errors:
                result["fallback_errors"] = fallback_errors
            return result
        except Exception as exc:
            fallback_errors.append(f"open_source_geopandas: {exc}")
            print(f"GeoPandas metrics failed: {traceback.format_exc()}")

    return {
        "status": "Fail",
        "stage": "urban_metrics",
        "far": 0,
        "building_count": 0,
        "message": "No GIS geometry backend is available. Run this service with the GeoScene/ArcPy interpreter or install geopandas/shapely/pandas.",
        "gis_backend": _preferred_backend(),
        "fallback_errors": fallback_errors,
        "runtime": runtime_status(),
    }


def _load_demo_json(file_name):
    import os
    from pathlib import Path

    base_dir = Path(__file__).resolve().parent
    configured_dir = os.getenv("GIS_DEMO_CASE_DIR")
    candidates = []
    if configured_dir:
        candidates.append(Path(configured_dir) / file_name)
    candidates.extend([
        base_dir / "demo-case" / file_name,
        base_dir / "src" / "main" / "resources" / "static" / "demo-case" / file_name,
        base_dir.parent / "lc4j-1(1)" / "src" / "main" / "resources" / "static" / "demo-case" / file_name,
    ])

    for path in candidates:
        if path.is_file():
            with path.open("r", encoding="utf-8-sig") as stream:
                return json.load(stream)

    searched = ", ".join(str(path) for path in candidates)
    raise FileNotFoundError(f"Demo case file {file_name} was not found. Searched: {searched}")


def _polygon_area_sqm(feature_collection, aoi):
    features = _features_from_source(feature_collection)
    if not features:
        return 0.0
    if HAS_OPEN_SOURCE:
        gdf_obj = dict_to_gdf(_feature_collection(features))
        aoi_gdf = dict_to_gdf(aoi)
        metric = _metric_crs_for_gdf(aoi_gdf if aoi_gdf is not None and not aoi_gdf.empty else gdf_obj)
        result = gdf_obj.to_crs(metric)
        if aoi_gdf is not None and not aoi_gdf.empty:
            result = gpd.clip(result, aoi_gdf.to_crs(metric))
        return float(result.geometry.area.sum())
    if HAS_ARCPY:
        metric = _metric_crs_for_features(_features_from_source(aoi) or features)
        records = _arcpy_records(_feature_collection(features), metric)
        return sum(_arcpy_area(record["geometry"]) for record in records)
    return 0.0


def _evaluate_rule(metric_name, value, rule):
    passed = True
    if "min" in rule and value < float(rule["min"]):
        passed = False
    if "max" in rule and value > float(rule["max"]):
        passed = False
    return {
        "metric": metric_name,
        "value": round(float(value), 4),
        "min": rule.get("min"),
        "max": rule.get("max"),
        "unit": rule.get("unit", ""),
        "passed": passed,
    }


def _evaluate_demo_metrics(metrics, green_rate, rule_set):
    rules = rule_set["rules"]
    height_stats = metrics.get("height_stats") or {}
    max_height = _safe_float(height_stats.get("max"), 0.0) or 0.0
    values = {
        "far": _safe_float(metrics.get("far"), 0.0) or 0.0,
        "buildingDensity": _safe_float(metrics.get("building_density"), 0.0) or 0.0,
        "buildingHeight": max_height,
        "greenRate": green_rate,
    }
    evaluations = [_evaluate_rule(name, values[name], rule) for name, rule in rules.items()]
    return evaluations, all(item["passed"] for item in evaluations)


def _problem_buildings(buildings, rules):
    max_height = _safe_float(rules.get("buildingHeight", {}).get("max"), None)
    far_excess = False
    results = []
    for feature in _features_from_source(buildings):
        props = dict(feature.get("properties") or {})
        height = _safe_float(props.get("height"), None)
        levels = _safe_float(props.get("building:levels"), _safe_float(props.get("levels"), None))
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
    return _feature_collection(results), far_excess


def _optimized_buildings(buildings, rules):
    max_height = _safe_float(rules.get("buildingHeight", {}).get("max"), 54.0) or 54.0
    max_levels = max(1, int(max_height // 3.0))
    optimized = []
    changes = []
    for feature in _features_from_source(buildings):
        props = dict(feature.get("properties") or {})
        original_levels = int(_safe_float(props.get("building:levels"), _safe_float(props.get("levels"), 1)) or 1)
        original_height = _safe_float(props.get("height"), original_levels * 3.0) or original_levels * 3.0
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
    return _feature_collection(optimized), changes


def _proposed_green_space():
    return {
        "type": "FeatureCollection",
        "features": [{
            "type": "Feature",
            "properties": {"id": "G02", "name": "建议新增公共绿地", "scenario": "optimized"},
            "geometry": {
                "type": "Polygon",
                "coordinates": [[[121.47215,31.23089],[121.47585,31.23089],[121.47585,31.23121],[121.47215,31.23121],[121.47215,31.23089]]],
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

def evaluate_context_case(payload):
    aoi = payload.get("aoi")
    buildings = payload.get("buildings")
    if not aoi:
        raise ValueError("当前地图上下文缺少 AOI，请先绘制地块或选择地点周边范围")
    if not buildings or not buildings.get("features"):
        fetched = fetch_buildings_for_aoi(aoi)
        if fetched.get("status") != "Success" or not (fetched.get("buildings") or {}).get("features"):
            raise ValueError("当前 AOI 内没有可用于 CityEngine 的建筑轮廓")
        buildings = fetched["buildings"]

    current_metrics = calculate_metrics({"aoi": aoi, "buildings": buildings})
    if current_metrics.get("status") != "Success":
        return current_metrics

    requirements = payload.get("requirements") or {}
    max_height = _safe_float(requirements.get("maxBuildingHeight"), None)
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
        "problemBuildings": problems,
        "cityEngineJob": cityengine_job,
        "aoi": aoi,
        "buildings": buildings,
        "commands": [],
        "dataQuality": {
            "mode": "runtime_context",
            "source": "current map context",
            "estimatedFields": [],
            "warning": rule_set["source"],
        },
        "message": "当前地图 AOI 与真实建筑已提交给 CityEngine。",
    }


@app.post("/analysis/cityengine/plan-context")
async def plan_current_context(payload: dict = Body(...)):
    try:
        return evaluate_context_case(payload)
    except Exception as exc:
        print(f"Context planning failed: {traceback.format_exc()}")
        return {"status": "Error", "stage": "context_planning", "message": str(exc)}

@app.post("/analysis/demo_case/evaluate")
async def evaluate_planning_demo(payload: dict = Body(default={})):
    try:
        return evaluate_demo_case(payload.get("requirements"), payload.get("ragContext", ""), payload.get("userRequest", ""))
    except Exception as exc:
        print(f"Demo case evaluation failed: {traceback.format_exc()}")
        return {"status": "Error", "stage": "planning_evaluation", "message": str(exc)}

@app.get("/analysis/geoscene/publishing")
async def get_geoscene_publishing_status():
    return publishing_status()


@app.get("/analysis/cityengine/runtime")
async def get_cityengine_runtime():
    return cityengine_runtime_status()


@app.get("/analysis/cityengine/jobs/{job_id}")
async def get_cityengine_job(job_id: str):
    return ensure_cityengine_published(job_id, read_cityengine_job(job_id))

@app.get("/analysis/cityengine/jobs/{job_id}/wait")
async def wait_cityengine_job(job_id: str, timeout: int = 180):
    deadline = time.time() + max(1, min(timeout, 600))
    while time.time() < deadline:
        result = read_cityengine_job(job_id)
        if result.get("status") == "completed":
            result = ensure_cityengine_published(job_id, result)
        if result.get("status") in {"completed", "failed", "not_found"}:
            return result
        await __import__("asyncio").sleep(2)
    result = read_cityengine_job(job_id)
    if result.get("status") == "completed":
        result = ensure_cityengine_published(job_id, result)
    result["waitTimedOut"] = True
    return result


@app.get("/analysis/cityengine/jobs/{job_id}/download/{format_name}")
async def download_cityengine_output(job_id: str, format_name: str):
    result = read_cityengine_job(job_id)
    if result.get("status") != "completed":
        raise HTTPException(status_code=409, detail="CityEngine job is not completed")
    outputs = result.get("outputs") or {}
    output = outputs.get(format_name.lower())
    if not output:
        raise HTTPException(status_code=404, detail="Requested output format was not generated")
    output_path = Path(output).resolve()
    allowed_root = Path(cityengine_runtime_status()["project"]).resolve() / "models" / "generated"
    if allowed_root not in output_path.parents and output_path != allowed_root:
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
    return runtime_status()


@app.post("/analysis/urban_metrics")
async def calculate_urban_metrics(payload: dict = Body(...)):
    try:
        return calculate_metrics(payload)
    except Exception as exc:
        print(f"urban_metrics failed: {traceback.format_exc()}")
        return {"status": "Error", "stage": "urban_metrics", "far": 0, "message": str(exc)}


@app.post("/analysis/buffer")
async def execute_buffer(payload: dict = Body(...)):
    try:
        feature = create_buffer_feature(payload.get("lon"), payload.get("lat"), payload.get("radius", 500))
        return _feature_collection([feature])
    except Exception as exc:
        print(f"buffer failed: {traceback.format_exc()}")
        return {"status": "Error", "stage": "buffer", "message": str(exc)}


@app.post("/analysis/fetch_buildings")
async def fetch_buildings(payload: dict = Body(...)):
    try:
        aoi = payload.get("aoi")
        if not aoi:
            aoi = {
                "type": "Feature",
                "geometry": create_buffer_feature(
                    payload.get("lon"), payload.get("lat"), payload.get("radius", 500)
                )["geometry"],
                "properties": {"source": "server_buffer"},
            }
        return fetch_buildings_for_aoi(aoi)
    except Exception as exc:
        print(f"fetch_buildings failed: {traceback.format_exc()}")
        return {"status": "Error", "stage": "fetch_buildings", "building_count": 0, "message": str(exc)}


@app.post("/analysis/analyze_area")
async def analyze_area(payload: dict = Body(...)):
    """Create/accept an AOI, fetch real OSM buildings, then calculate metrics."""
    try:
        explicit_aoi = payload.get("aoi")
        radius = float(payload.get("radius", 500) or 500)
        radii = [radius] if explicit_aoi else [radius, radius * 1.5, radius * 2]
        last_fetch = None

        for attempt, current_radius in enumerate(radii, start=1):
            if explicit_aoi:
                aoi = explicit_aoi
            else:
                feature = create_buffer_feature(payload.get("lon"), payload.get("lat"), current_radius)
                aoi = {
                    "type": "Feature",
                    "geometry": feature["geometry"],
                    "properties": feature["properties"],
                }

            fetch_result = fetch_buildings_for_aoi(aoi)
            last_fetch = fetch_result
            if fetch_result.get("status") != "Success":
                continue

            metrics = calculate_metrics({
                "buildings": fetch_result.get("buildings"),
                "aoi": aoi,
            })
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
                        "longitude": float(payload.get("lon")) if payload.get("lon") is not None else None,
                        "latitude": float(payload.get("lat")) if payload.get("lat") is not None else None,
                        "radius": current_radius,
                    },
                })
                return metrics

        fallback = last_fetch or {"status": "NoData", "message": "No fetch attempt was completed."}
        fallback.update({
            "far": 0,
            "stage": "analyze_area",
            "message": fallback.get("message", "No valid building data was found after retries."),
            "runtime": runtime_status(),
        })
        return fallback
    except Exception as exc:
        print(f"analyze_area failed: {traceback.format_exc()}")
        return {"status": "Error", "stage": "analyze_area", "far": 0, "building_count": 0, "message": str(exc)}


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="127.0.0.1", port=8000)






