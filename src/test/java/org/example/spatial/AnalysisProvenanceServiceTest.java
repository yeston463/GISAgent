package org.example.spatial;

import org.example.service.GisContextService;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AnalysisProvenanceServiceTest {
    @Test
    void latestSuccessfulIsScopedToTheActiveMemoryId() {
        Path tempDir = Path.of("target", "test-provenance-" + UUID.randomUUID());
        GisContextService context = new GisContextService(tempDir.resolve("contexts.json"));
        AnalysisProvenanceService provenance = new AnalysisProvenanceService(context, tempDir.resolve("runs.json"));

        context.activateSession("session-a");
        provenance.record(plan(), availability(), validation(), Map.of("status", "Success", "marker", "a"));

        context.activateSession("session-b");
        provenance.record(plan(), availability(), validation(), Map.of("status", "Success", "marker", "b"));

        assertEquals("a", resultMarker(provenance.latestSuccessful("session-a")));
        assertEquals("b", resultMarker(provenance.latestSuccessful("session-b")));
        assertNull(provenance.latestSuccessful("session-c"));
    }

    @SuppressWarnings("unchecked")
    private String resultMarker(Map<String, Object> record) {
        return String.valueOf(((Map<String, Object>) record.get("result")).get("marker"));
    }

    private AnalysisPlan plan() {
        return new AnalysisPlan("v1", "test", "urban_metrics", "context", List.of("metrics"), "analyze", List.of("metric"), Map.of());
    }

    private DataAvailabilityChecker.Availability availability() {
        return new DataAvailabilityChecker.Availability(Set.of("aoi"), Set.of(), 1L);
    }

    private SpatialPlanValidator.Validation validation() {
        return new SpatialPlanValidator.Validation("Valid", "", List.of(), null);
    }
}
