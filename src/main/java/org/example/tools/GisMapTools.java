package org.example.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;

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

    @Tool("searchNearbyPoi")
    public String searchNearbyPoi(
            @P("locationName") String locationName,
            @P("city") String city,
            @P("keyword") String keyword,
            @P("radius") Double radius) {
        if (locationName == null || locationName.isBlank()) {
            return "{\"status\": \"error\", \"message\": \"位置为空\"}";
        }
        if (keyword == null || keyword.isBlank()) {
            return "{\"status\": \"error\", \"message\": \"POI 关键词为空，例如：商场、超市、医院、学校\"}";
        }
        if (amapKey == null || amapKey.isBlank()) {
            return "{\"status\": \"error\", \"message\": \"AMAP_KEY environment variable is not configured\"}";
        }
        try {
            int searchRadius = radius == null || radius <= 0 ? 3000 : Math.min((int) Math.round(radius), 50000);
            // 1) 定位中心点（复用高德 geocode；结果为 WGS84）
            String geocodeJson = geocodeInternal(locationName, city);
            JsonNode geoRoot = objectMapper.readTree(geocodeJson);
            if (!"success".equalsIgnoreCase(geoRoot.path("status").asText())) {
                System.out.println("❌ [POI搜索] geocode 失败 locationName=" + locationName
                        + " city=" + city + " -> " + geocodeJson.substring(0, Math.min(200, geocodeJson.length())));
                System.out.flush();
                return geocodeJson;
            }
            double wgsLon = geoRoot.path("lon").asDouble(0);
            double wgsLat = geoRoot.path("lat").asDouble(0);
            if (wgsLon == 0 && wgsLat == 0) {
                return "{\"status\": \"error\", \"message\": \"无法解析位置坐标: " + locationName + "\"}";
            }
            // 高德周边搜索需要 GCJ02 坐标
            double[] gcj = CoordinateTransform.wgs84ToGcj02(wgsLon, wgsLat);
            String center = String.format(Locale.ROOT, "%.6f,%.6f", gcj[0], gcj[1]);
            System.out.println("📍 [高德周边POI] 中心: " + center + " 关键词: " + keyword + " 半径: " + searchRadius + "m"
                    + " | geocode: " + geocodeJson.substring(0, Math.min(180, geocodeJson.length())));
            System.out.flush();
            // 2) 周边搜索，按距离升序
            String uri = UriComponentsBuilder.fromHttpUrl("https://restapi.amap.com/v3/place/around")
                    .queryParam("key", amapKey)
                    .queryParam("location", center)
                    .queryParam("radius", searchRadius)
                    .queryParam("keywords", keyword)
                    .queryParam("offset", 10)
                    .queryParam("page", 1)
                    .queryParam("sortrule", "distance")
                    .queryParam("extensions", "base")
                    .build().toUriString();
            String response = new RestTemplate().getForObject(uri, String.class);
            JsonNode root = objectMapper.readTree(response);
            if (!"1".equals(root.path("status").asText())) {
                return "{\"status\": \"error\", \"message\": \"POI 周边搜索失败: "
                        + root.path("info").asText("unknown") + "\"}";
            }
            JsonNode pois = root.path("pois");
            if (!pois.isArray() || pois.isEmpty()) {
                return "{\"status\": \"nodata\", \"message\": \"半径 " + searchRadius
                        + " 米内未找到“" + keyword + "”相关 POI\"}";
            }
            ObjectNode result = objectMapper.createObjectNode();
            result.put("status", "success");
            result.put("query", keyword);
            result.put("center", center);
            result.put("radiusMeters", searchRadius);
            result.put("count", pois.size());
            ArrayNode list = result.putArray("pois");
            JsonNode nearest = null;
            for (JsonNode poi : pois) {
                ObjectNode item = list.addObject();
                String name = poi.path("name").asText("");
                String location = poi.path("location").asText("");
                int distance = poi.path("distance").asInt(-1);
                item.put("name", name);
                item.put("type", poi.path("type").asText(""));
                item.put("address", poi.path("address").asText(""));
                item.put("location", location);
                item.put("distanceMeters", distance);
                if (nearest == null) {
                    nearest = item;
                }
            }
            result.set("nearest", nearest);
            return result.toString();
        } catch (Exception error) {
            return "{\"status\": \"error\", \"message\": \"POI 搜索失败: " + error.getMessage() + "\"}";
        }
    }

    @Tool("resolveAdministrativeBoundary")
    public String resolveAdministrativeBoundary(@P("locationName") String locationName) {
        if (locationName == null || locationName.isBlank()) {
            return "{\"status\":\"error\",\"message\":\"地点为空\"}";
        }
        if (amapKey == null || amapKey.isBlank()) {
            return "{\"status\":\"error\",\"message\":\"AMAP_KEY environment variable is not configured\"}";
        }
        try {
            String queryName = locationName.trim();
            JsonNode district = com.fasterxml.jackson.databind.node.MissingNode.getInstance();
            String providerQuery = "";
            for (String candidateQuery : boundarySearchQueries(queryName)) {
                district = selectDistrictWithAi(fetchDistrictCandidates(candidateQuery), queryName);
                if (!district.isMissingNode()) {
                    providerQuery = candidateQuery;
                    break;
                }
            }
            if (district.isMissingNode()) {
                String geocodedAdcode = geocodeAdministrativeAdcode(queryName);
                if (!geocodedAdcode.isBlank()) {
                    district = selectDistrictWithAi(fetchDistrictCandidates(geocodedAdcode), queryName);
                    providerQuery = district.isMissingNode() ? "" : "adcode:" + geocodedAdcode;
                }
            }
            String boundary = district.path("polyline").asText("");
            if (district.isMissingNode() || boundary.isBlank()) {
                return "{\"status\":\"error\",\"message\":\"No AI-validated administrative boundary candidate was returned\"}";
            }
            ArrayNode polygons = objectMapper.createArrayNode();
            for (String rawRing : boundary.split(java.util.regex.Pattern.quote("|"))) {
                ArrayNode ring = objectMapper.createArrayNode();
                for (String coordinate : rawRing.split(";")) {
                    String[] parts = coordinate.split(",");
                    if (parts.length != 2) continue;
                    double[] wgs84 = CoordinateTransform.gcj02ToWgs84(
                            Double.parseDouble(parts[0]), Double.parseDouble(parts[1]));
                    ArrayNode point = ring.addArray(); point.add(wgs84[0]); point.add(wgs84[1]);
                }
                if (ring.size() >= 3) {
                    if (!ring.get(0).equals(ring.get(ring.size() - 1))) ring.add(ring.get(0));
                    polygons.addArray().add(ring);
                }
            }
            if (polygons.isEmpty()) return "{\"status\":\"error\",\"message\":\"行政区边界坐标无效\"}";
            String[] center = district.path("center").asText().split(",");
            ObjectNode result = objectMapper.createObjectNode();
            result.put("status", "success"); result.put("source", "amap_district");
            result.put("requestedLocation", queryName);
            result.put("providerQuery", providerQuery);
            result.put("location", district.path("name").asText(locationName.trim()));
            result.put("adcode", district.path("adcode").asText(""));
            if (center.length == 2) {
                double[] wgs84 = CoordinateTransform.gcj02ToWgs84(Double.parseDouble(center[0]), Double.parseDouble(center[1]));
                result.put("longitude", wgs84[0]); result.put("latitude", wgs84[1]);
            }
            ObjectNode feature = result.putObject("aoi"); feature.put("type", "Feature");
            feature.putObject("properties").put("source", "amap_district").put("name", result.path("location").asText());
            ObjectNode geometry = feature.putObject("geometry");
            if (polygons.size() == 1) { geometry.put("type", "Polygon"); geometry.set("coordinates", polygons.get(0)); }
            else { geometry.put("type", "MultiPolygon"); geometry.set("coordinates", polygons); }
            return objectMapper.writeValueAsString(result);
        } catch (Exception error) {
            return "{\"status\":\"error\",\"message\":\"行政区边界查询失败\"}";
        }
    }

    private JsonNode fetchDistrictCandidates(String query) throws Exception {
        String keyword = URLEncoder.encode(query, StandardCharsets.UTF_8);
        java.net.URI url = java.net.URI.create("https://restapi.amap.com/v3/config/district"
                + "?key=" + URLEncoder.encode(amapKey, StandardCharsets.UTF_8)
                + "&keywords=" + keyword + "&subdistrict=0&extensions=all");
        String response = restTemplate.getForObject(url, String.class);
        return objectMapper.readTree(response).path("districts");
    }

    private String geocodeAdministrativeAdcode(String locationName) throws Exception {
        java.net.URI url = java.net.URI.create("https://restapi.amap.com/v3/geocode/geo"
                + "?key=" + URLEncoder.encode(amapKey, StandardCharsets.UTF_8)
                + "&address=" + URLEncoder.encode(locationName, StandardCharsets.UTF_8));
        String response = restTemplate.getForObject(url, String.class);
        JsonNode geocodes = objectMapper.readTree(response).path("geocodes");
        if (!geocodes.isArray() || geocodes.isEmpty()) {
            return "";
        }
        return geocodes.get(0).path("adcode").asText("").trim();
    }

    private List<String> boundarySearchQueries(String locationName) {
        List<String> queries = new ArrayList<>();
        queries.add(locationName);
        String prompt = """
                You prepare administrative-boundary searches for a GIS provider.
                Convert the user's full place expression into up to two concise provider search terms.
                Preserve the intended city or district. Do not invent a place, province, or country.
                Include a shorter administrative name only when the full expression would not be a provider keyword.
                Reply with JSON only: {"queries":["term one","term two"]}.
                User location: %s
                """.formatted(locationName);
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                JsonNode plan = parseAiJson(chatLanguageModel.generate(prompt));
                JsonNode plannedQueries = plan.path("queries");
                if (!plannedQueries.isArray()) {
                    continue;
                }
                for (JsonNode item : plannedQueries) {
                    String query = item.asText("").trim();
                    if (!query.isBlank() && query.length() <= 80 && !queries.contains(query)) {
                        queries.add(query);
                    }
                }
                if (queries.size() > 1) {
                    break;
                }
            } catch (Exception ignored) {
                // The original location remains the only safe provider query.
            }
        }
        return queries;
    }

    private JsonNode selectDistrictWithAi(JsonNode districts, String requestedLocation) {
        if (districts == null || !districts.isArray()) {
            return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        }

        ArrayNode candidates = objectMapper.createArrayNode();
        for (JsonNode district : districts) {
            String adcode = district.path("adcode").asText("").trim();
            String boundary = district.path("polyline").asText("").trim();
            if (adcode.isBlank() || boundary.isBlank() || "100000".equals(adcode)) {
                continue;
            }
            ObjectNode candidate = candidates.addObject();
            candidate.put("adcode", adcode);
            candidate.put("name", district.path("name").asText(""));
            candidate.put("level", district.path("level").asText(""));
            candidate.put("center", district.path("center").asText(""));
        }
        if (candidates.isEmpty()) {
            return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        }
        if (candidates.size() == 1) {
            return districtForAdcode(districts, candidates.get(0).path("adcode").asText());
        }

        String prompt = """
                You select one administrative boundary for a GIS operation.
                Select only from the provider candidates below. Do not infer, rename, or invent an adcode.
                Choose the candidate that exactly represents the user's requested administrative area.
                Return exactly one JSON object: {\"adcode\":\"one provided adcode\"}.
                Return {\"adcode\":\"\"} when no candidate is reliable.
                User request: %s
                Provider candidates: %s
                """.formatted(requestedLocation, candidates);
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                JsonNode selection = parseAiJson(chatLanguageModel.generate(prompt));
                String selectedAdcode = selection.path("adcode").asText("").trim();
                if (!selectedAdcode.isBlank()) {
                    JsonNode selected = districtForAdcode(districts, selectedAdcode);
                    if (!selected.isMissingNode()) {
                        return selected;
                    }
                }
            } catch (Exception ignored) {
                // A model failure must not widen the requested AOI.
            }
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    private JsonNode parseAiJson(String answer) throws Exception {
        String text = answer == null ? "" : answer.trim();
        if (text.startsWith("```")) {
            int firstLine = text.indexOf('\n');
            if (firstLine < 0 || !text.endsWith("```")) {
                return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
            }
            text = text.substring(firstLine + 1, text.length() - 3).trim();
        }
        if (!text.startsWith("{") || !text.endsWith("}")) {
            return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        }
        return objectMapper.readTree(text);
    }

    static JsonNode districtForAdcode(JsonNode districts, String adcode) {
        if (districts == null || !districts.isArray()) return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        for (JsonNode candidate : districts) {
            if (adcode != null && adcode.equals(candidate.path("adcode").asText())
                    && !"100000".equals(adcode)
                    && !candidate.path("polyline").asText("").isBlank()) {
                return candidate;
            }
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
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
        String originalLocationName = locationName == null ? "" : locationName.trim();
        locationName = normalizeLocationName(originalLocationName);
        city = normalizeCity(city);
        if (city.isBlank()) {
            city = defaultCityFor(locationName);
        }
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
            LocationReview review = reviewLocationName(originalLocationName, city);
            if (review != null) {
                String reviewedName = normalizeLocationName(review.locationName());
                String reviewedCity = normalizeCity(review.city());
                if (reviewedCity.isBlank()) {
                    reviewedCity = defaultCityFor(reviewedName);
                }
                String reviewedPoiResult = searchPoi(reviewedName, reviewedCity);
                JsonNode reviewedPoiRoot = objectMapper.readTree(reviewedPoiResult);
                if ("success".equalsIgnoreCase(reviewedPoiRoot.path("status").asText())) {
                    System.out.println("✅ [AI地点复核] " + locationName + " → "
                            + review.locationName() + " (" + review.city() + ")");
                    return reviewedPoiResult;
                }
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

    /**
     * Turn common human shorthand into the institution name used by POI
     * providers.  This is deliberately conservative: only known aliases are
     * rewritten, while the original user text is still used in responses.
     */
    private String normalizeLocationName(String locationName) {
        String value = locationName == null ? "" : locationName
                .replaceAll("\\s+", "")
                .replace('（', '(')
                .replace('）', ')')
                .trim();
        if (value.equals("中国地质大学北京") || value.equals("中国地质大学(北京)")) {
            return "中国地质大学（北京）";
        }
        if (value.equals("中国地质大学北") || value.equals("中地大北京") || value.equals("地大北京")) {
            return "中国地质大学（北京）";
        }
        if (value.equals("中国地质大学武汉") || value.equals("中国地质大学(武汉)")) {
            return "中国地质大学（武汉）";
        }
        if (value.equals("中地大武汉") || value.equals("地大武汉")) {
            return "中国地质大学（武汉）";
        }
        if (value.equals("武大")) {
            return "武汉大学";
        }
        if (value.equals("武汉大学武汉") || value.equals("武汉大学(武汉)")) {
            return "武汉大学";
        }
        return value;
    }

    private String normalizeCity(String city) {
        String value = city == null ? "" : city.replaceAll("\\s+", "").trim();
        if (value.equals("北京") || value.equals("北京市")) return "北京市";
        if (value.equals("武汉") || value.equals("武汉市")) return "武汉市";
        return value;
    }

    private String defaultCityFor(String locationName) {
        String normalized = normalizeLocationName(locationName);
        if (normalized.equals("中国地质大学（北京）")) return "北京市";
        if (normalized.equals("中国地质大学（武汉）") || normalized.equals("武汉大学")) return "武汉市";
        return "";
    }

    private String normalizePoiText(String text) {
        return normalizeLocationName(text)
                .replaceAll("[()（）·,，。\\-—]", "")
                .toLowerCase(Locale.ROOT);
    }

    private boolean isNearPoiMatch(String query, String name) {
        if (query.length() < 4 || name.length() < 4) return false;
        int maxDistance = Math.max(1, Math.min(2, query.length() / 6));
        return levenshteinDistance(query, name) <= maxDistance;
    }

    private int levenshteinDistance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) previous[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            int[] current = new int[right.length() + 1];
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int substitution = previous[j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(Math.min(previous[j] + 1, current[j - 1] + 1), substitution);
            }
            previous = current;
        }
        return previous[right.length()];
    }

    private LocationReview reviewLocationName(String locationName, String city) {
        try {
            String prompt = promptResources.render("prompts/geocode-review.txt", Map.of(
                    "LOCATION", locationName,
                    "CITY", city == null ? "" : city
            ));
            String response = chatLanguageModel.generate(prompt).trim();
            String fence = String.valueOf((char) 96).repeat(3);
            if (response.startsWith(fence)) {
                int lineBreak = response.indexOf('\n');
                response = lineBreak >= 0 ? response.substring(lineBreak + 1).trim() : "";
            }
            if (response.endsWith(fence)) {
                response = response.substring(0, response.length() - fence.length()).trim();
            }
            JsonNode root = objectMapper.readTree(response);
            String reviewedName = root.path("locationName").asText("").trim();
            String reviewedCity = root.path("city").asText("").trim();
            if (reviewedName.isBlank()
                    || reviewedName.length() > 120
                    || reviewedName.equals(locationName)
                    && reviewedCity.equals(city == null ? "" : city)) {
                return null;
            }
            return new LocationReview(reviewedName, reviewedCity);
        } catch (Exception ignored) {
            return null;
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

        String normalizedQuery = normalizePoiText(locationName);
        List<JsonNode> exactMatches = new ArrayList<>();
        List<JsonNode> prefixMatches = new ArrayList<>();
        for (JsonNode poi : pois) {
            String name = normalizePoiText(poi.path("name").asText(""));
            if (normalizedQuery.equals(name)) {
                exactMatches.add(poi);
            } else if (!normalizedQuery.isBlank() && name.startsWith(normalizedQuery)) {
                // POI often names a campus or gate as "<institution><campus>".
                // It is a valid fallback only when all candidates belong to one city.
                prefixMatches.add(poi);
            } else if (!normalizedQuery.isBlank()
                    && (name.contains(normalizedQuery) || normalizedQuery.contains(name))) {
                // AMap may omit campus punctuation or append a gate/campus
                // suffix. Accept the best lexical match, subject to the same
                // city-ambiguity guard below.
                prefixMatches.add(poi);
            } else if (isNearPoiMatch(normalizedQuery, name)) {
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

    private record LocationReview(String locationName, String city) {}

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
