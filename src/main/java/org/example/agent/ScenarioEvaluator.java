package org.example.agent;

import org.example.spatial.SpatialCapabilityCatalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ScenarioEvaluator {

    private final SpatialCapabilityCatalog catalog;
    private final double complianceWeight;
    private final double economyWeight;
    private final double feasibilityWeight;

    public ScenarioEvaluator(SpatialCapabilityCatalog catalog) {
        this(catalog, 0.5, 0.3, 0.2);
    }

    public ScenarioEvaluator(SpatialCapabilityCatalog catalog,
                              double complianceWeight,
                              double economyWeight,
                              double feasibilityWeight) {
        this.catalog = catalog;
        this.complianceWeight = complianceWeight;
        this.economyWeight = economyWeight;
        this.feasibilityWeight = feasibilityWeight;
    }

    public List<ScenarioResult> evaluate(List<Scenario> scenarios, String capabilityId, double originalFar) {
        List<ScenarioResult> results = new ArrayList<>();
        for (Scenario scenario : scenarios) {
            Map<String, Object> metrics = calculateMetrics(scenario);
            List<SpatialCapabilityCatalog.Violation> violations = catalog.validateMetrics(capabilityId, metrics);
            double score = scoreScenario(violations, metrics, originalFar, scenario);
            results.add(new ScenarioResult(scenario, metrics, violations, score));
        }
        return results;
    }

    public Map<String, Object> calculateMetrics(Scenario scenario) {
        List<Map<String, Object>> buildings = scenario.buildings();
        double siteArea = scenario.parameters().getOrDefault("siteArea", 1.0);
        if (siteArea <= 0) siteArea = 1.0;
        double totalFloorArea = 0;
        double totalFootprint = 0;
        double maxHeight = 0;
        for (Map<String, Object> b : buildings) {
            double footprint = toDouble(b.get("footprint"));
            double floors = toDouble(b.get("floors"));
            double height = toDouble(b.get("height"));
            totalFootprint += footprint;
            totalFloorArea += footprint * floors;
            if (height > maxHeight) maxHeight = height;
        }
        double far = totalFloorArea / siteArea;
        double density = (totalFootprint / siteArea) * 100;
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("far", Math.round(far * 1000.0) / 1000.0);
        metrics.put("building_density", Math.round(density * 100.0) / 100.0);
        metrics.put("buildingHeight", Math.round(maxHeight * 10.0) / 10.0);
        metrics.put("building_count", buildings.size());
        metrics.put("site_area", siteArea);
        metrics.put("building_area", totalFloorArea);
        metrics.put("footprint_area", totalFootprint);
        metrics.put("FAR", metrics.get("far"));
        metrics.put("buildingDensity", metrics.get("building_density"));
        return metrics;
    }

    public double scoreScenario(List<SpatialCapabilityCatalog.Violation> violations,
                                 Map<String, Object> metrics,
                                 double originalFar,
                                 Scenario scenario) {
        double complianceScore = violations.isEmpty() ? 1.0 : Math.max(0, 1.0 - (violations.size() * 0.2));
        double far = toDouble(metrics.get("far"));
        double targetFar = scenario.parameters().getOrDefault("targetFar", 2.0);
        double economyScore = far <= targetFar ? (far / targetFar) : Math.max(0, 1.0 - (far - targetFar) * 0.5);
        double changeRatio = Math.abs(far - originalFar) / Math.max(0.01, originalFar);
        double feasibilityScore = Math.max(0, 1.0 - changeRatio);
        return complianceWeight * complianceScore
                + economyWeight * economyScore
                + feasibilityWeight * feasibilityScore;
    }

    private static double toDouble(Object v) {
        if (v == null) return 0;
        try { return Double.parseDouble(v.toString()); } catch (NumberFormatException e) { return 0; }
    }
}
