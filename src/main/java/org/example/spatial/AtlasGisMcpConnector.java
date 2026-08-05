package org.example.spatial;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/** Optional Atlas GIS MCP Streamable-HTTP bridge. Read-only discovery only. */
@Component
public class AtlasGisMcpConnector {
    private final RestTemplate http = new RestTemplate();
    @Value("${spatial.atlas-mcp.url:}") private String endpoint;
    @Value("${spatial.atlas-mcp.search-tool:search_data}") private String searchTool;

    public List<Map<String, Object>> search(String dataset, Map<String, Object> aoi) {
        if (endpoint == null || endpoint.isBlank()) return List.of();
        JSONObject request = new JSONObject();
        request.put("jsonrpc", "2.0"); request.put("id", "spatial-discovery"); request.put("method", "tools/call");
        request.put("params", Map.of("name", searchTool, "arguments", Map.of("dataset", dataset, "aoi", aoi == null ? Map.of() : aoi)));
        try {
            ResponseEntity<String> response = http.postForEntity(endpoint, request.toJSONString(), String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) return List.of();
            JSONObject body = JSON.parseObject(response.getBody());
            Object rows = body.getJSONObject("result") == null ? null : body.getJSONObject("result").get("candidates");
            if (rows instanceof com.alibaba.fastjson.JSONArray values) {
                java.util.List<Map<String, Object>> candidates = new java.util.ArrayList<>();
                for (Object value : values) if (value instanceof Map<?, ?> map) {
                    Map<String, Object> candidate = new java.util.LinkedHashMap<>();
                    map.forEach((key, item) -> candidate.put(String.valueOf(key), item)); candidates.add(candidate);
                }
                return candidates;
            }
        } catch (Exception ignored) { }
        return List.of();
    }

    public boolean configured() { return endpoint != null && !endpoint.isBlank(); }
}
