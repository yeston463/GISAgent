# -*- coding: utf-8 -*-
"""Pure / backend-independent model layer for the GIS engine.

Everything here is deterministic and has no dependency on FastAPI, the CityEngine
bridge, GeoScene, ArcPy, GeoPandas, or the network. Functions operate on plain
Python/GeoJSON data and (optionally) Shapely, which degrades gracefully.

This module is safe to unit-test in a clean environment.
"""
import json
import math
import re
from collections import Counter

MAX_BUILDINGS = 3000
DEFAULT_STOREY_HEIGHT_M = 3.2
MIN_USABLE_FOOTPRINT_AREA_SQM = 25.0

try:
    from shapely.geometry import Point, Polygon, mapping, shape

    SHAPELY_IMPORT_ERROR = None
except Exception as exc:  # pragma: no cover - depends on environment
    Point = None
    Polygon = None
    mapping = None
    shape = None
    SHAPELY_IMPORT_ERROR = f"{type(exc).__name__}: {exc}"


# --------------------------------------------------------------------------- #
# Value / JSON helpers
# --------------------------------------------------------------------------- #
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


# --------------------------------------------------------------------------- #
# CRS / geometry normalization
# --------------------------------------------------------------------------- #
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
    union = wgs.geometry.union_all() if hasattr(wgs.geometry, "union_all") else wgs.unary_union
    centroid = union.centroid
    return _metric_crs(centroid.x, centroid.y)


# --------------------------------------------------------------------------- #
# OSM element parsing
# --------------------------------------------------------------------------- #
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


def _ring_area_sqm(coords):
    """Approximate a WGS84 ring area in square metres without a GIS backend."""
    if not isinstance(coords, list) or len(coords) < 4:
        return 0.0
    try:
        lat0 = sum(float(point[1]) for point in coords) / len(coords)
        x_scale = 111320.0 * math.cos(math.radians(lat0))
        y_scale = 110540.0
        projected = [(float(point[0]) * x_scale, float(point[1]) * y_scale) for point in coords]
    except (IndexError, TypeError, ValueError):
        return 0.0
    return abs(_ring_area(projected))


def _footprint_quality(feature, minimum_area_sqm=MIN_USABLE_FOOTPRINT_AREA_SQM):
    """Validate exported building footprints before metrics or CityEngine use.

    Spatial clipping can turn a real building crossing an AOI edge into a tiny
    triangle.  Such a sliver is not a meaningful building for FAR or a 3D
    product, even though it remains a technically valid GeoJSON Polygon.
    """
    geometry = _normalize_geometry((feature or {}).get("geometry"))
    if not geometry or geometry.get("type") not in {"Polygon", "MultiPolygon"}:
        return False, "geometry is not a Polygon/MultiPolygon", 0.0

    polygons = [geometry.get("coordinates") or []] if geometry["type"] == "Polygon" else geometry.get("coordinates") or []
    total_area = 0.0
    usable_parts = 0
    for polygon in polygons:
        if not polygon or not isinstance(polygon[0], list):
            continue
        exterior = polygon[0]
        if len(exterior) < 4 or exterior[0] != exterior[-1]:
            continue
        try:
            vertices = {(float(point[0]), float(point[1])) for point in exterior[:-1]}
        except (IndexError, TypeError, ValueError):
            continue
        if len(vertices) < 3:
            continue
        area = _ring_area_sqm(exterior)
        if area <= 0:
            continue
        total_area += area
        usable_parts += 1

    if usable_parts == 0:
        return False, "polygon ring is open, degenerate, or has no measurable area", total_area
    if total_area < float(minimum_area_sqm):
        return False, f"footprint area {total_area:.2f} sqm is below the {minimum_area_sqm:g} sqm reliability threshold", total_area
    return True, "", total_area


def _filter_usable_building_footprints(features, minimum_area_sqm=MIN_USABLE_FOOTPRINT_AREA_SQM):
    usable = []
    rejected = []
    for feature in features or []:
        valid, reason, area_sqm = _footprint_quality(feature, minimum_area_sqm)
        if valid:
            usable.append(feature)
        else:
            rejected.append({"reason": reason, "area_sqm": round(area_sqm, 2)})
    return usable, rejected


