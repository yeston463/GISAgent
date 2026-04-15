package org.example.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class GisMapTools {

    // 建议在 application.properties 中配置 amap.key


    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Tool("根据地名或地址获取经纬度坐标")
    public String geocode(@P("地名，如：北京大学、天安门") String locationName) {
        System.out.println("🔍 [地名查找] 正在通过高德检索: " + locationName);
        try {
            String myKey = "e7380cee15eb2fc2fd75440e2f1bfe4d"; // 你的Key
            String url = UriComponentsBuilder.fromHttpUrl("https://restapi.amap.com/v3/geocode/geo")
                    .queryParam("key", myKey)
                    .queryParam("address", locationName)
                    .build().toUriString();

            String response = new RestTemplate().getForObject(url, String.class);
            JsonNode root = new ObjectMapper().readTree(response);

            if ("1".equals(root.path("status").asText()) && root.path("geocodes").size() > 0) {
                JsonNode first = root.path("geocodes").get(0);
                String[] parts = first.path("location").asText().split(",");
                double lng = Double.parseDouble(parts[0]);
                double lat = Double.parseDouble(parts[1]);

                // 核心：立即纠偏为 WGS84，确保 3D 视图和 Python 计算都不偏移
                double[] wgs84 = CoordinateTransform.gcj02ToWgs84(lng, lat);

                // 返回标准 JSON 字符串，AI 会自动解析这些字段
                return String.format(
                        "{\"longitude\": %f, \"latitude\": %f, \"address\": \"%s\", \"status\": \"success\"}",
                        wgs84[0], wgs84[1], first.path("formatted_address").asText()
                );
            }
            return "{\"status\": \"error\", \"message\": \"找不到该地点\"}";
        } catch (Exception e) {
            return "{\"status\": \"error\", \"message\": \"" + e.getMessage() + "\"}";
        }
    }


    @Tool("查询指定区域的历史灾害记录")
    public String getHistoryDisaster(@P("经度") double lon, @P("纬度") double lat) {
        return "该位置 500 米范围内 2023 年曾发生过一次内涝记录。";
    }

    @Tool("根据业务需求开启或关闭地图图层")
    public String controlLayer(
            @P("图层名称或ID") String layerId,
            @P("是否显示") boolean visible
    ) {
        return String.format("{\"action\": \"layerControl\", \"params\": {\"id\": \"%s\", \"visible\": %b}}", layerId, visible);
    }

    @Tool("对地图图层进行属性过滤查询")
    public String filterLayer(
            @P("图层ID") String layerId,
            @P("SQL过滤条件，如 status='illegal'") String where
    ) {
        return String.format("{\"action\": \"filter\", \"params\": {\"id\": \"%s\", \"where\": \"%s\"}}", layerId, where);
    }
}