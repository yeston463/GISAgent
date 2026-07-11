package org.example.agent;

import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class SuggestionEngine {

    public List<String> generateSuggestions(Map<String, Object> metrics) {
        List<String> suggestions = new ArrayList<>();
        if (metrics == null) {
            suggestions.add("可以尝试搜索一个具体位置进行分析");
            suggestions.add("或上传建筑数据 GeoJSON 进行分析");
            return suggestions;
        }

        double far = getDouble(metrics, "far");
        int buildingCount = getInt(metrics, "building_count");
        double buildingDensity = getDouble(metrics, "building_density");

        if (metrics.containsKey("location")) {
            String loc = metrics.get("location").toString();
            if (far > 3.0) {
                suggestions.add(String.format("%s 容积率偏高（%.2f），是否需要对不符合规划的建筑进行重规划？", loc, far));
            } else if (far < 0.5 && buildingCount > 0) {
                suggestions.add(String.format("%s 容积率偏低（%.2f），有开发潜力", loc, far));
            }
        } else {
            if (far > 3.0) {
                suggestions.add("该区域容积率偏高，建议进行规划优化");
            } else if (far < 0.5 && buildingCount > 0) {
                suggestions.add("该区域容积率偏低，有开发潜力");
            }
        }

        if (buildingDensity > 0.4) {
            suggestions.add("建筑密度较高，建议增加绿化分析");
        }

        if (metrics.containsKey("height_stats")) {
            suggestions.add("需要查看建筑高度分布详情吗？");
        }

        if (metrics.containsKey("skyline")) {
            suggestions.add("是否对比周边区域的天际线形态？");
        }

        if (metrics.containsKey("building_types")) {
            suggestions.add("需要分类统计各类型建筑的详细指标吗？");
        }

        suggestions.add("是否导出分析报告？");

        return suggestions;
    }

    private double getDouble(Map<String, Object> m, String key) {
        if (!m.containsKey(key)) return 0;
        return Double.parseDouble(m.get(key).toString());
    }

    private int getInt(Map<String, Object> m, String key) {
        if (!m.containsKey(key)) return 0;
        return Integer.parseInt(m.get(key).toString());
    }
}
