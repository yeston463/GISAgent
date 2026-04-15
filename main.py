# -*- coding: utf-8 -*-
from fastapi import FastAPI, Body, HTTPException
import geopandas as gpd
import pandas as pd
from shapely.geometry import Polygon, shape
import json

app = FastAPI(title="Esri Cup Professional GIS Engine")


def dict_to_gdf(data_source):
    """
    通用转换工具：支持 FeatureCollection 或单个 Feature
    """
    if not data_source:
        return None

    # 提取 features 列表
    if isinstance(data_source, dict) and data_source.get("type") == "FeatureCollection":
        features = data_source.get("features", [])
    elif isinstance(data_source, dict) and data_source.get("type") == "Feature":
        features = [data_source]
    else:
        return None

    if not features:
        return None

    geoms = []
    attributes = []

    for f in features:
        try:
            g = f.get("geometry")
            if not g: continue

            # 这里的转换逻辑要极其强壮
            poly = None
            if g.get("type") == "Polygon":
                poly = Polygon(g["coordinates"][0])
            elif "rings" in g:  # 兼容原始 ArcGIS 格式
                poly = Polygon(g["rings"][0])

            if poly and poly.is_valid:
                geoms.append(poly)
                attributes.append(f.get("properties", {}))
        except Exception as e:
            print(f"解析要素失败: {e}")

    if not geoms:
        return None

    gdf = gpd.GeoDataFrame(attributes, geometry=geoms)

    # 坐标系判定逻辑
    # 取第一个坐标点，如果 > 500 通常是 Web Mercator (米)
    sample_x = geoms[0].exterior.coords[0][0]
    if abs(sample_x) > 500:
        gdf.crs = "EPSG:3857"
    else:
        gdf.crs = "EPSG:4326"
        gdf = gdf.to_crs(epsg=3857)  # 必须转为米制坐标系计算面积

    return gdf


@app.post("/analysis/urban_metrics")
async def calculate_urban_metrics(payload: dict = Body(...)):
    try:
        # 1. 转换并投影（dict_to_gdf 内部应确保转为 3857）
        gdf_bldgs = dict_to_gdf(payload.get("buildings"))
        gdf_aoi = dict_to_gdf(payload.get("aoi"))

        if gdf_bldgs is None or gdf_bldgs.empty:
            return {"far": 0, "building_count": 0, "status": "Error: Buildings data not received"}

        # 2. 【核心加固】：强制对齐坐标系
        if gdf_aoi is not None:
            # 哪怕 dict_to_gdf 转过了，这里也要再检查一遍，确保两者 CRS 绝对一致
            if gdf_bldgs.crs != gdf_aoi.crs:
                gdf_bldgs = gdf_bldgs.to_crs(gdf_aoi.crs)

            # 3. 执行空间裁剪 (Clip)
            print(f"开始裁剪：建筑总数 {len(gdf_bldgs)}，用地面积 {gdf_aoi.area.sum()}...")
            gdf_bldgs_clipped = gpd.clip(gdf_bldgs, gdf_aoi)

            # 更新统计
            bldg_count = len(gdf_bldgs_clipped)
            site_area = float(gdf_aoi.geometry.area.sum())
            gdf_final = gdf_bldgs_clipped
        else:
            # 自动模式（没画红线时）
            gdf_final = gdf_bldgs
            site_area = float(gdf_final.geometry.union_all().convex_hull.buffer(10).area)
            bldg_count = len(gdf_final)

        if bldg_count == 0:
            return {"far": 0, "building_count": 0, "site_area_sqm": site_area, "status": "No Buildings Found in AOI"}

        # 4. 计算总建筑面积
        total_const_area = 0
        floor_field = next((f for f in ['floors', 'building:levels', 'levels', 'height'] if f in gdf_final.columns),
                           None)

        if floor_field:
            gdf_final[floor_field] = pd.to_numeric(gdf_final[floor_field], errors='coerce').fillna(1)
            total_const_area = (gdf_final.geometry.area * gdf_final[floor_field]).sum()
        else:
            total_const_area = gdf_final.geometry.area.sum()

        return {
            "status": "Success",
            "far": round(total_const_area / site_area, 3),
            "building_count": bldg_count,
            "site_area_sqm": round(site_area, 2),
            "total_const_area_sqm": round(total_const_area, 2)
        }
    except Exception as e:
        print(f"🔥 Python 崩溃: {str(e)}")
        return {"far": 0, "error": str(e)}


@app.post("/analysis/buffer")
async def execute_buffer(payload: dict = Body(...)):
    print(f"🚀 Python 收到缓冲区请求: {payload}")
    try:
        from shapely.geometry import Point
        # 确保这里取的 key 和 Java Map 里的 payload.put("lon", ...) 一致
        lon = payload.get("lon")
        lat = payload.get("lat")
        radius = payload.get("radius", 500)

        if lon is None or lat is None:
            return {"error": "缺少坐标信息"}

        # 1. 创建中心点
        gdf = gpd.GeoDataFrame(geometry=[Point(lon, lat)], crs="EPSG:4326")
        # 2. 投影到米制坐标系 (3857) 计算真实几何圆
        buffer_gdf = gdf.to_crs(epsg=3857).buffer(radius)
        # 3. 转回 4326 返回标准 GeoJSON
        res_gdf = gpd.GeoDataFrame(geometry=buffer_gdf, crs="EPSG:3857").to_crs(epsg=4326)

        return json.loads(res_gdf.to_json())
    except Exception as e:
        print(f"🔥 Python 缓冲失败: {e}")
        return {"error": str(e)}
if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="127.0.0.1", port=8000)