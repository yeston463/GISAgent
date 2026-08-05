package org.example.agent;

import org.example.spatial.SpatialCapabilityCatalog;

import java.util.List;
import java.util.Map;

public record ScenarioResult(
        Scenario scenario,
        Map<String, Object> metrics,
        List<SpatialCapabilityCatalog.Violation> violations,
        double score
) {
    public ScenarioResult {
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
        violations = violations == null ? List.of() : List.copyOf(violations);
    }
}
