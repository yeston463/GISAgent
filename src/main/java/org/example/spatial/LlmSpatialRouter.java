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
import org.springframework.stereotype.Component;

import java.util.List;

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

    public LlmSpatialRouter(ChatLanguageModel llm, SpatialCapabilityCatalog catalog) {
        this.llm = llm;
        this.catalog = catalog;
    }

    public Route route(String request) {
        List<String> capabilityIds = catalog.capabilities().stream()
                .map(SpatialCapabilityCatalog.Capability::id)
                .toList();
        ToolSpecification routeTool = routeTool(capabilityIds);
        String instructions = """
                You are the route selector for a GIS agent. Always call select_spatial_route exactly once.
                Select only one kind and never invent catalog IDs or datasets.
                Route the requested analysis even when its input data is missing. Do not ask for rainfall, DEM, radius, or other data here; the data validator runs after routing.
                Select ground_dem when the user asks to obtain, sample, load, or query DEM/elevation from the current map or current AOI. This action needs no analysis radius or metric selection.
                A request for flood analysis must select the catalog ID flood_analysis. A request for skyline or sunlight analysis must select its matching catalog ID.
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
                if (route != null) {
                    return route;
                }
                failure = "invalid_tool_call";
                log.warn("LLM spatial routing attempt {} returned an invalid tool call", attempt);
            } catch (RuntimeException error) {
                failure = "tool_call_" + diagnostic(error);
                log.warn("LLM spatial routing attempt {} failed: {}", attempt, error.getMessage());
            }
            try {
                AiMessage compatibilityResponse = llm.generate(List.of(
                        SystemMessage.from(instructions + " Return exactly one JSON object with the route fields if tool calls are unavailable."),
                        UserMessage.from(request == null ? "" : request))).content();
                Route route = parseJsonResponse(compatibilityResponse);
                if (route != null) {
                    return route;
                }
                route = parseSingleCatalogMention(compatibilityResponse);
                if (route != null) {
                    return route;
                }
                failure = "invalid_json_response";
                log.warn("LLM spatial routing compatibility attempt {} returned invalid JSON", attempt);
            } catch (RuntimeException error) {
                failure = "json_fallback_" + diagnostic(error);
                log.warn("LLM spatial routing compatibility attempt {} failed: {}", attempt, error.getMessage());
            }
        }
        return new Route("unavailable", List.of(), List.of(), null, failure);
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

    private Route parseSingleCatalogMention(AiMessage response) {
        if (response == null || response.text() == null) return null;
        String answer = response.text();
        List<String> matches = catalog.capabilities().stream()
                .map(SpatialCapabilityCatalog.Capability::id)
                .filter(answer::contains)
                .toList();
        return matches.size() == 1 ? parseCatalogRoute(matches.get(0)) : null;
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
            Route route = parseCatalogRoute(catalogId(envelope));
            if (route != null) return route;
        }
        if (value.containsKey("route") && value.get("route") instanceof String routeId) {
            Route route = parseCatalogRoute(routeId);
            if (route != null) return route;
        }
        Route catalogRoute = parseCatalogRoute(catalogId(value));
        if (catalogRoute != null) {
            return catalogRoute;
        }
        String kind = value.getString("kind");
        if ("analysis".equals(kind)) {
            JSONArray raw = value.getJSONArray("capabilityIds");
            if (raw == null || raw.isEmpty()) return null;
            List<String> ids = raw.toJavaList(String.class);
            if (ids.stream().anyMatch(id -> catalog.find(id).isEmpty())) return null;
            return new Route(kind, ids, List.of(), null, null);
        }
        if ("discovery".equals(kind)) {
            JSONArray raw = value.getJSONArray("datasets");
            List<String> datasets = raw == null ? List.of() : raw.toJavaList(String.class);
            if (datasets.stream().anyMatch(dataset -> !DATASETS.contains(dataset))) return null;
            return new Route(kind, List.of(), datasets, null, null);
        }
        if ("import_osm".equals(kind)) {
            String dataset = value.getString("dataset");
            return DATASETS.contains(dataset) ? new Route(kind, List.of(), List.of(), dataset, null) : null;
        }
        return ("none".equals(kind) || "ground_dem".equals(kind))
                ? new Route(kind, List.of(), List.of(), null, null)
                : null;
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
            return new Route(catalogId, List.of(), List.of(), null, null);
        }
        if (catalogId != null && catalog.find(catalogId).isPresent()) {
            return new Route("analysis", List.of(catalogId), List.of(), null, null);
        }
        return null;
    }

    public record Route(String kind, List<String> capabilityIds, List<String> datasets, String dataset, String diagnostic) { }
}
