package org.example.spatial;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.request.json.JsonArraySchema;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.output.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lets the model choose an intent through a forced function call. The returned
 * arguments are still validated against the current capability catalog before
 * anything can be executed.
 */
@Component
public class LlmSpatialRouter {
    private static final Logger log = LoggerFactory.getLogger(LlmSpatialRouter.class);
    private static final String ROUTE_TOOL = "select_spatial_route";
    private static final List<String> ROUTE_KINDS = List.of("analysis", "discovery", "import_osm", "ground_dem", "none");
    private static final List<String> DATASETS = List.of("dem", "buildings", "roads", "waterways");

    private final ChatLanguageModel llm;
    private final SpatialCapabilityCatalog catalog;
    private final RestTemplate restTemplate;
    private final String deepSeekApiKey;
    private final String deepSeekBaseUrl;
    private final String deepSeekModel;
    private final SpatialRoutingTelemetry telemetry;

    public LlmSpatialRouter(
            @Qualifier("spatialRouterModel") ChatLanguageModel llm,
            SpatialCapabilityCatalog catalog,
            RestTemplate restTemplate,
            SpatialRoutingTelemetry telemetry,
            @Value("${DEEPSEEK_API_KEY:}") String deepSeekApiKey,
            @Value("${ai.deepseek.base-url:https://api.deepseek.com}") String deepSeekBaseUrl,
            @Value("${ai.deepseek.router-model-name:deepseek-v4-flash}") String deepSeekModel) {
        this.llm = llm;
        this.catalog = catalog;
        this.restTemplate = restTemplate;
        this.telemetry = telemetry;
        this.deepSeekApiKey = deepSeekApiKey;
        this.deepSeekBaseUrl = deepSeekBaseUrl;
        this.deepSeekModel = deepSeekModel;
    }

    public Route route(String request) {
        long startedAt = System.nanoTime();
        Route strictRoute = strictDeepSeekRoute(request);
        long strictElapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        if (strictRoute != null && !"none".equals(strictRoute.kind())) {
            telemetry.record("deepseek", "success", strictElapsedMs, null);
            return strictRoute;
        }
        if (deepSeekApiKey != null && !deepSeekApiKey.isBlank()) {
            telemetry.record("deepseek", "fallback", strictElapsedMs, "strict_tool_call_unavailable");
        }
        Route fallback = fallbackRoute(request);
        long totalElapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
        telemetry.record("unavailable".equals(fallback.kind()) ? "unavailable" : "qwen_fallback",
                "unavailable".equals(fallback.kind()) ? "failed" : "success", totalElapsedMs, fallback.diagnostic());
        return fallback;
    }