# --------------------------------------------------------------------------- #
# Urban metrics (pure aggregation)
# --------------------------------------------------------------------------- #
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
            source = str(props.get("floorSource") or field)
            return min(max(value, 1), 80), source

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


def _vertical_for_record(props, footprint_area):
    """Return one explicit vertical model used by metrics and CityEngine."""
    floors, floor_source = _floor_for_record(props, footprint_area)
    for field in ("height", "render_height", "HEIGHT", "H_AVG"):
        height = _parse_number(props.get(field))
        if height is not None and height > 0:
            source = str(props.get("heightSource") or field)
            return {
                "floors": int(floors),
                "floor_source": floor_source,
                "height_m": round(min(height, 300.0), 1),
                "height_source": source,
                "estimated": bool(props.get("heightEstimated", False)),
            }

    inferred_from_levels = floor_source != "estimated"
    return {
        "floors": int(floors),
        "floor_source": floor_source,
        "height_m": round(min(floors * DEFAULT_STOREY_HEIGHT_M, 300.0), 1),
        "height_source": "levels_inferred" if inferred_from_levels else "building_type_estimated",
        "estimated": not inferred_from_levels,
    }


def _building_identifier(props, index):
    value = props.get("id") or props.get("osm_id") or props.get("OBJECTID") or props.get("objectid")
    return str(value) if value is not None else f"feature-{index + 1}"


def _height_stats(verticals):
    if not verticals:
        return {}
    heights = [item["height_m"] for item in verticals]
    source_counts = Counter(item["height_source"] for item in verticals)
    measured_count = sum(int(source_counts.get(field, 0)) for field in (
        "height", "render_height", "HEIGHT", "H_AVG", "scene_mesh_z_range", "scene_attribute_height"
    ))
    levels_count = int(source_counts.get("levels_inferred", 0))
    estimated_count = int(source_counts.get("building_type_estimated", 0))
    count = len(verticals)
    measured_ratio = measured_count / count
    levels_ratio = levels_count / count
    estimated_ratio = estimated_count / count
    confidence = "high" if measured_ratio >= 0.7 else "medium" if measured_ratio + levels_ratio >= 0.7 else "low"
    return {
        "avg": round(sum(heights) / count, 1),
        "max": round(max(heights), 1),
        "min": round(min(heights), 1),
        "source_counts": {str(key): int(value) for key, value in source_counts.items()},
        "measured_ratio": round(measured_ratio, 3),
        "levels_inferred_ratio": round(levels_ratio, 3),
        "estimated_ratio": round(estimated_ratio, 3),
        "confidence": confidence,
    }


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
    verticals = []
    for index, record in enumerate(records):
        footprint_area = float(record.get("footprint_area") or 0)
        props = record.get("properties", {})
        vertical = _vertical_for_record(props, footprint_area)
        floor, source = vertical["floors"], vertical["floor_source"]
        footprint_areas.append(footprint_area)
        floors.append(floor)
        floor_sources.append(source)
        verticals.append({
            "building_id": _building_identifier(props, index),
            "floors": floor,
            "floor_source": source,
            "height_m": vertical["height_m"],
            "height_source": vertical["height_source"],
            "estimated": vertical["estimated"],
        })

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
    height_count = sum(int(source_counts.get(field, 0)) for field in (
        "height", "render_height", "HEIGHT", "H_AVG", "scene_mesh_z_range", "scene_attribute_height"
    ))
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
        "height_stats": _height_stats(verticals),
        "floor_stats": floor_stats,
        "floor_confidence": confidence,
        "vertical_profile": verticals,
        "data_provenance": {
            "geometry_sources": _value_counts_records(records, "geometrySource"),
            "height_sources": _value_counts_records(verticals, "height_source"),
            "floor_sources": _value_counts_records(verticals, "floor_source"),
            "geometry_backend": backend,
            "height_confidence": _height_stats(verticals).get("confidence", "unknown"),
            "estimated_vertical_ratio": round(estimated_ratio, 3),
        },
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


