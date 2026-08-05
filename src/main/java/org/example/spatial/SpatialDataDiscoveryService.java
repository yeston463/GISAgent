package org.example.spatial;

import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;

/** Read-only source recommender. Never downloads or substitutes data. */
@Service
public class SpatialDataDiscoveryService {
    public List<Map<String, Object>> recommend(List<String> missing) {
        return missing.stream().map(this::source).toList();
    }
    private Map<String, Object> source(String key) {
        return switch (key) {
            case "aoi" -> Map.of("dataset", key, "source", "user_draw_or_geojson", "action", "绘制 AOI 或上传 GeoJSON", "required", true);
            case "buildings" -> Map.of("dataset", key, "source", "OSM_or_authoritative_building_footprints", "action", "上传建筑面和高度/层数字段", "required", true);
            case "dem" -> Map.of("dataset", key, "source", "authoritative_DEM_or_ArcGIS_ground", "action", "上传覆盖 AOI 的 DEM", "required", true);
            case "rainfall_scenario" -> Map.of("dataset", key, "source", "design_storm_or_user_provided", "action", "提供雨量、历时、重现期", "required", true);
            case "drainage_network" -> Map.of("dataset", key, "source", "municipal_drainage_network", "action", "可选：上传排水管网", "required", false);
            default -> Map.of("dataset", key, "source", "user_provided", "action", "提供可追溯空间数据", "required", true);
        };
    }
}
