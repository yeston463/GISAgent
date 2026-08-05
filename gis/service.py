# -*- coding: utf-8 -*-
"""Service layer: orchestrates model + adapter to fulfill analysis use-cases.

No FastAPI / route definitions here. These functions take plain payloads and
return plain dicts (or raise ValueError on bad input), so they are directly
unit-testable and reusable from the router.
"""
import json
import heapq
import math
import os
import time
import traceback
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
}


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
    except Exception as exc:
        return {"status": "Error", "stage": "spatial_plan", "message": str(exc)}
    return {"status": "Error", "stage": "spatial_plan", "message": "Operation did not produce a result"}


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

    raw = adapter._call_overpass(query)
    raw_features = model._elements_to_features(raw.get("elements", []))
    if not raw_features:
        return {
            "status": "NoData",
            "stage": "fetch_buildings",
            "building_count": 0,
            "message": "Overpass returned no building footprints for this AOI.",
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
            "message": "Buildings were fetched, but none intersected the AOI after clipping.",
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
            "message": "AOI only intersects degenerate or very small building fragments; no unreliable footprint will be used for metrics or CityEngine.",
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
            "message": "No valid building polygons were provided.",
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
            "message": "No valid building polygons were provided.",
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
            "message": "No valid building polygons were provided.",
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
            if result.get("status") == "Success" or not adapter.HAS_OPEN_SOURCE:
                return result
            fallback_errors.append(f"geoscene_arcpy returned {result.get('status')}: {result.get('message')}")
        except Exception as exc:
            fallback_errors.append(f"geoscene_arcpy: {exc}")
            print(f"ArcPy metrics failed, falling back: {traceback.format_exc()}")

    if adapter.HAS_OPEN_SOURCE:
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
        "gis_backend": adapter._preferred_backend(),
        "fallback_errors": fallback_errors,
        "runtime": adapter.runtime_status(),
    }


# --------------------------------------------------------------------------- #
# Skyline / sunlight analysis
# --------------------------------------------------------------------------- #
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
        return {"status": "NoData", "analysis_type": "skyline", "message": "No buildings are available for skyline analysis"}
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
        "limitations": "Screening profile based on building centroids and attribute heights; terrain and true line-of-sight occlusion are not included.",
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
        return {"status": "NoData", "analysis_type": "sunlight", "message": "No buildings are available for sunlight analysis"}
    center = _analysis_center(payload, features)
    try:
        analysis_date = calendar_date.fromisoformat(str(payload.get("date"))) if payload.get("date") else calendar_date.today()
    except ValueError:
        return {"status": "Error", "analysis_type": "sunlight", "message": "date must use YYYY-MM-DD"}
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
        "limitations": "Uses local solar time and building attribute heights. Terrain, facade windows and regulatory duration rules are not included.",
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


