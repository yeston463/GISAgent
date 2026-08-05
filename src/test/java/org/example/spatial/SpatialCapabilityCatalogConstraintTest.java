package org.example.spatial;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpatialCapabilityCatalogConstraintTest {

    @Test
    void urbanMetricsConstraintsLoadedCorrectly() {
        SpatialCapabilityCatalog catalog = new SpatialCapabilityCatalog();
        catalog.load();

        List<SpatialCapabilityCatalog.Constraint> constraints = catalog.getConstraints("urban_metrics");
        assertFalse(constraints.isEmpty());
        assertEquals(3, constraints.size());

        SpatialCapabilityCatalog.Constraint farConstraint = constraints.get(0);
        assertEquals("FAR", farConstraint.metric());
        assertEquals(2.0, farConstraint.max());
        assertEquals("R2用地规范 GB50137", farConstraint.source());
    }

    @Test
    void urbanMetricsRelationsLoadedCorrectly() {
        SpatialCapabilityCatalog catalog = new SpatialCapabilityCatalog();
        catalog.load();

        List<SpatialCapabilityCatalog.Relation> relations = catalog.getRelations("urban_metrics");
        assertFalse(relations.isEmpty());
        assertEquals(3, relations.size());

        SpatialCapabilityCatalog.Relation sunlightRelation = relations.get(0);
        assertEquals("height > 24m", sunlightRelation.trigger());
        assertEquals("require", sunlightRelation.action());
        assertEquals("sunlight_analysis", sunlightRelation.target());
    }

    @Test
    void validateMetricsDetectsViolationWhenFarExceedsMax() {
        SpatialCapabilityCatalog catalog = new SpatialCapabilityCatalog();
        catalog.load();

        Map<String, Object> metrics = Map.of("FAR", 3.8, "buildingDensity", 25.0);
        List<SpatialCapabilityCatalog.Violation> violations = catalog.validateMetrics("urban_metrics", metrics);

        assertFalse(violations.isEmpty());
        SpatialCapabilityCatalog.Violation violation = violations.get(0);
        assertEquals("FAR", violation.metric());
        assertEquals(3.8, violation.value());
        assertEquals(2.0, violation.max());
        assertEquals("R2用地规范 GB50137", violation.source());
    }

    @Test
    void validateMetricsDetectsMultipleViolations() {
        SpatialCapabilityCatalog catalog = new SpatialCapabilityCatalog();
        catalog.load();

        Map<String, Object> metrics = Map.of("FAR", 3.8, "buildingDensity", 35.0, "buildingHeight", 60.0);
        List<SpatialCapabilityCatalog.Violation> violations = catalog.validateMetrics("urban_metrics", metrics);

        assertEquals(3, violations.size());
    }

    @Test
    void validateMetricsReturnsEmptyWhenWithinLimits() {
        SpatialCapabilityCatalog catalog = new SpatialCapabilityCatalog();
        catalog.load();

        Map<String, Object> metrics = Map.of("FAR", 1.5, "buildingDensity", 25.0, "buildingHeight", 40.0);
        List<SpatialCapabilityCatalog.Violation> violations = catalog.validateMetrics("urban_metrics", metrics);

        assertTrue(violations.isEmpty());
    }

    @Test
    void backwardCompatibilityOldJsonWithoutConstraintsDoesNotBreakParsing() {
        SpatialCapabilityCatalog catalog = new SpatialCapabilityCatalog();
        catalog.load();

        catalog.applyRemoteGraph("""
                {"version":"legacy-test","capabilities":[{
                  "id":"skyline_analysis","enabled":false,"aliases":["city silhouette"],
                  "requires":["aoi","buildings"],"optional":[],
                  "operations":["directional_height_profile"],"tool":"skylineAnalysis",
                  "outputs":["chart","metric"],"rendererKinds":["chart","metric"],
                  "knowledge":{"purpose":"remote wording"}
                }]}""");

        SpatialCapabilityCatalog.Capability cap = catalog.find("skyline_analysis").orElseThrow();
        assertNotNull(cap.constraints());
        assertNotNull(cap.relations());
        assertEquals("city silhouette", cap.aliases().get(0));
        assertEquals("remote wording", cap.knowledge().get("purpose"));
    }

    @Test
    void capabilityWithEmptyConstraintsReturnsEmptyList() {
        SpatialCapabilityCatalog catalog = new SpatialCapabilityCatalog();
        catalog.load();

        List<SpatialCapabilityCatalog.Constraint> constraints = catalog.getConstraints("site_selection");
        assertTrue(constraints.isEmpty());
    }

    @Test
    void validateMetricsReturnsEmptyForUnknownCapability() {
        SpatialCapabilityCatalog catalog = new SpatialCapabilityCatalog();
        catalog.load();

        List<SpatialCapabilityCatalog.Violation> violations = catalog.validateMetrics("nonexistent", Map.of("FAR", 5.0));
        assertTrue(violations.isEmpty());
    }

    @Test
    void validateMetricsSkipsNonNumericValues() {
        SpatialCapabilityCatalog catalog = new SpatialCapabilityCatalog();
        catalog.load();

        Map<String, Object> metrics = Map.of("FAR", "not_a_number");
        List<SpatialCapabilityCatalog.Violation> violations = catalog.validateMetrics("urban_metrics", metrics);
        assertTrue(violations.isEmpty());
    }

    @Test
    void floodAnalysisRelationsLoadedCorrectly() {
        SpatialCapabilityCatalog catalog = new SpatialCapabilityCatalog();
        catalog.load();

        List<SpatialCapabilityCatalog.Relation> relations = catalog.getRelations("flood_analysis");
        assertFalse(relations.isEmpty());

        SpatialCapabilityCatalog.Relation demCheck = relations.get(0);
        assertEquals("rainfallMm > 200", demCheck.trigger());
        assertEquals("require", demCheck.action());
        assertEquals("dem_resolution_check", demCheck.target());
    }

    @Test
    void capabilityRecordExposesConstraintsAndRelations() {
        SpatialCapabilityCatalog catalog = new SpatialCapabilityCatalog();
        catalog.load();

        SpatialCapabilityCatalog.Capability cap = catalog.find("urban_metrics").orElseThrow();
        assertNotNull(cap.constraints());
        assertNotNull(cap.relations());
        assertFalse(cap.constraints().isEmpty());
        assertFalse(cap.relations().isEmpty());
    }
}
