# -*- coding: utf-8 -*-
"""Service layer: orchestrates model + adapter to fulfill analysis use-cases.

No FastAPI / route definitions here. These functions take plain payloads and
return plain dicts (or raise ValueError on bad input), so they are directly
unit-testable and reusable from the router.
"""
import json
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

    buildings_geojson = model._feature_collection(clipped_features)
    result = {
        "status": "Success",
        "stage": "fetch_buildings",
        "building_count": int(len(clipped_features)),
        "raw_count": int(len(raw_features)),
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
