package org.example.spatial;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.example.service.GisContextService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Post-execution evidence check. Never fabricates results. */
@Service
public class SpatialResultQualityService {
    private final GisContextService context;
    public SpatialResultQualityService(GisContextService context) { this.context = context; }

    public Map<String, Object> assess(AnalysisPlan plan, Map<String, Object> result) {
        List<String> warnings = new ArrayList<>(); List<String> reflection = new ArrayList<>(); JSONObject data = readContext();
        String status = String.valueOf(result == null ? "Error" : result.getOrDefault("status", "Success"));
        if (!"Success".equalsIgnoreCase(status) && !"ok".equalsIgnoreCase(status)) return Map.of("grade", "failed", "usable", false,
                "warnings", List.of("工具未成功返回结果"), "reflection", List.of("检查工具错误和输入数据后重新规划。"));
        if (plan.requiresBuildings()) assessBuildings(data, plan.capabilityId(), warnings, reflection);
        if ("flood_analysis".equals(plan.capabilityId())) assessFlood(data, result, warnings, reflection);
        if ("urban_metrics".equals(plan.capabilityId()) && number(result, "far") < 0) { warnings.add("容积率为负值。"); reflection.add("核对建筑面积和 AOI 面积单位。"); }
        String grade = warnings.isEmpty() ? "good" : warnings.size() == 1 ? "review" : "limited";
        return Map.of("grade", grade, "usable", !"limited".equals(grade), "warnings", List.copyOf(warnings),
                "reflection", List.copyOf(reflection), "contextVersion", data.getLongValue("contextVersion"));
    }

    private void assessBuildings(JSONObject data, String capability, List<String> warnings, List<String> reflection) {
        JSONObject buildings = data.getJSONObject("buildings"); JSONArray features = buildings == null ? null : buildings.getJSONArray("features");
        if (features == null || features.isEmpty()) { warnings.add("建筑要素为空。"); reflection.add("上传建筑面数据或从底图重新提取建筑。"); return; }
        if ("skyline_analysis".equals(capability) || "sunlight_analysis".equals(capability)) {
            boolean heights = features.stream().anyMatch(item -> item instanceof JSONObject f && hasHeight(f));
            if (!heights) { warnings.add("建筑缺高度或层数字段。"); reflection.add("补充 height、building_height、floors 或 storeys 字段。"); }
        }
    }
    private void assessFlood(JSONObject data, Map<String, Object> result, List<String> warnings, List<String> reflection) {
        JSONObject dem = data.getJSONObject("dem"); JSONObject metadata = dem == null ? null : dem.getJSONObject("metadata");
        if (dem == null) { warnings.add("未找到 DEM。"); reflection.add("上传覆盖 AOI 的 DEM 后重试。"); }
        else if (metadata == null || !"ready".equals(metadata.getString("metadataStatus"))) { warnings.add("DEM 元数据未完全可读。"); reflection.add("优先使用 ASC 或配置栅格读取器后重试。"); }
        if (number(result, "rainfall_mm") <= 0) { warnings.add("结果未回传有效降雨量。"); reflection.add("核对降雨情景。"); }
        warnings.add("洪水结果为相对风险筛查，不替代校准水动力模型。");
    }
    private boolean hasHeight(JSONObject feature) { JSONObject p = feature.getJSONObject("properties"); return p != null && (p.get("height") != null || p.get("building_height") != null || p.get("floors") != null || p.get("storeys") != null); }
    private double number(Map<String, Object> values, String key) { try { return Double.parseDouble(String.valueOf(values.getOrDefault(key, 0))); } catch (Exception ignored) { return 0; } }
    private JSONObject readContext() { try { return JSON.parseObject(context.getGeoJson()); } catch (Exception ignored) { return new JSONObject(); } }
}
