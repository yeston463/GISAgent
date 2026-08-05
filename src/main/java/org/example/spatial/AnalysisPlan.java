package org.example.spatial;

import java.util.List;
import java.util.Map;

/** Declarative, non-executable description of one spatial analysis run. */
public record AnalysisPlan(
        String planVersion,
        String knowledgeGraphSource,
        String capabilityId,
        String contextRef,
        List<String> operations,
        String tool,
        List<String> outputs,
        Map<String, Object> params
) {
    public boolean requiresBuildings() {
        return "urban_metrics".equals(capabilityId) || "skyline_analysis".equals(capabilityId) || "sunlight_analysis".equals(capabilityId);
    }
}
