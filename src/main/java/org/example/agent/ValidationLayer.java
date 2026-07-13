package org.example.agent;

import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class ValidationLayer {

    public Map<String, Object> validateMetrics(Map<String, Object> metrics) {
        Map<String, Object> validated = new LinkedHashMap<>(metrics);
        List<String> warnings = new ArrayList<>();

        if (metrics.containsKey("far")) {
            double far = Double.parseDouble(metrics.get("far").toString());
            if (far > 20) {
                warnings.add("容积率 " + far + " 异常偏高，可能分母偏小");
                validated.put("far_warning", true);
            } else if (far <= 0 && metrics.containsKey("building_count")) {
                int count = Integer.parseInt(metrics.get("building_count").toString());
                if (count > 0) {
                    warnings.add("有 " + count + " 栋建筑但容积率为 0，可能建筑面积为 0");
                    validated.put("far_warning", true);
                }
            }
        }

        if (metrics.containsKey("building_count")) {
            int count = Integer.parseInt(metrics.get("building_count").toString());
            if (count == 0 && metrics.containsKey("site_area_sqm")) {
                double site = Double.parseDouble(metrics.get("site_area_sqm").toString());
                if (site > 1000) {
                    warnings.add("用地面积 " + (int)site + "m² 但建筑数量为 0，可能未匹配到建筑数据");
                }
            }
            if (count > 10000) {
                warnings.add("建筑数量 " + count + " 异常多，可能数据范围过大");
            }
        }

        if (metrics.containsKey("site_area_sqm") && metrics.containsKey("total_const_area_sqm")) {
            double site = Double.parseDouble(metrics.get("site_area_sqm").toString());
            double total = Double.parseDouble(metrics.get("total_const_area_sqm").toString());
            if (site > 0 && total < site) {
                double far = Double.parseDouble(metrics.getOrDefault("far", "0").toString());
                if (far < 0.1 && total > 0) {
                    warnings.add("总建筑面积小于用地面积但容积率极低，检查楼层数据是否完整");
                }
            }
        }

        if (!warnings.isEmpty()) {
            validated.put("warnings", warnings);
        }
        return validated;
    }

    public boolean needsReplan(Map<String, Object> metrics) {
        if (!metrics.containsKey("far")) return false;
        double far = Double.parseDouble(metrics.get("far").toString());
        double farMax = metrics.containsKey("far_max")
            ? Double.parseDouble(metrics.get("far_max").toString()) : 3.0;
        return far > farMax;
    }
}