def _risk_cell(lon, lat, score, level, depth_m, elevation):
    half_side_m = 28.0
    lon_delta = half_side_m / (111320.0 * max(math.cos(math.radians(lat)), 0.01))
    lat_delta = half_side_m / 111320.0
    ring = [
        [lon - lon_delta, lat - lat_delta], [lon + lon_delta, lat - lat_delta],
        [lon + lon_delta, lat + lat_delta], [lon - lon_delta, lat + lat_delta],
        [lon - lon_delta, lat - lat_delta],
    ]
    return {
        "type": "Feature",
        "properties": {
            "name": f"{level.title()} flood risk cell",
            "riskLevel": level,
            "riskScore": round(score, 1),
            "estimatedDepthM": round(depth_m, 3),
            "elevationM": round(elevation, 2),
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
            if dataset.width * dataset.height > 250000:
                return None
            band = dataset.read(1, masked=True)
            # Hydrologic routing needs a regular grid. We retain the raster's
            # topology and convert its centres to WGS84 only for rendering.
            if not dataset.crs or str(dataset.crs).upper() == "EPSG:4326":
                def coordinate(row, column):
                    return dataset.xy(row, column)
            else:
                def coordinate(row, column):
                    x, y = dataset.xy(row, column)
                    lon, lat = transform(dataset.crs, "EPSG:4326", [x], [y])
                    return lon[0], lat[0]
            origin_x, origin_y = coordinate(0, 0)
            x_next, _ = coordinate(0, min(1, dataset.width - 1))
            _, y_next = coordinate(min(1, dataset.height - 1), 0)
            values = [[None if getattr(band[row, column], "mask", False) else float(band[row, column])
                       for column in range(dataset.width)] for row in range(dataset.height)]
            return {
                "values": values, "rows": dataset.height, "columns": dataset.width,
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
    if isinstance(dem, dict) and dem.get("kind") == "raster" and dem.get("path"):
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
                "missing_data": ["aoi"], "message": "An AOI is required for flood risk screening."}
    grid = _hydrologic_grid(payload.get("dem"))
    if not grid or grid["rows"] < 3 or grid["columns"] < 3:
        return {"status": "NoData", "stage": "flood_analysis", "analysis_type": "flood",
                "missing_data": ["hydrologic_dem_grid"],
                "message": "A 3×3 or larger regular DEM grid is required. Upload ASC/GeoTIFF, or sample a regular ground grid."}
    scenario = payload.get("rainfall_scenario")
    rainfall_mm = _number_from_mapping(scenario, ("rainfallMm", "rainfall_mm", "rainfall", "depthMm"))
    if rainfall_mm is None or rainfall_mm <= 0:
        return {"status": "NoData", "stage": "flood_analysis", "analysis_type": "flood",
                "missing_data": ["rainfall_scenario"], "message": "A positive rainfall scenario is required."}

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
        dem_quality["warning"] = "Terrain relief is below 0.5 m across the sampled AOI; relative risk classes are sensitive to elevation noise."
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
            feature = _risk_cell(lon, lat, score, level, depth_m, elevation)
            feature["properties"].update({"filledElevationM": round(filled[row][column], 3),
                                             "depressionFillDepthM": round(depression_depth, 3),
                                             "d8Direction": directions[row][column],
                                             "flowAccumulationCells": contributing_cells,
                                             "drainageReduction": round(drainage[row][column], 3)})
            risk_features.append(feature)
            risk_by_sample.append((lon, lat, score, level, depth_m))

    affected_buildings = 0
    exposed_buildings = []
    for building in model._features_from_source(payload.get("buildings")):
        center = model._feature_centroid(building)
        if not center:
            continue
        nearest = min(risk_by_sample, key=lambda item: model._distance_and_bearing(center, item[:2])[0])
        if nearest[3] in ("high", "medium"):
            affected_buildings += 1
            exposed = dict(building)
            exposed["properties"] = dict(building.get("properties") or {})
            exposed["properties"].update({
                "name": exposed["properties"].get("name") or exposed["properties"].get("id") or "Affected building",
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
        "affected_building_count": affected_buildings, "risk_cells": risk_cells,
        "affected_buildings": model._feature_collection(exposed_buildings), "dem_quality": dem_quality,
        "data_source": "current_context", "method": "hydrologic_dem_priority_flood_d8_flow_accumulation",
        "hydrology": {"depressionFill": "priority_flood", "flowDirection": "D8",
                       "drainageNetworkApplied": has_drainage, "runoffCoefficient": 0.65},
        "limitations": "Hydrologic terrain screening uses DEM depression filling, D8 routing and proximity-based drainage reduction. It is not a calibrated 2D hydraulic model and does not represent pipe capacity, river stage, boundary inflow, roughness or time-varying inundation.",
    }
    result["commands"] = [{"action": "addGeoJsonLayer", "params": {
        "layerId": "flood-risk-screening", "title": "Flood risk screening", "style": "floodRisk",
        "visible": True, "data": risk_cells,
    }}]
    if exposed_buildings:
        result["commands"].append({"action": "addGeoJsonLayer", "params": {
            "layerId": "flood-exposed-buildings", "title": "Potentially affected buildings", "style": "floodExposure",
            "visible": True, "data": result["affected_buildings"],
        }})
    result["commands"].append({"action": "showAdvancedAnalysis", "params": {
        "analysisType": "flood", "title": "Flood risk screening", "rainfallMm": result["rainfall_mm"],
        "returnPeriodYears": return_period, "highRiskCellCount": result["high_risk_cell_count"],
        "mediumRiskCellCount": result["medium_risk_cell_count"], "affectedBuildingCount": affected_buildings,
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
        base_dir.parent / "lc4j-1(1)" / "src" / "main" / "resources" / "static" / "demo-case" / file_name,
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
