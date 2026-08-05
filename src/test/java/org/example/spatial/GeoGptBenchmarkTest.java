package org.example.spatial;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(properties = {"spatial.demo.enabled=true", "rag.auto-load=false", "spatial.knowledge-graph.url="})
class GeoGptBenchmarkTest {
    @Autowired SpatialWorkflowPlanner workflowPlanner;
    @Autowired AnalysisPlanCompiler compiler;

    @Test
    void benchmarkCorpusCompilesToExpectedCapabilities() throws Exception {
        try (var stream = getClass().getResourceAsStream("/geogpt-spatial-benchmark.json")) {
            JSONArray cases = JSON.parseArray(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            for (Object row : cases) {
                JSONObject item = (JSONObject) row; List<String> expected = item.getJSONArray("capabilities").toJavaList(String.class);
                List<String> actual = expected.size() == 1 ? List.of(compiler.compile(item.getString("utterance")).orElseThrow().capabilityId())
                        : workflowPlanner.compileWorkflow(item.getString("utterance")).stream().map(AnalysisPlanCompiler.Compilation::capabilityId).toList();
                assertEquals(expected, actual, item.getString("utterance"));
            }
        }
    }
}
