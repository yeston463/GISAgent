package org.example.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AggressiveStrategy implements ScenarioStrategy {
    @Override
    public String id() { return "aggressive"; }
    @Override
    public String name() { return "激进方案"; }
    @Override
    public String description() { return "拆除重建，按 FAR=2.0 上限优化"; }
    @Override
    public List<Map<String, Object>> apply(List<Map<String, Object>> originalBuildings, Map<String, Double> context) {
        double targetFar = context.getOrDefault("targetFar", 2.0);
        double heightLimit = context.getOrDefault("heightLimit", 60.0);
        double siteArea = context.getOrDefault("siteArea", 1.0);
        if (siteArea <= 0) siteArea = 1.0;
        double targetTotalFloor = targetFar * siteArea;
        double avgFootprint = originalBuildings.stream()
                .mapToDouble(b -> toDouble(b.get("footprint")))
                .average().orElse(100);
        double floorHeight = 3.0;
        double floorsPerBuilding = Math.min(heightLimit / floorHeight, 20);
        double floorPerBuilding = avgFootprint * floorsPerBuilding;
        int buildingCount = floorPerBuilding > 0 ? (int) Math.ceil(targetTotalFloor / floorPerBuilding) : originalBuildings.size();
        buildingCount = Math.max(1, Math.min(buildingCount, (int) (originalBuildings.size() * 1.5)));
        double actualFloorEach = targetTotalFloor / Math.max(1, buildingCount);
        double actualFloors = Math.min(floorPerBuilding > 0 ? Math.ceil(actualFloorEach / avgFootprint) : 10, floorsPerBuilding);
        List<Map<String, Object>> rebuilt = new ArrayList<>();
        for (int i = 0; i < buildingCount; i++) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("height", actualFloors * floorHeight);
            b.put("floors", actualFloors);
            b.put("footprint", avgFootprint);
            rebuilt.add(b);
        }
        return rebuilt;
    }

    private static double toDouble(Object v) {
        if (v == null) return 0;
        try { return Double.parseDouble(v.toString()); } catch (NumberFormatException e) { return 0; }
    }
}
