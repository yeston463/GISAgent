package org.example.spatial;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.example.controller.AgentController;
import org.example.controller.AnalysisReportController;
import org.example.controller.GisDataController;
import org.example.service.GisContextService;
import org.example.spatial.SpatialCapabilityCatalog;
import org.example.spatial.SpatialWorkflowPlanner;
import org.example.tools.DynamicToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {"spatial.demo.enabled=true", "rag.auto-load=false", "spatial.knowledge-graph.url=", "agent.llm-routing.enabled=false"})
class SpatialPlanningContractTest {

    @Autowired
    private AnalysisPlanCompiler compiler;

    @Autowired
    private SpatialCapabilityCatalog capabilityCatalog;

    @Autowired
    private SpatialPlanService spatialPlanService;

    @Autowired
    private AgentController agentController;

    @Autowired
    private GisDataController gisDataController;

    @Autowired
    private GisContextService gisContextService;

    @Autowired
    private AnalysisReportController analysisReportController;

    @Autowired
    private SpatialWorkflowPlanner workflowPlanner;

    @Autowired
    private SpatialDemoContext spatialDemoContext;

    @MockBean
    private DynamicToolRegistry toolRegistry;

    @BeforeEach
    void registerDeterministicAnalysisTools() throws Exception {
        when(toolRegistry.getToolDescriptors()).thenReturn(List.of(
                Map.of("name", "analyzeCurrentView"),
                Map.of("name", "skylineAnalysis"),
                Map.of("name", "sunlightAnalysis"),
                Map.of("name", "floodAnalysis")
        ));
        when(toolRegistry.invoke(eq("analyzeCurrentView"), any(JSONObject.class))).thenReturn(Map.of(
                "status", "Success", "far", 1.8, "building_count", 3,
                "site_area", 1000.0, "building_area", 1800.0
        ));
        when(toolRegistry.invoke(eq("skylineAnalysis"), any(JSONObject.class))).thenReturn(Map.of(
                "status", "Success", "analysis_type", "skyline", "building_count", 3,
                "max_height", 72.0, "mean_height", 50.0,
                "commands", List.of(Map.of("action", "showAdvancedAnalysis", "params", Map.of("kind", "skyline")))
        ));
        when(toolRegistry.invoke(eq("sunlightAnalysis"), any(JSONObject.class))).thenReturn(Map.of(
                "status", "Success", "analysis_type", "sunlight", "building_count", 3,
                "sample_count", 5, "sunlight_window_percent", 80.0, "max_shadow_length_m", 64.0,
                "commands", List.of(Map.of("action", "showAdvancedAnalysis", "params", Map.of("kind", "sunlight")))
        ));
        when(toolRegistry.invoke(eq("floodAnalysis"), any(JSONObject.class))).thenReturn(Map.of(
                "status", "Success", "analysis_type", "flood", "rainfall_mm", 120.0,
                "high_risk_cell_count", 2, "medium_risk_cell_count", 3,
                "affected_building_count", 2, "max_estimated_depth_m", 0.078,
                "commands", List.of(Map.of("action", "showAdvancedAnalysis", "params", Map.of("kind", "flood")))
        ));
    }

    @Test
    void chineseSkylineRequestCompilesToApprovedPlan() {
        AnalysisPlanCompiler.Compilation compilation = compiler
                .compile("\u8fdb\u884c\u5929\u9645\u7ebf\u5206\u6790")
                .orElseThrow();

        assertEquals("skyline_analysis", compilation.capabilityId());
        assertEquals("skylineAnalysis", compilation.plan().tool());
    }

    @Test
    void urbanMetricsKnowledgeGraphContainsFormulasQualityAndLimitations() {
        SpatialCapabilityCatalog.Capability capability = capabilityCatalog.find("urban_metrics").orElseThrow();

        assertTrue(capability.optional().contains("parcel_area"));
        assertTrue(capability.knowledge().containsKey("formulas"));
        assertTrue(capability.knowledge().containsKey("qualityGates"));
        assertTrue(capability.knowledge().containsKey("sourceGuidance"));
        assertTrue(String.valueOf(capability.knowledge().get("limitations")).contains("法定"));
    }

