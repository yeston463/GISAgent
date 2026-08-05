package org.example.spatial;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** ArcGIS Portal/Living Atlas metadata discovery. No data import here. */
@Component
public class ArcGisPortalConnector {
    private final RestTemplate http = new RestTemplate();
    public List<Map<String, Object>> search(String dataset) {
        String query = switch (dataset) { case "dem" -> "type:(\"Image Service\" OR \"Elevation Layer\")"; case "buildings", "roads", "waterways" -> "type:\"Feature Service\""; default -> "type:(\"Feature Service\" OR \"Image Service\")"; };
        try {
            String url = "https://www.arcgis.com/sharing/rest/search?f=json&num=10&q=" + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8);
            JSONObject body = JSON.parseObject(http.getForObject(url, String.class)); List<Map<String, Object>> result = new ArrayList<>();
            for (Object raw : body.getJSONArray("results")) { JSONObject item=(JSONObject)raw; result.add(Map.of("source", "arcgis_portal", "dataset", dataset,
                    "title", item.getString("title"), "type", item.getString("type"), "url", item.getString("url"), "updated", item.getLongValue("modified"), "id", item.getString("id"), "requiresUserConfirmation", true)); }
            return result;
        } catch (Exception ignored) { return List.of(); }
    }
}
