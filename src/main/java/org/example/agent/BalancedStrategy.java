package org.example.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class BalancedStrategy implements ScenarioStrategy {
    @Override
    public String id() { return "balanced"; }
    @Override
    public String name() { return "平衡方案"; }
    @Override
    public String description() { return "超高建筑降至限高，调整建筑密度"; }
    @Override
    public List<Map<String, Object>> apply(List<Map<String, Object>> originalBuildings, Map<String, Double> context) {
        double heightLimit = context.getOrDefault("heightLimit", 24.0);
        double densityFactor = context.getOrDefault("densityFactor", 0.85);
        List<Map<String, Object>> adjusted = new ArrayList<>();
        for (Map<String, Object> b : originalBuildings) {
            Map<String, Object> copy = new LinkedHashMap<>(b);
            double height = toDouble(b.get("height"));
            if (height > heightLimit) {
                copy.put("height", heightLimit);
                double floors = toDouble(b.get("floors"));
                double floorHeight = height > 0 ? height / Math.max(1, floors) : 3.0;
                copy.put("floors", Math.max(1, Math.round(heightLimit / floorHeight)));
            }
            double footprint = toDouble(b.get("footprint"));
            copy.put("footprint", footprint * densityFactor);
            adjusted.add(copy);
        }
        return adjusted;
    }

    private static double toDouble(Object v) {
        if (v == null) return 0;
        try { return Double.parseDouble(v.toString()); } catch (NumberFormatException e) { return 0; }
    }
}
