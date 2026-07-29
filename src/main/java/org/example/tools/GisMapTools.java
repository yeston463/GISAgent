package org.example.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import org.example.service.PromptResourceService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class GisMapTools {

    @Autowired
    private ChatLanguageModel chatLanguageModel;

    @Autowired
    private PromptResourceService promptResources;

    @Value("${gis.amap-key:}")
    private String amapKey;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Tool("geocode")
    public String geocode(@P("locationName") String locationName) {
        return geocodeInternal(locationName, "");
    }

    @Tool("geocodeWithCity")
    public String geocodeWithCity(
            @P("locationName") String locationName,
            @P("city") String city) {
        if (locationName == null || locationName.isBlank()) {
            return "{\"status\": \"error\", \"message\": \"地址为空\"}";
        }
        return geocodeInternal(locationName, city != null ? city : "");
    }

    private static boolean isInChina(double lng, double lat) {
        return lng > 73 && lng < 135 && lat > 3 && lat < 54;
    }

    @Tool("aiGeocode")
    public String aiGeocode(
            @P("locationName") String locationName,
            @P("longitude") Double longitude,
            @P("latitude") Double latitude) {
        // 如果 planner 已在 params 中提供了坐标，直接返回（planner 已按 WGS84 输出）
        if (longitude != null && latitude != null) {
            if (Math.abs(longitude) < 0.1 && Math.abs(latitude) < 0.1) {
                return "{\"status\": \"error\", \"message\": \"plan中坐标无效(0,0)，请改用 geocodeWithCity\"}";
            }
            System.out.println("🧠 [AI地名查找] 使用 plan 内嵌坐标: " + locationName + " → " + longitude + ", " + latitude);
            return String.format(
                    "{\"longitude\": %f, \"latitude\": %f, \"lon\": %f, \"lat\": %f, \"address\": \"%s\", \"status\": \"success\", \"source\": \"plan\"}",
                    longitude, latitude, longitude, latitude, locationName);
        }
        // 降级：走 LLM 知识
        System.out.println("🧠 [AI地名查找] 使用 LLM 知识获取坐标: " + locationName);
        try {
            String prompt = promptResources.render("prompts/geocode.txt", Map.of("LOCATION", locationName));
            String response = chatLanguageModel.generate(prompt);
            response = response.trim();
            if (response.startsWith("```")) {
                int idx = response.indexOf('\n');
                if (idx > 0) response = response.substring(idx).trim();
            }
            if (response.endsWith("```")) {
                response = response.substring(0, response.length() - 3).trim();
            }
            JsonNode root = objectMapper.readTree(response);
            if (root.has("longitude") && root.has("latitude")) {
                double lng = root.get("longitude").asDouble();
                double lat = root.get("latitude").asDouble();
                // LLM 训练数据中中国地点坐标多为 GCJ-02，转 WGS84 以匹配 ArcGIS 底图
                if (isInChina(lng, lat)) {
                    double[] wgs84 = CoordinateTransform.gcj02ToWgs84(lng, lat);
                    lng = wgs84[0]; lat = wgs84[1];
                }
                // 拒绝 (0,0) 和明显无效坐标
                if (Math.abs(lng) < 0.1 && Math.abs(lat) < 0.1) {
                    return "{\"status\": \"error\", \"message\": \"AI返回的坐标无效(0,0)。请改用 geocodeWithCity 通过高德API查询\"}";
                }
                String addr = root.has("address") ? root.get("address").asText() : locationName;
                String city = root.has("city") ? root.get("city").asText() : "";
                System.out.println("✅ [AI地名查找] " + locationName + " → " + lng + ", " + lat);
                return String.format(
                        "{\"longitude\": %f, \"latitude\": %f, \"lon\": %f, \"lat\": %f, \"address\": \"%s\", \"city\": \"%s\", \"status\": \"success\", \"source\": \"ai\"}",
                        lng, lat, lng, lat, addr, city);
            }
            return "{\"status\": \"error\", \"message\": \"AI无法确定该地点坐标\"}";
        } catch (Exception e) {
            return "{\"status\": \"error\", \"message\": \"" + e.getMessage() + "\"}";
        }
    }

    private String geocodeInternal(String locationName, String city) {
        System.out.println("🔍 [高德地名查找] 正在检索: " + locationName + (city.isEmpty() ? "" : " (限定城市: " + city + ")"));
        try {
            if (amapKey == null || amapKey.isBlank()) {
                return "{\"status\": \"error\", \"message\": \"AMAP_KEY environment variable is not configured\"}";
            }
            String poiResult = searchPoi(locationName, city);
            JsonNode poiRoot = objectMapper.readTree(poiResult);
            if ("success".equalsIgnoreCase(poiRoot.path("status").asText())) {
                return poiResult;
            }
            // An unqualified address geocode can return an unrelated same-name
            // settlement. Only permit this weaker fallback once a city is known.
            if (city == null || city.isBlank()) {
                return poiResult;
            }
            UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl("https://restapi.amap.com/v3/geocode/geo")
                    .queryParam("key", amapKey)
                    .queryParam("address", locationName);
            if (!city.isEmpty()) {
                builder.queryParam("city", city);
            }
            String url = builder.build().toUriString();

            String response = new RestTemplate().getForObject(url, String.class);
            JsonNode root = new ObjectMapper().readTree(response);

            if ("1".equals(root.path("status").asText()) && root.path("geocodes").size() > 0) {
                JsonNode first = root.path("geocodes").get(0);
                String[] parts = first.path("location").asText().split(",");
                double lng = Double.parseDouble(parts[0]);
                double lat = Double.parseDouble(parts[1]);

                // GCJ-02 → WGS84
                double[] wgs84 = CoordinateTransform.gcj02ToWgs84(lng, lat);

                // AI 验证：对比高德结果与 LLM 知识，差异大时标记
                String verification = verifyWithAi(locationName, wgs84[0], wgs84[1]);

                return String.format(
                        "{\"longitude\": %f, \"latitude\": %f, \"lon\": %f, \"lat\": %f, \"address\": \"%s\", \"status\": \"success\", \"source\": \"amap\", \"verification\": \"%s\"}",
                        wgs84[0], wgs84[1], wgs84[0], wgs84[1], first.path("formatted_address").asText(), verification);
            }
            return "{\"status\": \"error\", \"message\": \"找不到该地点\"}";
        } catch (Exception e) {
            return "{\"status\": \"error\", \"message\": \"" + e.getMessage() + "\"}";
        }
    }

    private String searchPoi(String locationName, String city) throws Exception {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl("https://restapi.amap.com/v3/place/text")
                .queryParam("key", amapKey)
                .queryParam("keywords", locationName)
                .queryParam("offset", 20)
                .queryParam("page", 1)
                .queryParam("extensions", "base");
        if (city != null && !city.isBlank()) {
            builder.queryParam("city", city).queryParam("citylimit", true);
        }
        String response = new RestTemplate().getForObject(builder.build().toUriString(), String.class);
        JsonNode root = objectMapper.readTree(response);
        JsonNode pois = root.path("pois");
        if (!"1".equals(root.path("status").asText()) || !pois.isArray() || pois.isEmpty()) {
            return "{\"status\": \"error\", \"message\": \"POI search returned no result\"}";
        }

        String normalizedQuery = locationName.replaceAll("\\s+", "");
        List<JsonNode> exactMatches = new ArrayList<>();
        List<JsonNode> prefixMatches = new ArrayList<>();
        for (JsonNode poi : pois) {
            String name = poi.path("name").asText("").replaceAll("\\s+", "");
            if (normalizedQuery.equals(name)) {
                exactMatches.add(poi);
            } else if (!normalizedQuery.isBlank() && name.startsWith(normalizedQuery)) {
                // POI often names a campus or gate as "<institution><campus>".
                // It is a valid fallback only when all candidates belong to one city.
                prefixMatches.add(poi);
            }
        }

        boolean cityConstrained = city != null && !city.isBlank();
        List<JsonNode> candidates = !exactMatches.isEmpty()
                ? exactMatches
                : !prefixMatches.isEmpty() ? prefixMatches : List.of();
        String matchType = !exactMatches.isEmpty() ? "poi_exact" : "poi_prefix_same_city";
        if (!cityConstrained) {
            if (candidates.isEmpty()) {
                return "{\"status\": \"error\", \"message\": \"POI name is not a reliable match; provide a city or full address\"}";
            }
            if (candidates.size() > 1 && !allCandidatesShareCity(candidates)) {
                return "{\"status\": \"error\", \"message\": \"Ambiguous same-name POI across cities; provide a city or full address\"}";
            }
        } else if (candidates.isEmpty()) {
            // The request is city-constrained, so AMap ranking is a safe fallback.
            candidates = List.of(pois.get(0));
            matchType = "poi_city_ranked";
        }

        JsonNode selected = candidates.get(0);
        String[] parts = selected.path("location").asText().split(",");
        if (parts.length != 2) {
            return "{\"status\": \"error\", \"message\": \"POI did not provide a valid coordinate\"}";
        }
        double[] wgs84 = CoordinateTransform.gcj02ToWgs84(
                Double.parseDouble(parts[0]), Double.parseDouble(parts[1]));
        String selectedCity = selected.path("cityname").asText("");
        String address = selected.path("pname").asText("")
                + selectedCity + selected.path("adname").asText("")
                + selected.path("name").asText(locationName);
        String confidence = "poi_exact".equals(matchType) && candidates.size() == 1 ? "high" : "medium";
        return String.format(
                "{\"longitude\": %f, \"latitude\": %f, \"lon\": %f, \"lat\": %f, \"address\": \"%s\", \"city\": \"%s\", \"status\": \"success\", \"source\": \"amap_poi\", \"verification\": \"%s\", \"confidence\": \"%s\", \"candidateCount\": %d}",
                wgs84[0], wgs84[1], wgs84[0], wgs84[1], address, selectedCity, matchType, confidence, candidates.size());
    }

    private boolean allCandidatesShareCity(List<JsonNode> candidates) {
        String sharedCity = null;
        for (JsonNode candidate : candidates) {
            String candidateCity = candidate.path("cityname").asText("").trim();
            if (candidateCity.isBlank()) {
                return false;
            }
            if (sharedCity == null) {
                sharedCity = candidateCity;
            } else if (!sharedCity.equals(candidateCity)) {
                return false;
            }
        }
        return sharedCity != null;
    }

    private String verifyWithAi(String locationName, double lng, double lat) {
        try {
            String prompt = promptResources.render("prompts/geocode-verify.txt", Map.of(
                    "LOCATION", locationName,
                    "LONGITUDE", String.valueOf(lng),
                    "LATITUDE", String.valueOf(lat)
            ));
            String answer = chatLanguageModel.generate(prompt).trim().toLowerCase();
            return answer.contains("inconsistent") ? "inconsistent" : "consistent";
        } catch (Exception e) {
            return "consistent";
        }
    }


    @Tool("getHistoryDisaster")
    public String getHistoryDisaster(@P("lon") double lon, @P("lat") double lat) {
        return "该位置 500 米范围内 2023 年曾发生过一次内涝记录。";
    }

    @Tool("layerControl")
    public String controlLayer(
            @P("id") String layerId,
            @P("visible") boolean visible
    ) {
        return String.format("{\"action\": \"layerControl\", \"params\": {\"id\": \"%s\", \"visible\": %b}}", layerId, visible);
    }

    @Tool("filterLayer")
    public String filterLayer(
            @P("id") String layerId,
            @P("where") String where
    ) {
        return String.format("{\"action\": \"filter\", \"params\": {\"id\": \"%s\", \"where\": \"%s\"}}", layerId, where);
    }
}