# --------------------------------------------------------------------------- #
# Advanced analysis primitives (pure)
# --------------------------------------------------------------------------- #
def _feature_centroid(feature):
    coords = list(_iter_coordinates((feature or {}).get("geometry")))
    if not coords:
        return None
    return (
        sum(coord[0] for coord in coords) / len(coords),
        sum(coord[1] for coord in coords) / len(coords),
    )


def _feature_height(feature):
    props = (feature or {}).get("properties") or {}
    vertical = _vertical_for_record(props, 0.0)
    source = vertical["height_source"]
    return min(float(vertical["height_m"]), 400.0), source


def _distance_and_bearing(origin, target):
    lon1, lat1 = origin
    lon2, lat2 = target
    mean_lat = math.radians((lat1 + lat2) / 2.0)
    dx = (lon2 - lon1) * 111320.0 * max(math.cos(mean_lat), 0.01)
    dy = (lat2 - lat1) * 111320.0
    return math.hypot(dx, dy), (math.degrees(math.atan2(dx, dy)) + 360.0) % 360.0


def _solar_position(latitude, day_of_year, hour):
    lat = math.radians(latitude)
    declination = math.radians(23.44 * math.sin(math.radians((360.0 / 365.0) * (284 + day_of_year))))
    hour_angle = math.radians(15.0 * (hour - 12.0))
    sin_altitude = math.sin(lat) * math.sin(declination) + math.cos(lat) * math.cos(declination) * math.cos(hour_angle)
    altitude = math.asin(max(-1.0, min(1.0, sin_altitude)))
    cos_altitude = max(math.cos(altitude), 1e-6)
    sin_azimuth = -math.sin(hour_angle) * math.cos(declination) / cos_altitude
    cos_azimuth = (math.sin(declination) - math.sin(lat) * math.sin(altitude)) / max(math.cos(lat) * cos_altitude, 1e-6)
    azimuth = (math.degrees(math.atan2(sin_azimuth, cos_azimuth)) + 360.0) % 360.0
    return math.degrees(altitude), azimuth


def _convex_hull(points):
    unique = sorted(set((float(x), float(y)) for x, y in points))
    if len(unique) <= 2:
        return unique

    def cross(origin, a, b):
        return (a[0] - origin[0]) * (b[1] - origin[1]) - (a[1] - origin[1]) * (b[0] - origin[0])

    lower = []
    for point in unique:
        while len(lower) >= 2 and cross(lower[-2], lower[-1], point) <= 0:
            lower.pop()
        lower.append(point)
    upper = []
    for point in reversed(unique):
        while len(upper) >= 2 and cross(upper[-2], upper[-1], point) <= 0:
            upper.pop()
        upper.append(point)
    return lower[:-1] + upper[:-1]


def _shadow_feature(feature, shadow_length, shadow_bearing, latitude, hour):
    original = list(_iter_coordinates(feature.get("geometry")))
    if len(original) < 3:
        return None
    radians = math.radians(shadow_bearing)
    dx = shadow_length * math.sin(radians)
    dy = shadow_length * math.cos(radians)
    lon_scale = 111320.0 * max(math.cos(math.radians(latitude)), 0.01)
    shifted = [(lon + dx / lon_scale, lat + dy / 111320.0) for lon, lat in original]
    hull = _convex_hull(original + shifted)
    if len(hull) < 3:
        return None
    hull.append(hull[0])
    props = dict(feature.get("properties") or {})
    props.update({"analysis": "sunlight_shadow_screening", "hour": hour, "shadowLengthM": round(shadow_length, 1)})
    return {"type": "Feature", "properties": props,
            "geometry": {"type": "Polygon", "coordinates": [[list(point) for point in hull]]}}


# --------------------------------------------------------------------------- #
# Rule evaluation (pure)
# --------------------------------------------------------------------------- #
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


# --------------------------------------------------------------------------- #
# Overpass query builder (pure string construction)
# --------------------------------------------------------------------------- #
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
    out tags geom qt;
    """


# --------------------------------------------------------------------------- #
# Standard-library buffer fallback (pure)
# --------------------------------------------------------------------------- #
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
