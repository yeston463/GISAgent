package org.example.tools;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.example.agent.AgentLoopService;
import org.example.agent.Scenario;
import org.example.agent.ScenarioResult;
import org.example.service.GisContextService;
import org.example.service.KnowledgeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class pyGisTools {

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private GisContextService contextService;

    @Autowired
    private KnowledgeService knowledgeService;

    @Autowired
    private AgentLoopService agentLoopService;

    @Value("${gis.python-service-url:http://127.0.0.1:8000/analysis}")
    private String pythonServiceUrl;

    @Tool("bufferAnalysis")
    public Map<String, Object> bufferAnalysis(
            @P("lon") double lon,
            @P("lat") double lat,
            @P("radius") double radius) {

        if (Math.abs(lon) < 0.1 && Math.abs(lat) < 0.1) {
            return Map.of("status", "Error", "message", "Invalid coordinate (0,0).");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "Success");
        result.put("action", "addBuffer");
        result.put("params", Map.of(
                "longitude", lon,
                "latitude", lat,
                "radius", radius
        ));
        return result;
    }

    @Tool("analyzeArea")
    public Map<String, Object> analyzeArea(
            @P("lon") double lon,
            @P("lat") double lat,
            @P("radius") double radius) {

        if (Math.abs(lon) < 0.1 && Math.abs(lat) < 0.1) {
            return Map.of("status", "Error", "message", "Invalid coordinate (0,0).");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("lon", lon);
        payload.put("lat", lat);
        payload.put("radius", radius > 0 ? radius : 500);

        Map<String, Object> result = postForMap("/analyze_area", payload);
        saveContextFromResult(result);
        return result;
    }

    @Tool("fetchBuildingsFromOSM")
    public Map<String, Object> fetchBuildingsFromOSM(
            @P("lon") double lon,
            @P("lat") double lat,
            @P("radius") double radius) {

        if (Math.abs(lon) < 0.1 && Math.abs(lat) < 0.1) {
            return Map.of("status", "Error", "message", "Invalid coordinate (0,0).");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("lon", lon);
        payload.put("lat", lat);
        payload.put("radius", radius > 0 ? radius : 500);

        Map<String, Object> result = postForMap("/fetch_buildings", payload);
        saveContextFromResult(result);
        return result;
    }

    @Tool("getScreenBuildings")
    public Map<String, Object> getScreenBuildings() {
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("status", "Success");
        res.put("action", "getScreenBuildings");
        res.put("message", "Fallback only: ask the ArcGIS frontend to query loaded building tiles and upload them.");
        return res;
    }

    @Tool("submitCityEnginePlanningJob")
    public Map<String, Object> submitCityEnginePlanningJob(
            @P("requirementsJson") String requirementsJson,
            @P("ragContext") String ragContext,
            @P("userRequest") String userRequest,
            @P("useDemoCase") boolean useDemoCase) {
        Map<String, Object> payload = new LinkedHashMap<>();
        try {
            payload.put("requirements", JSON.parseObject(requirementsJson));
        } catch (Exception ignored) {
            payload.put("requirements", Map.of());
        }
        payload.put("ragContext", ragContext != null ? ragContext : "");
        payload.put("userRequest", userRequest != null ? userRequest : "");

        if (useDemoCase) {
            Map<String, Object> result = postForMap("/demo_case/evaluate", payload);
            saveContextFromResult(result);
            return result;
        }

        String geoJson = contextService.getGeoJson();
        if (geoJson == null || geoJson.isBlank() || "{}".equals(geoJson)) {
            return Map.of("status", "NoData", "message", "请先绘制 AOI 或选择地点周边范围，再生成 CityEngine 优化方案。");
        }
        JSONObject context = JSON.parseObject(geoJson);
        if (!context.containsKey("aoi")) {
            return Map.of("status", "NoData", "message", "当前地图上下文缺少 AOI，请先绘制地块或创建地点缓冲区。");
        }
        if (!context.containsKey("buildings")) {
            Map<String, Object> fetchPayload = new LinkedHashMap<>();
            fetchPayload.put("aoi", context.get("aoi"));
            Map<String, Object> fetched = postForMap("/analyze_area", fetchPayload);
            if (isErrorResult(fetched)) {
                return fetched;
            }
            saveContextFromResult(fetched);
            context = JSON.parseObject(contextService.getGeoJson());
        }
        payload.put("aoi", context.get("aoi"));
        payload.put("buildings", context.get("buildings"));
        Map<String, Object> result = postForMap("/cityengine/plan-context", payload);
        saveContextFromResult(result);
        return result;
    }

    private boolean isErrorResult(Map<String, Object> result) {
        if (result == null) return true;
        String status = String.valueOf(result.getOrDefault("status", ""));
        return "Error".equalsIgnoreCase(status) || "NoData".equalsIgnoreCase(status);
    }
    @Tool("waitCityEngineJob")
    public Map<String, Object> waitCityEngineJob(@P("jobId") String jobId, @P("timeoutSeconds") int timeoutSeconds) {
        int timeout = Math.max(1, Math.min(timeoutSeconds, 600));
        return getForMap("/cityengine/jobs/" + jobId + "/wait?timeout=" + timeout);
    }
    public Map<String, Object> getCityEngineJob(String jobId) {
        return getForMap("/cityengine/jobs/" + jobId);
    }
    public Map<String, Object> publishCityEngineJob(String jobId) {
        return postForMap("/cityengine/jobs/" + jobId + "/publish", Map.of());
    }
    @Tool("gisRuntimeStatus")
    public Map<String, Object> gisRuntimeStatus() {
        return getForMap("/runtime");
    }

    @Tool("executeSpatialAnalysis")
    public Map<String, Object> executeSpatialAnalysis(
            @P("operation") String operation,
            @P("paramsJson") String paramsJson) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("operation", operation);
        try {
            payload.put("params", paramsJson == null || paramsJson.isBlank()
                    ? Map.of() : JSON.parseObject(paramsJson));
        } catch (Exception e) {
            return Map.of("status", "Error", "stage", "spatial_plan",
                    "message", "paramsJson must be valid JSON: " + e.getMessage());
        }
        Map<String, Object> result = postForMap("/execute", payload);
        saveContextFromResult(result);
        return result;
    }

    @Tool("analyzeCurrentView")
    public Map<String, Object> analyzeCurrentView() {
        String geoJson = contextService.getGeoJson();

        if (geoJson == null || geoJson.isEmpty() || "{}".equals(geoJson)) {
            return Map.of(
                    "status", "NoData",
                    "message", "No server-side GIS context is available. Use analyzeArea or fetchBuildingsFromOSM first."
            );
        }

        try {
            JSONObject contextObj = JSON.parseObject(geoJson);
            if (!contextObj.containsKey("buildings") && contextObj.containsKey("aoi")) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("aoi", contextObj.get("aoi"));
                Map<String, Object> fetched = postForMap("/analyze_area", payload);
                saveContextFromResult(fetched);
                return fetched;
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("buildings", contextObj.get("buildings"));
            payload.put("aoi", contextObj.get("aoi"));
            Map<String, Object> result = postForMap("/urban_metrics", payload);
            saveContextFromResult(result);
            return result;
        } catch (Exception e) {
            return Map.of("status", "Error", "message", "Python GIS engine failed: " + e.getMessage());
        }
    }

    @Tool("skylineAnalysis")
    public Map<String, Object> skylineAnalysis() {
        return postAdvancedAnalysis("/skyline", Map.of());
    }

    @Tool("sunlightAnalysis")
    public Map<String, Object> sunlightAnalysis(@P("date") String date) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (date != null && !date.isBlank()) {
            payload.put("date", date);
        }
        return postAdvancedAnalysis("/sunlight", payload);
    }

    @Tool("floodAnalysis")
    public Map<String, Object> floodAnalysis(@P("returnPeriodYears") int returnPeriodYears) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (returnPeriodYears > 0) {
            payload.put("returnPeriodYears", returnPeriodYears);
        }
        return postAdvancedAnalysis("/flood", payload);
    }

    @Tool("siteSelection")
    public Map<String, Object> siteSelection(
            @P("candidatesGeoJson") String candidatesGeoJson,
            @P("facilitiesGeoJson") String facilitiesGeoJson,
            @P("constraintsGeoJson") String constraintsGeoJson,
            @P("weightsJson") String weightsJson) {
        try {
            if (candidatesGeoJson == null || candidatesGeoJson.isBlank()) {
                return Map.of("status", "NoData", "message", "candidatesGeoJson is required for site selection.");
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("candidates", JSON.parseObject(candidatesGeoJson));
            if (facilitiesGeoJson != null && !facilitiesGeoJson.isBlank()) payload.put("facilities", JSON.parseObject(facilitiesGeoJson));
            if (constraintsGeoJson != null && !constraintsGeoJson.isBlank()) payload.put("constraints", JSON.parseObject(constraintsGeoJson));
            if (weightsJson != null && !weightsJson.isBlank()) payload.put("weights", JSON.parseObject(weightsJson));
            Map<String, Object> result = postForMap("/site-selection", payload);
            saveContextFromResult(result);
            return result;
        } catch (Exception error) {
            return Map.of("status", "Error", "stage", "site_selection",
                    "message", "Site-selection GeoJSON and weights must be valid JSON objects: " + error.getMessage());
        }
    }

    private Map<String, Object> postAdvancedAnalysis(String path, Map<String, Object> options) {
        String geoJson = contextService.getGeoJson();
        if (geoJson == null || geoJson.isBlank() || "{}".equals(geoJson)) {
            return Map.of("status", "NoData", "analysis_type", path.substring(1),
                    "message", "请先完成地点分析或上传 AOI 与建筑数据");
        }
        try {
            JSONObject context = JSON.parseObject(geoJson);
            Map<String, Object> payload = new LinkedHashMap<>(context.getInnerMap());
            payload.putAll(options);
            Map<String, Object> result = postForMap(path, payload);
            saveContextFromResult(result);
            return result;
        } catch (Exception e) {
            return Map.of("status", "Error", "analysis_type", path.substring(1),
                    "message", "高级分析请求失败: " + e.getMessage());
        }
    }

    @Tool("urbanMetrics")
    public Map<String, Object> urbanMetrics(@P("aoiGeoJson") String aoiGeoJson) {
        return analyzeAoi(aoiGeoJson);
    }

    @Tool("calculateUrbanMetrics")
    public Map<String, Object> calculateUrbanMetrics(@P("aoiGeoJson") String aoiGeoJson) {
        return analyzeAoi(aoiGeoJson);
    }

    private Map<String, Object> analyzeAoi(String aoiGeoJson) {
        if (aoiGeoJson == null || aoiGeoJson.isBlank()) {
            return Map.of("status", "Error", "message", "aoiGeoJson is required.");
        }
        try {
            JSONObject aoi = JSON.parseObject(aoiGeoJson);
            if (aoi == null || !"Feature".equals(aoi.getString("type"))
                    || aoi.getJSONObject("geometry") == null) {
                return Map.of("status", "Error", "message", "aoiGeoJson must be a GeoJSON Feature with geometry.");
            }
            Map<String, Object> result = postForMap("/analyze_area", Map.of("aoi", aoi));
            saveContextFromResult(result);
            return result;
        } catch (Exception error) {
            return Map.of("status", "Error", "message", "aoiGeoJson must be valid JSON.");
        }
    }

    @Tool("executeBufferAnalysis")
    public Map<String, Object> executeBufferAnalysis(
            @P("center") double[] center,
            @P("radius") double radius) {
        if (center == null || center.length < 2) {
            return Map.of("status", "Error", "message", "center must be [lon, lat].");
        }
        return bufferAnalysis(center[0], center[1], radius);
    }

    @Tool("formatAnalysisResult")
    public String formatAnalysisResult(String resultData) {
        return resultData;
    }

    @Tool("knowledgeSearch")
    public String knowledgeSearch(@P("query") String query) {
        return knowledgeService.search(query, 3);
    }

    private Map<String, Object> postForMap(String path, Map<String, Object> payload) {
        try {
            String raw = restTemplate.postForObject(pythonUrl(path), payload, String.class);
            if (raw == null || raw.isBlank()) {
                return Map.of("status", "Error", "message", "Python GIS engine returned an empty response.");
            }
            JSONObject json = JSON.parseObject(raw);
            return new LinkedHashMap<>(json.getInnerMap());
        } catch (Exception e) {
            return Map.of("status", "Error", "message", "Python GIS engine is unavailable: " + e.getMessage());
        }
    }

    private Map<String, Object> getForMap(String path) {
        try {
            String raw = restTemplate.getForObject(pythonUrl(path), String.class);
            if (raw == null || raw.isBlank()) {
                return Map.of("status", "Error", "message", "Python GIS engine returned an empty response.");
            }
            JSONObject json = JSON.parseObject(raw);
            return new LinkedHashMap<>(json.getInnerMap());
        } catch (Exception e) {
            return Map.of("status", "Error", "message", "Python GIS engine is unavailable: " + e.getMessage());
        }
    }

    private String pythonUrl(String path) {
        String base = pythonServiceUrl == null || pythonServiceUrl.isBlank()
                ? "http://127.0.0.1:8000/analysis"
                : pythonServiceUrl.trim();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + path;
    }

    @Tool("scenarioComparison")
    public Map<String, Object> scenarioComparison(
            @P("capabilityId") String capabilityId) {
        try {
            JSONObject context = JSON.parseObject(contextService.getGeoJson());
            if (context == null || !context.containsKey("aoi") || !context.containsKey("buildings")) {
                return Map.of("status", "Error", "message", "No AOI/buildings in context. Run analyzeArea first.");
            }
            Map<String, Object> aoi = context.getJSONObject("aoi");
            List<Map<String, Object>> buildings = new ArrayList<>();
            JSONArray buildingArray = context.getJSONArray("buildings");
            if (buildingArray != null) {
                for (Object o : buildingArray) {
                    if (o instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> entry = (Map<String, Object>) o;
                        buildings.add(entry);
                    }
                }
            }
            double originalFar = context.getDoubleValue("far");
            if (originalFar <= 0) originalFar = 1.0;

            List<Scenario> scenarios = agentLoopService.generateScenarios(aoi, buildings, null);
            List<ScenarioResult> results = agentLoopService.evaluateScenarios(scenarios, capabilityId != null ? capabilityId : "urban_metrics", originalFar);

            List<Map<String, Object>> responseResults = new ArrayList<>();
            for (ScenarioResult r : results) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("scenarioId", r.scenario().id());
                entry.put("name", r.scenario().name());
                entry.put("description", r.scenario().description());
                entry.put("metrics", r.metrics());
                entry.put("violations", r.violations());
                entry.put("score", r.score());
                responseResults.add(entry);
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "Success");
            response.put("scenarios", responseResults);
            response.put("command", Map.of(
                    "action", "show-planning-comparison",
                    "params", Map.of("scenarios", responseResults)
            ));
            return response;
        } catch (Exception e) {
            return Map.of("status", "Error", "message", e.getMessage());
        }
    }

    private void saveContextFromResult(Map<String, Object> result) {
        if (result == null || result.isEmpty()) {
            return;
        }
        if (!result.containsKey("buildings") && !result.containsKey("aoi")) {
            return;
        }

        Map<String, Object> context = new HashMap<>();
        try {
            JSONObject existing = JSON.parseObject(contextService.getGeoJson());
            if (existing != null) {
                context.putAll(existing.getInnerMap());
            }
        } catch (Exception ignored) {
            // The incoming data starts a fresh context when no valid prior state exists.
        }
        if (result.containsKey("buildings")) {
            context.put("buildings", result.get("buildings"));
        }
        if (result.containsKey("aoi")) {
            context.put("aoi", result.get("aoi"));
        }
        contextService.saveGeoJson(JSON.toJSONString(context));
    }
}
