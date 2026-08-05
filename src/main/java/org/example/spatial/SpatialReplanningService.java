package org.example.spatial;

import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;

/** Deterministic failure recovery advice; does not introduce unapproved tools. */
@Service
public class SpatialReplanningService {
    public List<Map<String, Object>> replan(String capabilityId, Map<String, Object> result) {
        String message = String.valueOf(result.getOrDefault("message", ""));
        if ("flood_analysis".equals(capabilityId)) return List.of(Map.of("action", "validate_dem_and_rainfall", "reason", message,
                "next", "核验 DEM 覆盖、分辨率和降雨情景；数据合格后重试相同图谱计划。"));
        if ("skyline_analysis".equals(capabilityId) || "sunlight_analysis".equals(capabilityId)) return List.of(Map.of("action", "refresh_buildings", "reason", message,
                "next", "重新提取或上传建筑面，并补齐高度/层数字段。"));
        return List.of(Map.of("action", "validate_input_data", "reason", message, "next", "核验 AOI 与建筑数据后重试。"));
    }
}
