package org.example.spatial;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpatialCapabilityCatalogTest {

    @Test
    void validRemoteGraphUpdatesSemanticsButPreservesExecutableContract() {
        SpatialCapabilityCatalog catalog = new SpatialCapabilityCatalog();
        catalog.load();

        catalog.applyRemoteGraph("""
                {"version":"remote-2026-08-04","capabilities":[{
                  "id":"skyline_analysis","enabled":false,"aliases":["city silhouette"],
                  "requires":["aoi","buildings"],"optional":[],
                  "operations":["directional_height_profile"],"tool":"skylineAnalysis",
                  "outputs":["chart","metric"],"rendererKinds":["chart","metric"],
                  "knowledge":{"purpose":"remote wording"}
                }]}""");

        SpatialCapabilityCatalog.Capability skyline = catalog.find("skyline_analysis").orElseThrow();
        assertEquals("remote-2026-08-04", catalog.version());
        assertEquals("skylineAnalysis", skyline.tool());
        assertEquals("directional_height_profile", skyline.operations().get(0));
        assertEquals("city silhouette", skyline.aliases().get(0));
        assertEquals("remote wording", skyline.knowledge().get("purpose"));
        assertTrue(catalog.find("flood_analysis").isPresent());
    }

    @Test
    void rejectedRemoteGraphLeavesLastKnownGoodSnapshotUntouched() {
        SpatialCapabilityCatalog catalog = new SpatialCapabilityCatalog();
        catalog.load();
        String baselineVersion = catalog.version();

        assertThrows(IllegalArgumentException.class, () -> catalog.applyRemoteGraph("""
                {"version":"bad","capabilities":[{
                  "id":"skyline_analysis","enabled":true,"aliases":["skyline"],
                  "requires":["aoi","buildings"],"optional":[],
                  "operations":["arbitrary_code"],"tool":"skylineAnalysis",
                  "outputs":["chart","metric"],"rendererKinds":["chart","metric"]
                }]}"""));

        assertEquals(baselineVersion, catalog.version());
        assertEquals("directional_height_profile", catalog.find("skyline_analysis").orElseThrow().operations().get(0));
    }

    @Test
    void bundledRemoteGraphTemplateIsAValidSemanticOverlay() throws Exception {
        SpatialCapabilityCatalog catalog = new SpatialCapabilityCatalog();
        catalog.load();
        String template;
        try (var stream = new ClassPathResource("spatial-capabilities.remote-template.json").getInputStream()) {
            template = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        catalog.applyRemoteGraph(template);

        assertEquals("remote-2026-08-10", catalog.version());
        assertTrue(catalog.find("skyline_analysis").orElseThrow().aliases().contains("城市轮廓"));
        assertTrue(catalog.find("flood_analysis").isPresent());
    }

    @Test
    void newCapabilityMayReuseAnApprovedExecutionContract() {
        SpatialCapabilityCatalog catalog = new SpatialCapabilityCatalog();
        catalog.load();

        catalog.applyRemoteGraph("""
                {"version":"new-capability","capabilities":[{
                  "id":"view_corridor_analysis","enabled":true,"aliases":["视廊分析"],
                  "requires":["aoi","buildings"],"optional":[],
                  "operations":["directional_height_profile"],"tool":"skylineAnalysis",
                  "outputs":["chart","metric"],"rendererKinds":["chart","metric"],
                  "knowledge":{"purpose":"Assess a planned view corridor."}
                }]}""");

        assertTrue(catalog.find("view_corridor_analysis").isPresent());
        assertEquals("skylineAnalysis", catalog.find("view_corridor_analysis").orElseThrow().tool());
    }
}
