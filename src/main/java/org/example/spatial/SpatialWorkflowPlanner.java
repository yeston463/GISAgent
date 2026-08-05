package org.example.spatial;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/** Finds every approved capability explicitly requested in one utterance. */
@Component
public class SpatialWorkflowPlanner {
    private final SpatialCapabilityCatalog catalog;
    private final AnalysisPlanCompiler compiler;

    public SpatialWorkflowPlanner(SpatialCapabilityCatalog catalog, AnalysisPlanCompiler compiler) {
        this.catalog = catalog;
        this.compiler = compiler;
    }

    /** A workflow starts only when two or more distinct graph capabilities are named. */
    public List<AnalysisPlanCompiler.Compilation> compileWorkflow(String message) {
        if (message == null || message.isBlank()) return List.of();
        String normalized = message.toLowerCase(java.util.Locale.ROOT);
        return catalog.capabilities().stream()
                .filter(capability -> capability.aliases().stream()
                        .anyMatch(alias -> alias != null && normalized.contains(alias.toLowerCase(java.util.Locale.ROOT))))
                .sorted(Comparator.comparingInt(capability -> executionOrder(capability.id())))
                .map(capability -> compiler.compile(capability.id(), message))
                .toList();
    }

    private int executionOrder(String capabilityId) {
        return switch (capabilityId) {
            case "urban_metrics" -> 10;
            case "skyline_analysis" -> 20;
            case "sunlight_analysis" -> 30;
            case "flood_analysis" -> 40;
            default -> 100;
        };
    }
}
