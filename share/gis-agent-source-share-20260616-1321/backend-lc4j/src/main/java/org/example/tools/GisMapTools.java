package org.example.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class GisMapTools {

    @Autowired
    private ChatLanguageModel chatLanguageModel;

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
            String prompt = "你是一个地理坐标专家。请直接给出「" + locationName + "」的WGS84经纬度坐标（GCJ-02纠偏前的原始坐标）。"
                    + "只返回纯JSON，不要任何其他文字：{\"longitude\": 116.xx, \"latitude\": 39.xx, \"address\": \"...\"}";
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
                System.out.println("✅ [AI地名查找] " + locationName + " → " + lng + ", " + lat);
                return String.format(
                        "{\"longitude\": %f, \"latitude\": %f, \"lon\": %f, \"lat\": %f, \"address\": \"%s\", \"status\": \"success\", \"source\": \"ai\"}",
                        lng, lat, lng, lat, addr);
            }
            return "{\"status\": \"error\", \"message\": \"AI无法确定该地点坐标\"}";
        } catch (Exception e) {
            return "{\"status\": \"error\", \"message\": \"" + e.getMessage() + "\"}";
        }
    }

    private String geocodeInternal(String locationName, String city) {
        System.out.println("🔍 [高德地名查找] 正在检索: " + locationName + (city.isEmpty() ? "" : " (限定城市: " + city + ")"));
        try {
            String myKey = "e7380cee15eb2fc2fd75440e2f1bfe4d";
            UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl("https://restapi.amap.com/v3/geocode/geo")
                    .queryParam("key", myKey)
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

    private String verifyWithAi(String locationName, double lng, double lat) {
        try {
            String prompt = "判断WGS84坐标(" + lng + ", " + lat + ")是否与「" + locationName + "」的实际地理位置一致。"
                    + "只返回一个词：consistent 或 inconsistent";
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