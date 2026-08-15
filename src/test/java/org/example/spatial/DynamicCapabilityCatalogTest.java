package org.example.spatial;

import com.alibaba.fastjson.JSONObject;
import org.example.agent.DynamicToolStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DynamicCapabilityCatalogTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsOnlyPersistedDynamicToolsForNewCapabilities() {
        DynamicToolStore store = new DynamicToolStore(temporaryDirectory.resolve("dynamic-tools.json"));
        store.upsert("parcel_score", "Parcel score", "result = { score: 1 };", new JSONObject());

        SpatialCapabilityCatalog catalog = new SpatialCapabilityCatalog(store);
        catalog.load();
        String graph = graph("parcel_score");

        assertEquals("dynamic-1", catalog.preview(graph).version());
        catalog.applyPublishedGraph(graph);
        assertEquals("parcel_score", catalog.find("parcel_score_cap").orElseThrow().tool());

        assertThrows(IllegalArgumentException.class, () -> catalog.preview(graph("missing_tool")));
    }

    private String graph(String tool) {
        return """
                {"version":"dynamic-1","capabilities":[{
                  "id":"parcel_score_cap","enabled":true,
                  "aliases":["\\u52a8\\u6001\\u65b9\\u6cd5"],
                  "requires":[],"optional":[],"operations":["dynamic_compute"],
                  "tool":"%s","outputs":["metric"],"rendererKinds":["metric"],
                  "dataRequirements":[],"knowledge":{"purpose":"dynamic metric"}
                }]}""".formatted(tool);
    }
}