    @Test
    void floodRequestCompilesToApprovedPlan() {
        AnalysisPlanCompiler.Compilation compilation = compiler.compile("flood analysis").orElseThrow();

        assertEquals("flood_analysis", compilation.capabilityId());
        assertEquals("floodAnalysis", compilation.plan().tool());
    }

    @Test
    void compositeSpatialRequestCompilesIntoAnOrderedWorkflow() {
        List<AnalysisPlanCompiler.Compilation> workflow = workflowPlanner.compileWorkflow(
                "评估这个地块的容积率、天际线、日照与阴影，以及洪水风险");

        assertEquals(List.of("urban_metrics", "skyline_analysis", "sunlight_analysis", "flood_analysis"),
                workflow.stream().map(AnalysisPlanCompiler.Compilation::capabilityId).toList());
    }

    @Test
    void preparedContextProducesTraceCommandsAndProvenanceForAdvancedCapabilities() {
        String memoryId = "spatial-contract-" + UUID.randomUUID();
        ResponseEntity<Map<String, Object>> demo = gisDataController.loadDemoContext(
                new GisDataController.DemoContextRequest(memoryId));
        assertTrue(demo.getStatusCode().is2xxSuccessful());
        assertEquals(3, demo.getBody().get("buildingCount"));

        assertAdvancedContract(memoryId, "skyline analysis", "skyline_analysis");
        assertAdvancedContract(memoryId, "sunlight analysis", "sunlight_analysis");
        assertAdvancedContract(memoryId, "flood analysis", "flood_analysis");
        ResponseEntity<byte[]> report = analysisReportController.latest(memoryId);
        assertTrue(report.getStatusCode().is2xxSuccessful());
        assertTrue(new String(report.getBody(), java.nio.charset.StandardCharsets.UTF_8).contains("<!doctype html>"));
        assertTrue(report.getHeaders().getFirst("Content-Disposition").contains("analysis-report-"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void floodWithoutInputsRequestsDataInsteadOfReturningAnError() {
        ResponseEntity<Map<String, Object>> response = agentController.agenticChat(
                new AgentController.ChatRequest("flood analysis", "empty-flood-" + UUID.randomUUID()));

        Map<String, Object> outcome = (Map<String, Object>) response.getBody().get("outcome");
        assertEquals("NeedsClarification", outcome.get("status"));
        assertEquals("required_data_missing", outcome.get("code"));
        assertTrue(((List<?>) outcome.get("missingData")).contains("aoi"));
        assertTrue(((List<?>) outcome.get("missingData")).contains("dem"));
        assertTrue(((List<?>) outcome.get("missingData")).contains("rainfall_scenario"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void compositeWorkflowMergesMissingDataIntoOneClarification() {
        String memoryId = "workflow-missing-" + UUID.randomUUID();

        ResponseEntity<Map<String, Object>> response = agentController.agenticChat(new AgentController.ChatRequest(
                "评估这个地块的容积率、天际线、日照与阴影，以及洪水风险", memoryId));

        Map<String, Object> outcome = (Map<String, Object>) response.getBody().get("outcome");
        assertEquals("NeedsClarification", outcome.get("status"));
        assertEquals("spatial_workflow", outcome.get("analysisType"));
        assertTrue(((List<?>) outcome.get("missingData")).containsAll(List.of("aoi", "buildings", "dem", "rainfall_scenario")));
        assertEquals(4, ((List<?>) outcome.get("capabilityIds")).size());
    }

    @SuppressWarnings("unchecked")
    @Test
    void pendingCompositeWorkflowResumesAllSubAnalysesAfterRainfallIsProvided() {
        String memoryId = "workflow-resume-" + UUID.randomUUID();
        Map<String, Object> context = new LinkedHashMap<>(spatialDemoContext.payload());
        context.remove("rainfall_scenario");
        context.put("memoryId", memoryId);
        context.put("contextVersion", 0L);
        assertFalse(gisContextService.saveGeoJson(memoryId, JSON.toJSONString(context), 0).conflict());

        ResponseEntity<Map<String, Object>> waiting = agentController.agenticChat(new AgentController.ChatRequest(
                "评估这个地块的容积率、天际线、日照与阴影，以及洪水风险", memoryId));
        assertEquals("NeedsClarification", ((Map<String, Object>) waiting.getBody().get("outcome")).get("status"));

        ResponseEntity<Map<String, Object>> resumed = agentController.agenticChat(new AgentController.ChatRequest(
                "降雨 80mm，历时 24h，20年一遇", memoryId));
        Map<String, Object> outcome = (Map<String, Object>) resumed.getBody().get("outcome");
        assertEquals("Success", outcome.get("status"));
        assertEquals("spatial_workflow", outcome.get("analysisType"));
        assertEquals(4, ((List<?>) outcome.get("subAnalyses")).size());
    }

    @SuppressWarnings("unchecked")
    @Test
    void conversationalRainfallDataCompletesPendingFloodRequest() {
        String memoryId = "flood-rainfall-" + UUID.randomUUID();
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("memoryId", memoryId);
        context.put("contextVersion", 0L);
        context.put("aoi", Map.of("type", "Feature", "geometry", Map.of("type", "Polygon", "coordinates", List.of()), "properties", Map.of()));
        context.put("dem", Map.of("kind", "raster", "path", "C:/test/terrain.asc"));
        assertTrue(gisDataController.upload(context).getStatusCode().is2xxSuccessful());

        ResponseEntity<Map<String, Object>> missing = agentController.agenticChat(
                new AgentController.ChatRequest("洪水分析", memoryId));
        assertEquals("NeedsClarification", ((Map<String, Object>) missing.getBody().get("outcome")).get("status"));

        ResponseEntity<Map<String, Object>> completed = agentController.agenticChat(
                new AgentController.ChatRequest("降雨 30mm，历时 24h，20年一遇", memoryId));
        assertEquals("Success", ((Map<String, Object>) completed.getBody().get("outcome")).get("status"));
        JSONObject saved = JSON.parseObject(gisContextService.getGeoJson(memoryId));
        JSONObject scenario = saved.getJSONObject("rainfall_scenario");
        assertNotNull(scenario);
        assertEquals(30.0, scenario.getDoubleValue("rainfallMm"));
        assertEquals(24.0, scenario.getDoubleValue("durationHours"));
        assertEquals(20, scenario.getIntValue("returnPeriodYears"));
        assertEquals("conversation_user_provided", scenario.getString("source"));

        ResponseEntity<Map<String, Object>> revised = agentController.agenticChat(
                new AgentController.ChatRequest("降水改成200mm", memoryId));
        assertEquals("Success", ((Map<String, Object>) revised.getBody().get("outcome")).get("status"));
        JSONObject updated = JSON.parseObject(gisContextService.getGeoJson(memoryId)).getJSONObject("rainfall_scenario");
        assertEquals(200.0, updated.getDoubleValue("rainfallMm"));
        assertEquals(24.0, updated.getDoubleValue("durationHours"));
        assertEquals(20, updated.getIntValue("returnPeriodYears"));
        assertEquals("conversation_user_revised", updated.getString("source"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void inlineFloodRequestLetsTheSchemaCollectorReadCompactParameters() {
        String memoryId = "inline-flood-" + UUID.randomUUID();
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("memoryId", memoryId);
        context.put("contextVersion", 0L);
        context.put("aoi", Map.of("type", "Feature", "geometry", Map.of("type", "Polygon", "coordinates", List.of()), "properties", Map.of()));
        context.put("dem", Map.of("kind", "raster", "path", "C:/test/terrain.asc"));
        assertTrue(gisDataController.upload(context).getStatusCode().is2xxSuccessful());

        ResponseEntity<Map<String, Object>> response = agentController.agenticChat(
                new AgentController.ChatRequest("执行洪水分析 80mm，24h，20年", memoryId));
        assertEquals("Success", ((Map<String, Object>) response.getBody().get("outcome")).get("status"));
        JSONObject scenario = JSON.parseObject(gisContextService.getGeoJson(memoryId)).getJSONObject("rainfall_scenario");
        assertEquals(80.0, scenario.getDoubleValue("rainfallMm"));
        assertEquals(24.0, scenario.getDoubleValue("durationHours"));
        assertEquals(20, scenario.getIntValue("returnPeriodYears"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void vectorFileUploadStoresGeoJsonInTheRequestedDataset() {
        String memoryId = "vector-upload-" + UUID.randomUUID();
        MockMultipartFile file = new MockMultipartFile("file", "aoi.geojson", "application/geo+json", """
                {"type":"Feature","geometry":{"type":"Polygon","coordinates":[[[121.47,31.23],[121.471,31.23],[121.471,31.231],[121.47,31.23],[121.47,31.23]]]},"properties":{}}
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        ResponseEntity<Map<String, Object>> response = gisDataController.uploadSpatialData(
                file, "aoi", memoryId, 0, "EPSG:4326");

        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertEquals("Success", response.getBody().get("status"));
        assertEquals("vector", response.getBody().get("dataType"));
        assertTrue((Boolean) response.getBody().get("hasAoi"));
    }

    @Test
    void groundElevationSamplesBecomeAnAvailableDemDataset() {
        String memoryId = "ground-dem-" + UUID.randomUUID();
        ResponseEntity<Map<String, Object>> response = gisDataController.saveGroundDem(Map.of(
                "memoryId", memoryId, "contextVersion", 0, "sourceCrs", "EPSG:4326",
                "dem", Map.of("type", "FeatureCollection", "features", List.of(
                        elevationFeature(121.4700, 31.2300, 8.0),
                        elevationFeature(121.4705, 31.2305, 4.0),
                        elevationFeature(121.4710, 31.2310, 6.0)
                ))));

        assertTrue(response.getStatusCode().is2xxSuccessful());
        assertEquals("Success", response.getBody().get("status"));
        assertEquals(3, response.getBody().get("sampleCount"));
        assertTrue(((List<?>) response.getBody().get("availableData")).contains("dem"));
    }

    private Map<String, Object> elevationFeature(double longitude, double latitude, double elevation) {
        return Map.of("type", "Feature",
                "geometry", Map.of("type", "Point", "coordinates", List.of(longitude, latitude)),
                "properties", Map.of("elevation_m", elevation));
    }

    @SuppressWarnings("unchecked")
    private void assertAdvancedContract(String memoryId, String message, String capabilityId) {
        ResponseEntity<Map<String, Object>> response = agentController.agenticChat(
                new AgentController.ChatRequest(message, memoryId));

        assertTrue(response.getStatusCode().is2xxSuccessful());
        Map<String, Object> body = response.getBody();
        assertNotNull(body);
        Map<String, Object> outcome = (Map<String, Object>) body.get("outcome");
        assertEquals("Success", outcome.get("status"));
        assertEquals(capabilityId, outcome.get("analysisType"));
        assertTrue(((List<?>) body.get("trace")).stream()
                .anyMatch(item -> item instanceof org.example.agent.AgentLoopService.ExecutionTrace trace
                        && "plan".equals(trace.phase())));
        assertFalse(((JSONArray) body.get("commands")).isEmpty());

        Map<String, Object> envelope = (Map<String, Object>) body.get("resultEnvelope");
        Map<String, Object> provenance = (Map<String, Object>) envelope.get("provenance");
        assertNotNull(provenance.get("runId"));
        assertEquals(capabilityId, provenance.get("capabilityId"));
    }
}
