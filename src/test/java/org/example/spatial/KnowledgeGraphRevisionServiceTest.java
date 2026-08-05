package org.example.spatial;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class KnowledgeGraphRevisionServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void previewPublishAndRollbackKeepOnlyValidatedRevisions() {
        SpatialCapabilityCatalog catalog = new SpatialCapabilityCatalog();
        catalog.load();
        KnowledgeGraphRevisionService service = new KnowledgeGraphRevisionService(catalog,
                temporaryDirectory.resolve("knowledge-graph-revisions.json"));

        String v2 = graph("published-2", "city silhouette");
        Map<String, List<String>> v2Tests = Map.of("skyline_analysis", List.of("执行城市天际线 city silhouette 分析"));
        assertEquals(true, service.preview(v2, v2Tests).get("valid"));
        assertEquals(true, service.publish(v2, "tester", "new wording", v2Tests).get("published"));
        assertEquals("published-2", catalog.version());
        assertTrue(catalog.find("skyline_analysis").orElseThrow().aliases().contains("city silhouette"));
        assertEquals(1, service.list().size());

        String v3 = graph("published-3", "urban outline");
        service.publish(v3, "tester", "different wording", Map.of("skyline_analysis", List.of("执行城市轮廓 urban outline 分析")));
        assertEquals("published-3", catalog.version());

        assertEquals(true, service.rollback("published-2").get("rolledBack"));
        assertEquals("published-2", catalog.version());
        assertTrue(catalog.find("flood_analysis").isPresent());

        SpatialCapabilityCatalog restartedCatalog = new SpatialCapabilityCatalog();
        restartedCatalog.load();
        KnowledgeGraphRevisionService restarted = new KnowledgeGraphRevisionService(restartedCatalog,
                temporaryDirectory.resolve("knowledge-graph-revisions.json"));
        restarted.restoreActiveRevision();
        assertEquals("published-2", restartedCatalog.version());
        assertTrue(restartedCatalog.find("skyline_analysis").orElseThrow().aliases().contains("city silhouette"));
    }

    @Test
    void qualityGateRejectsGraphWithoutChineseAliasOrMatchingAcceptanceUtterance() {
        SpatialCapabilityCatalog catalog = new SpatialCapabilityCatalog();
        catalog.load();
        KnowledgeGraphRevisionService service = new KnowledgeGraphRevisionService(catalog,
                temporaryDirectory.resolve("knowledge-graph-revisions.json"));

        Map<String, Object> preview = service.preview(graph("bad-quality", "only english").replace("\"天际线分析\",", ""),
                Map.of("skyline_analysis", List.of("进行天际线分析")));

        assertFalse((Boolean) preview.get("valid"));
        assertTrue(((List<?>) preview.get("qualityGates")).stream()
                .anyMatch(item -> item instanceof Map<?, ?> gate && Boolean.FALSE.equals(gate.get("passed"))));
    }

    private String graph(String version, String alias) {
        return """
                {"version":"%s","capabilities":[{
                  "id":"skyline_analysis","enabled":true,"aliases":["天际线分析","%s"],
                  "requires":["aoi","buildings"],"optional":[],
                  "operations":["directional_height_profile"],"tool":"skylineAnalysis",
                  "outputs":["chart","metric"],"rendererKinds":["chart","metric"],
                  "knowledge":{"purpose":"versioned wording"}
                }]}""".formatted(version, alias);
    }
}
