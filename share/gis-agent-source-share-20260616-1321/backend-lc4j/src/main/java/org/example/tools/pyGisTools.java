package org.example.tools;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.example.service.GisContextService;
import org.example.service.KnowledgeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class pyGisTools {

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private GisContextService contextService;

    @Autowired
    private KnowledgeService knowledgeService;

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

    @Tool("evaluatePlanningDemo")
    public Map<String, Object> evaluatePlanningDemo() {
        Map<String, Object> result = postForMap("/demo_case/evaluate", Map.of());
        saveContextFromResult(result);
        return result;
    }
    @Tool("gisRuntimeStatus")
    public Map<String, Object> gisRuntimeStatus() {
        return getForMap("/runtime");
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

    @Tool("urbanMetrics")
    public Map<String, Object> urbanMetrics(@P("aoiGeoJson") String aoiGeoJson) {
        return analyzeCurrentView();
    }

    @Tool("calculateUrbanMetrics")
    public Map<String, Object> calculateUrbanMetrics(@P("aoiGeoJson") String aoiGeoJson) {
        return analyzeCurrentView();
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

    private void saveContextFromResult(Map<String, Object> result) {
        if (result == null || result.isEmpty()) {
            return;
        }
        if (!result.containsKey("buildings") && !result.containsKey("aoi")) {
            return;
        }

        Map<String, Object> context = new HashMap<>();
        if (result.containsKey("buildings")) {
            context.put("buildings", result.get("buildings"));
        }
        if (result.containsKey("aoi")) {
            context.put("aoi", result.get("aoi"));
        }
        contextService.saveGeoJson(JSON.toJSONString(context));
    }
}

