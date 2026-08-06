package org.example.agent;

import org.example.spatial.SpatialCapabilityCatalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ScenarioEvaluator {

    /** Default weighting policy: 合规优先，经济次之，可行性兜底。 */
    public static final double DEFAULT_COMPLIANCE_WEIGHT = 0.5;
    public static final double DEFAULT_ECONOMY_WEIGHT = 0.3;
    public static final double DEFAULT_FEASIBILITY_WEIGHT = 0.2;

    private final SpatialCapabilityCatalog catalog;
    private final double complianceWeight;
    private final double economyWeight;
    private final double feasibilityWeight;

    public ScenarioEvaluator(SpatialCapabilityCatalog catalog) {
        this(catalog,
                DEFAULT_COMPLIANCE_WEIGHT,
                DEFAULT_ECONOMY_WEIGHT,
                DEFAULT_FEASIBILITY_WEIGHT);
    }

    public ScenarioEvaluator(SpatialCapabilityCatalog catalog,
                              double complianceWeight,
                              double economyWeight,
                              double feasibilityWeight) {
        if (complianceWeight < 0 || economyWeight < 0 || feasibilityWeight < 0
                || Math.abs(complianceWeight + economyWeight + feasibilityWeight - 1.0) > 1e-9) {
            throw new IllegalArgumentException(
                    "Scenario weights must be non-negative and sum to 1.0; got "
                            + complianceWeight + ", " + economyWeight + ", " + feasibilityWeight);
        }
        this.catalog = catalog;
        this.complianceWeight = complianceWeight;
        this.economyWeight = economyWeight;
        this.feasibilityWeight = feasibilityWeight;
    }

    public List<ScenarioResult> evaluate(List<Scenario> scenarios, String capabilityId, double originalFar) {
        List<ScenarioResult> results = new ArrayList<>();
        for (Scenario scenario : scenarios) {
            Map<String, Object> metrics = new LinkedHashMap<>(calculateMetrics(scenario));
            List<SpatialCapabilityCatalog.Violation> violations = catalog.validateMetrics(capabilityId, metrics);
            double score = scoreScenario(violations, metrics, originalFar, scenario);
            metrics.put("scoreBreakdown", scoreBreakdown(violations, metrics, originalFar, scenario));
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
        double complianceScore = complianceScore(violations);
        double economyScore = economyScore(metrics, scenario);
        double feasibilityScore = feasibilityScore(metrics, originalFar);
        return complianceWeight * complianceScore
                + economyWeight * economyScore
                + feasibilityWeight * feasibilityScore;
    }

    /**
     * Score breakdown exposed alongside the total, so the "推荐" is not a black
     * box: the reader can see each dimension's score, the applied weights and the
     * weighting rationale. Weights are configurable via the constructor, so a
     * stakeholder can re-tune the policy without touching scoring math.
     */
    public Map<String, Object> scoreBreakdown(List<SpatialCapabilityCatalog.Violation> violations,
                                            Map<String, Object> metrics,
                                            double originalFar,
                                            Scenario scenario) {
        double complianceScore = complianceScore(violations);
        double economyScore = economyScore(metrics, scenario);
        double feasibilityScore = feasibilityScore(metrics, originalFar);
        Map<String, Object> breakdown = new LinkedHashMap<>();
        breakdown.put("complianceScore", round3(complianceScore));
        breakdown.put("economyScore", round3(economyScore));
        breakdown.put("feasibilityScore", round3(feasibilityScore));
        breakdown.put("complianceWeight", complianceWeight);
        breakdown.put("economyWeight", economyWeight);
        breakdown.put("feasibilityWeight", feasibilityWeight);
        breakdown.put("weightSum", Math.round((complianceWeight + economyWeight + feasibilityWeight) * 1000.0) / 1000.0);
        breakdown.put("weightingPolicy", "合规优先：合规体现规划合法底线，权重最高；经济性评价容积率贴近目标；可行性以对现状改动幅度兜底。权重可在 ScenarioEvaluator 构造时调整。");
        return breakdown;
    }

    double complianceScore(List<SpatialCapabilityCatalog.Violation> violations) {
        return violations.isEmpty() ? 1.0 : Math.max(0, 1.0 - (violations.size() * 0.2));
    }

    double economyScore(Map<String, Object> metrics, Scenario scenario) {
        double far = toDouble(metrics.get("far"));
        double targetFar = scenario.parameters().getOrDefault("targetFar", 2.0);
        return far <= targetFar ? (far / targetFar) : Math.max(0, 1.0 - (far - targetFar) * 0.5);
    }

    double feasibilityScore(Map<String, Object> metrics, double originalFar) {
        double far = toDouble(metrics.get("far"));
        double changeRatio = Math.abs(far - originalFar) / Math.max(0.01, originalFar);
        return Math.max(0, 1.0 - changeRatio);
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static double toDouble(Object v) {
        if (v == null) return 0;
        try { return Double.parseDouble(v.toString()); } catch (NumberFormatException e) { return 0; }
    }
}
