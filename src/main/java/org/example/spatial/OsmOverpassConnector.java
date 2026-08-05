package org.example.spatial;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** OSM Overpass reader. Discovery and import happen only after explicit API calls. */
@Component
public class OsmOverpassConnector {
    private final RestTemplate http = new RestTemplate();
    @Value("${spatial.osm-overpass.url:https://overpass-api.de/api/interpreter}") private String endpoint;

    public Map<String, Object> discover(String dataset, Map<String, Object> bbox) {
        String selector = selector(dataset); if (selector == null) return Map.of();
        String box = box(bbox); if (box == null) return Map.of();
        String query = "[out:json][timeout:25];(" + selector + "(" + box + "););out count;";
        JSONObject result = request(query); int count = result == null ? -1 : result.getJSONArray("elements").getJSONObject(0).getIntValue("tags.total");
        return Map.of("source", "osm_overpass", "dataset", dataset, "estimatedFeatureCount", count, "bbox", bbox,
                "importAction", "POST /api/gis/data-discovery/import", "requiresUserConfirmation", true);
    }

    public JSONObject fetchGeoJson(String dataset, Map<String, Object> bbox) {
        String selector = selector(dataset); String box = box(bbox);
        if (selector == null || box == null) throw new IllegalArgumentException("osm_dataset_or_aoi_invalid");
        JSONObject source = request("[out:json][timeout:60];(" + selector + "(" + box + "););out geom;");
        if (source == null) throw new IllegalArgumentException("osm_request_failed");
        JSONArray features = new JSONArray();
        for (Object raw : source.getJSONArray("elements")) {
            if (!(raw instanceof JSONObject item) || !"way".equals(item.getString("type"))) continue;
            JSONArray geometry = item.getJSONArray("geometry"); if (geometry == null || geometry.size() < 2) continue;
            JSONArray coordinates = new JSONArray(); for (Object point : geometry) { JSONObject p=(JSONObject) point; coordinates.add(List.of(p.getDoubleValue("lon"), p.getDoubleValue("lat"))); }
            boolean closed = coordinates.size() > 3 && coordinates.getJSONArray(0).equals(coordinates.getJSONArray(coordinates.size()-1));
            JSONObject feature = new JSONObject(); feature.put("type", "Feature"); feature.put("properties", item.getJSONObject("tags") == null ? Map.of() : item.getJSONObject("tags"));
            feature.put("geometry", Map.of("type", closed ? "Polygon" : "LineString", "coordinates", closed ? List.of(coordinates) : coordinates)); features.add(feature);
        }
        return new JSONObject(Map.of("type", "FeatureCollection", "features", features));
    }

    private JSONObject request(String query) { try { return JSON.parseObject(http.postForObject(endpoint, query, String.class)); } catch (Exception ignored) { return null; } }
    private String selector(String dataset) { return switch (dataset) { case "buildings" -> "way[building]"; case "roads" -> "way[highway]"; case "waterways" -> "way[waterway]"; default -> null; }; }
    private String box(Map<String, Object> bbox) { try { List<?> b=(List<?>)bbox.get("bbox"); return b.get(1)+","+b.get(0)+","+b.get(3)+","+b.get(2); } catch(Exception e){ return null; } }
}