    private Route fallbackRoute(String request) {
        List<String> capabilityIds = catalog.capabilities().stream()
                .map(SpatialCapabilityCatalog.Capability::id)
                .toList();
        ToolSpecification routeTool = routeTool(capabilityIds);
        String instructions = """
                You are the route selector for a GIS agent. Always call select_spatial_route exactly once.
                Select only one kind and never invent catalog IDs or datasets.
                Route the requested analysis even when its input data is missing. Do not ask for rainfall, DEM, radius, or other data here; the data validator runs after routing.
                Select ground_dem when the user asks to obtain, sample, load, or query DEM/elevation from the current map or current AOI. This action needs no analysis radius or metric selection.
                A request for flood analysis, 洪水分析, 进行洪水分析, 内涝分析, or 淹没分析 must select the catalog ID flood_analysis. A request for skyline or sunlight analysis must select its matching catalog ID.
                Requests to generate, publish, download, or view a CityEngine, GeoScene, or SLPK 3D deliverable are not spatial analysis routes. Select none so the dedicated 3D pipeline can handle them.
                Select discovery only to search external data candidates. Select import_osm only when the user explicitly confirms importing an OSM dataset. Select none for requests unrelated to GIS routing.
                Catalog capability IDs: %s
                """.formatted(String.join(", ", capabilityIds));

        String failure = "invalid_model_response";
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                Response<AiMessage> response = llm.generate(
                        List.of(SystemMessage.from(instructions), UserMessage.from(request == null ? "" : request)),
                        List.of(routeTool));
                Route route = parseToolCall(response.content());
                if (route != null && !"none".equals(route.kind())) {
                    return route;
                }
                failure = route == null ? "invalid_tool_call" : "tool_call_none";
                log.warn("LLM spatial routing attempt {} returned an invalid tool call", attempt);
            } catch (RuntimeException error) {
                failure = "tool_call_" + diagnostic(error);
                log.warn("LLM spatial routing attempt {} failed: {}", attempt, error.getMessage());
            }
            try {
                AiMessage compatibilityResponse = llm.generate(List.of(
                        SystemMessage.from(instructions + """
                                Tool calls are unavailable. Reply with JSON only, with no prose or Markdown.
                                Use one of these shapes:
                                {"kind":"analysis","capabilityIds":["one catalog ID"],"location":""}
                                {"kind":"ground_dem","location":"exact user-named location or empty"}
                                {"kind":"discovery","datasets":["dem"]}
                                {"kind":"import_osm","dataset":"buildings"}
                                {"kind":"none"}
                                This is a structured-output retry. Never describe a route in natural language.
                                """),
                        UserMessage.from(request == null ? "" : request))).content();
                Route route = parseJsonResponse(compatibilityResponse);
                if (route != null && !"none".equals(route.kind())) {
                    return route;
                }
                failure = "invalid_json_response";
                log.warn("LLM spatial routing compatibility attempt {} returned invalid JSON", attempt);
            } catch (RuntimeException error) {
                failure = "json_fallback_" + diagnostic(error);
                log.warn("LLM spatial routing compatibility attempt {} failed: {}", attempt, error.getMessage());
            }
        }
        return new Route("unavailable", List.of(), List.of(), null, failure, null);
    }

    private Route strictDeepSeekRoute(String request) {
        if (deepSeekApiKey == null || deepSeekApiKey.isBlank()) return null;
        try {
            List<String> catalogIds = new ArrayList<>(catalog.capabilities().stream()
                    .map(SpatialCapabilityCatalog.Capability::id).toList());
            catalogIds.add("ground_dem");
            catalogIds.add("none");
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("kind", Map.of("type", "string", "enum", ROUTE_KINDS));
            properties.put("catalog_id", Map.of("type", "string", "enum", catalogIds));
            properties.put("dataset", Map.of("type", "string", "enum", List.of("dem", "buildings", "roads", "waterways", "none")));
            properties.put("location", Map.of("type", "string", "description", "Exact place named by the user for a ground DEM request, or an empty string when no place is named."));
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("type", "object");
            parameters.put("properties", properties);
            parameters.put("required", List.of("kind", "catalog_id", "dataset", "location"));
            parameters.put("additionalProperties", false);
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", ROUTE_TOOL);
            function.put("strict", true);
            function.put("description", "Select one validated GIS route. Never explain or ask questions.");
            function.put("parameters", parameters);
            Map<String, Object> tool = Map.of("type", "function", "function", function);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", deepSeekModel);
            body.put("messages", List.of(
                    Map.of("role", "system", "content", "Select exactly one GIS route. 洪水分析、进行洪水分析、内涝分析必须选择 kind=analysis and catalog_id=flood_analysis. 获取DEM必须选择 kind=ground_dem and catalog_id=ground_dem. Missing data never changes the selected route."),
                    Map.of("role", "user", "content", request == null ? "" : request)));
            body.put("thinking", Map.of("type", "disabled"));
            body.put("tools", List.of(tool));
            body.put("tool_choice", Map.of("type", "function", "function", Map.of("name", ROUTE_TOOL)));
            body.put("stream", false);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(deepSeekApiKey);
            String raw = restTemplate.postForObject(deepSeekBaseUrl.replaceAll("/+$", "") + "/beta/chat/completions",
                    new HttpEntity<>(JSON.toJSONString(body), headers), String.class);
            JSONObject response = JSON.parseObject(raw);
            JSONArray choices = response.getJSONArray("choices");
            if (choices == null || choices.isEmpty()) return null;
            JSONObject message = choices.getJSONObject(0).getJSONObject("message");
            JSONArray calls = message == null ? null : message.getJSONArray("tool_calls");
            if (calls == null || calls.isEmpty()) return null;
            JSONObject functionCall = calls.getJSONObject(0).getJSONObject("function");
            if (functionCall == null || !ROUTE_TOOL.equals(functionCall.getString("name"))) return null;
            return parse(JSON.parseObject(functionCall.getString("arguments")));
        } catch (RuntimeException error) {
            log.warn("DeepSeek strict spatial route failed: {}", error.getMessage());
            return null;
        }
    }

    private ToolSpecification routeTool(List<String> capabilityIds) {
        JsonEnumSchema capabilityId = JsonEnumSchema.builder().enumValues(capabilityIds).build();
        JsonObjectSchema parameters = JsonObjectSchema.builder()
                .addEnumProperty("kind", ROUTE_KINDS, "The routing kind to execute")
                .addProperty("capabilityIds", JsonArraySchema.builder().items(capabilityId).build())
                .addProperty("datasets", JsonArraySchema.builder()
                        .items(JsonEnumSchema.builder().enumValues(DATASETS).build())
                        .build())
                .addEnumProperty("dataset", DATASETS, "The OSM dataset to import")
                .addStringProperty("location", "The exact user-named place for a ground DEM request, or an empty string")
                .required("kind")
                .additionalProperties(false)
                .build();
        return ToolSpecification.builder()
                .name(ROUTE_TOOL)
                .description("Select the single validated GIS route for the user's request.")
                .parameters(parameters)
                .build();
    }

    private Route parseToolCall(AiMessage response) {
        if (response == null || !response.hasToolExecutionRequests()) {
            return null;
        }
        ToolExecutionRequest call = response.toolExecutionRequests().get(0);
        if (!ROUTE_TOOL.equals(call.name())) {
            return null;
        }
        try {
            return parse(JSON.parseObject(call.arguments()));
        } catch (RuntimeException error) {
            log.warn("LLM spatial routing returned invalid function arguments: {}", error.getMessage());
            return null;
        }
    }

    private Route parseJsonResponse(AiMessage response) {
        if (response == null || response.text() == null) {
            return null;
        }
        try {
            // Only accept a complete JSON value. No substring extraction means
            // natural-language model output can never become an executable route.
            String text = response.text().trim();
            if (text.startsWith("```")) {
                int firstLine = text.indexOf('\n');
                if (firstLine < 0 || !text.endsWith("```")) return null;
                text = text.substring(firstLine + 1, text.length() - 3).trim();
            }
            return parse(JSON.parseObject(text));
        } catch (RuntimeException error) {
            return null;
        }
    }

    private String diagnostic(RuntimeException error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
        String compact = message.replace('\r', ' ').replace('\n', ' ').trim();
        return error.getClass().getSimpleName() + ":" + compact.substring(0, Math.min(compact.length(), 180));
    }

    private Route parse(JSONObject value) {
        if (value == null) return null;
        for (String key : List.of("route", "analysis", "selection", "plan")) {
            JSONObject envelope = value.getJSONObject(key);
            if (envelope == null) continue;
            Route route = withLocation(parseCatalogRoute(catalogId(envelope)), location(envelope));
            if (route != null) return route;
        }
        if (value.containsKey("route") && value.get("route") instanceof String routeId) {
            Route route = withLocation(parseCatalogRoute(routeId), location(value));
            if (route != null) return route;
        }
        Route catalogRoute = withLocation(parseCatalogRoute(catalogId(value)), location(value));
        if (catalogRoute != null) {
            return catalogRoute;
        }
        String kind = value.getString("kind");
        if ("analysis".equals(kind)) {
            JSONArray raw = value.getJSONArray("capabilityIds");
            if (raw == null || raw.isEmpty()) return null;
            List<String> ids = raw.toJavaList(String.class);
            if (ids.stream().anyMatch(id -> catalog.find(id).isEmpty())) return null;
            return new Route(kind, ids, List.of(), null, null, location(value));
        }
        if ("discovery".equals(kind)) {
            JSONArray raw = value.getJSONArray("datasets");
            List<String> datasets = raw == null ? List.of() : raw.toJavaList(String.class);
            if (datasets.stream().anyMatch(dataset -> !DATASETS.contains(dataset))) return null;
            return new Route(kind, List.of(), datasets, null, null, location(value));
        }
        if ("import_osm".equals(kind)) {
            String dataset = value.getString("dataset");
            return DATASETS.contains(dataset) ? new Route(kind, List.of(), List.of(), dataset, null, location(value)) : null;
        }
        return ("none".equals(kind) || "ground_dem".equals(kind))
                ? new Route(kind, List.of(), List.of(), null, null, location(value))
                : null;
    }

    private Route withLocation(Route route, String location) {
        if (route == null) return null;
        return new Route(route.kind(), route.capabilityIds(), route.datasets(), route.dataset(), route.diagnostic(), location);
    }

    private String location(JSONObject value) {
        String location = value.getString("location");
        return location == null || location.isBlank() ? null : location.trim();
    }

    private String catalogId(JSONObject value) {
        for (String key : List.of("catalog_id", "capability_id", "capabilityId", "analysis_type", "analysisType", "selected_capability", "intent", "action", "tool", "tool_name", "capability", "id")) {
            String id = value.getString(key);
            if (id != null && !id.isBlank()) return id;
        }
        return null;
    }

    private Route parseCatalogRoute(String catalogId) {
        if ("ground_dem".equals(catalogId) || "none".equals(catalogId)) {
            return new Route(catalogId, List.of(), List.of(), null, null, null);
        }
        if (catalogId != null && catalog.find(catalogId).isPresent()) {
            return new Route("analysis", List.of(catalogId), List.of(), null, null, null);
        }
        return null;
    }

    public record Route(String kind, List<String> capabilityIds, List<String> datasets, String dataset, String diagnostic, String location) { }
}
