package org.example.agent;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.example.memory.PgVectorMemoryStore;
import org.example.tools.DynamicToolRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AgentLoopService {

    @Autowired
    private ChatLanguageModel chatLanguageModel;

    @Autowired
    private DynamicToolRegistry toolRegistry;

    @Autowired
    private PgVectorMemoryStore memoryStore;

    @Autowired
    private ValidationLayer validationLayer;

    @Autowired
    private SuggestionEngine suggestionEngine;

    @Autowired
    private ClarificationEngine clarificationEngine;

    private static final int MAX_ROUNDS = 8;

    private static final String SYSTEM_PROMPT = """
        You are a professional GIS analysis agent.
        Work in an observe-think-act loop. Output exactly one JSON object each turn.

        Available tools:
        - geocodeWithCity: params {"locationName":"place","city":"city or empty"}
        - aiGeocode: params {"locationName":"place"}
        - analyzeArea: preferred server-side pipeline. Params {"lon":number,"lat":number,"radius":meters}. It creates an AOI, fetches real OSM buildings, clips them, calculates metrics, and returns verified data.
        - analyzeCurrentView: analyze already uploaded AOI/buildings. If only AOI exists, it fetches buildings server-side first.
        - evaluatePlanningDemo: deterministic local competition case. It evaluates FAR, building density, maximum height and green rate, identifies problem buildings, creates an optimization scenario, and recalculates before/after metrics. No params.
        - fetchBuildingsFromOSM: params {"lon":number,"lat":number,"radius":meters}. Fetches real OSM building footprints.
        - bufferAnalysis: visual command only, params {"lon":number,"lat":number,"radius":meters}
        - getScreenBuildings: frontend fallback only when server-side fetching fails.
        - knowledgeSearch: params {"query":"text"}
        - synthesis: final report helper.

        Rules:
        1. For competition demo, planning evaluation, optimization scenario, before/after comparison, or 比赛案例/规划评价/优化方案 requests, call evaluatePlanningDemo first.
        2. For building/FAR/urban metrics around a named place, first get coordinates, then call analyzeArea.
        3. For "data uploaded", "redline", "AOI", or "current view" requests, call analyzeCurrentView first.
        4. Do not use getScreenBuildings unless analyzeArea/analyzeCurrentView failed or returned NoData.
        5. Never invent GIS metrics. Use only tool observations.
        6. If a tool returns Error/Fail/NoData, try another valid strategy or ask a concise question.
        7. When planning evaluation is returned, explain rule source, failed metrics, problem buildings and before/after comparison.
        8. When metrics are valid, respond with a short professional GIS report.

        JSON formats:
        Tool call:
        {"thought":"why this action is next","action":"tool_name","params":{"key":"value"}}

        Ask:
        {"thought":"what is missing","action":"ask","content":"question"}

        Final response:
        {"thought":"analysis complete","action":"respond","content":"answer","suggestions":["next step"]}
        """;

    public AgentResult execute(String userMessage, String userId, String memoryId) {
        if (isPlanningDemoRequest(userMessage)) {
            return executePlanningDemo(userMessage, userId);
        }

        if (shouldAskClarification(userMessage)) {
            String question = clarificationEngine.ask(userMessage);
            if (question != null) {
                return new AgentResult(null, null, question, true, null, null);
            }
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", SYSTEM_PROMPT));

        String memoryContext = buildMemoryContext(userId, userMessage);
        if (memoryContext != null) {
            messages.add(Map.of("role", "system", "content", "User memory:\n" + memoryContext));
        }
        messages.add(Map.of("role", "user", "content", userMessage));

        Map<String, Object> finalMetrics = null;
        String finalReply = null;
        List<String> finalSuggestions = null;
        List<Map<String, Object>> pendingCommands = new ArrayList<>();

        for (int round = 0; round < MAX_ROUNDS; round++) {
            JSONObject decision = askModelForDecision(messages);
            if (decision == null) {
                break;
            }

            String action = decision.getString("action");
            String thought = decision.getString("thought");
            if (action == null || action.isBlank()) {
                break;
            }

            if ("respond".equals(action)) {
                finalReply = decision.getString("content");
                finalSuggestions = parseSuggestions(decision.getJSONArray("suggestions"));
                break;
            }

            if ("ask".equals(action)) {
                return new AgentResult(null, null, decision.getString("content"), true, null, null);
            }

            JSONObject params = decision.getJSONObject("params");
            if (params == null) {
                params = new JSONObject();
            }

            Object rawResult = invokeTool(action, params);
            Map<String, Object> resultMap = asMap(rawResult);
            String observation = formatObservation(action, rawResult);

            if (resultMap != null) {
                collectCommands(resultMap, pendingCommands);

                if (isValidMetrics(resultMap)) {
                    finalMetrics = validationLayer.validateMetrics(resultMap);
                    String synthesis = synthesize(finalMetrics, userMessage);
                    if (synthesis != null && !synthesis.isBlank()) {
                        observation = synthesis;
                    }
                } else if (isFailed(resultMap)) {
                    Map<String, Object> fallback = tryFallback(action, params, resultMap, round);
                    if (fallback != null) {
                        collectCommands(fallback, pendingCommands);
                        observation = "Primary tool failed. Fallback result:\n" + formatObservation("fallback", fallback);
                        if (isValidMetrics(fallback)) {
                            finalMetrics = validationLayer.validateMetrics(fallback);
                            String synthesis = synthesize(finalMetrics, userMessage);
                            if (synthesis != null && !synthesis.isBlank()) {
                                observation = synthesis;
                            }
                        }
                    }
                }
            }

            messages.add(Map.of("role", "assistant", "content",
                    safe(thought, "I selected " + action)));
            messages.add(Map.of("role", "user", "content",
                    "Tool " + action + " returned:\n" + observation));
        }

        if (finalReply == null) {
            finalReply = finalMetrics != null
                    ? buildMetricReply(finalMetrics)
                    : "I could not obtain enough valid GIS data for this request. Please provide a more specific location or draw/upload an AOI.";
        }

        if ((finalSuggestions == null || finalSuggestions.isEmpty())) {
            finalSuggestions = suggestionEngine.generateSuggestions(finalMetrics);
        }

        saveAnalysis(userId, userMessage, finalMetrics);
        memoryStore.cleanupExpired();

        return new AgentResult(finalReply, finalSuggestions, null, false,
                finalMetrics, dedupeCommands(pendingCommands));
    }

    private boolean isPlanningDemoRequest(String userMessage) {
        if (userMessage == null) {
            return false;
        }
        String lower = userMessage.toLowerCase();
        return lower.contains("competition demo")
                || lower.contains("planning evaluation")
                || lower.contains("optimization scenario")
                || userMessage.contains("比赛案例")
                || userMessage.contains("规划评价")
                || userMessage.contains("优化方案")
                || userMessage.contains("方案对比");
    }

    private AgentResult executePlanningDemo(String userMessage, String userId) {
        Map<String, Object> result = asMap(invokeTool("evaluatePlanningDemo", new JSONObject()));
        if (result == null || isFailed(result)) {
            String message = result == null
                    ? "比赛案例分析未返回有效结果。"
                    : String.valueOf(result.getOrDefault("message", "比赛案例分析失败。"));
            return new AgentResult(message, List.of("检查 Python GIS 服务是否已启动"), null, false, null, List.of());
        }

        List<Map<String, Object>> commands = new ArrayList<>();
        collectCommands(result, commands);
        Map<String, Object> metrics = validationLayer.validateMetrics(result);
        String reply = synthesizePlanningDemo(result, userMessage);
        if (reply == null || reply.isBlank()) {
            reply = "比赛案例已完成规划评价与优化方案复算，请查看地图中的问题建筑、优化建筑、绿地方案和指标对比面板。";
        }
        List<String> suggestions = List.of(
                "切换现状与优化图层核对空间变化",
                "将演示规则替换为案例地区现行规划规则",
                "导出优化前后指标与数据质量说明"
        );
        saveAnalysis(userId, userMessage, metrics);
        return new AgentResult(reply, suggestions, null, false, metrics, dedupeCommands(commands));
    }

    private String synthesizePlanningDemo(Map<String, Object> result, String userMessage) {
        try {
            String prompt = """
                    你是城市规划 GIS 分析师。根据给定的确定性比赛案例结果，生成简洁中文报告。
                    必须说明：四项指标的现状和优化后变化、未达标项、问题建筑、采用的优化动作，
                    并明确演示规则不是法定审批依据。只能使用 JSON 中已有信息，禁止编造数值。

                    用户请求：
                    %s

                    规划评价 JSON：
                    %s
                    """.formatted(userMessage, JSON.toJSONString(result));
            return chatLanguageModel.generate(prompt);
        } catch (Exception e) {
            return null;
        }
    }

    private boolean shouldAskClarification(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return true;
        }
        String lower = userMessage.toLowerCase();
        if (lower.contains("data uploaded")
                || lower.contains("uploaded")
                || lower.contains("aoi")
                || lower.contains("redline")
                || userMessage.contains("数据已")
                || userMessage.contains("红线")
                || userMessage.contains("当前")
                || userMessage.contains("范围")) {
            return false;
        }
        return clarificationEngine.needsClarification(userMessage);
    }

    private JSONObject askModelForDecision(List<Map<String, Object>> messages) {
        try {
            String llmResponse = chatLanguageModel.generate(buildPrompt(messages));
            return JSON.parseObject(extractJson(llmResponse));
        } catch (Exception e) {
            return null;
        }
    }

    private Object invokeTool(String action, JSONObject params) {
        try {
            return toolRegistry.invoke(action, params);
        } catch (Exception e) {
            return Map.of("status", "Error", "message", e.getMessage(), "tool", action);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> tryFallback(String action, JSONObject params, Map<String, Object> failure, int round) {
        if ("geocodeWithCity".equals(action)) {
            JSONObject fallbackParams = new JSONObject();
            if (params.containsKey("locationName")) {
                fallbackParams.put("locationName", params.get("locationName"));
            }
            return asMap(invokeTool("aiGeocode", fallbackParams));
        }

        if ("analyzeArea".equals(action)) {
            JSONObject wider = new JSONObject(params);
            double radius = getDouble(wider, "radius", 500);
            wider.put("radius", radius + 500 * (round + 1));
            Map<String, Object> widerResult = asMap(invokeTool("analyzeArea", wider));
            if (widerResult != null && !isFailed(widerResult)) {
                return widerResult;
            }
            return asMap(invokeTool("getScreenBuildings", new JSONObject()));
        }

        if ("analyzeCurrentView".equals(action) || "fetchBuildingsFromOSM".equals(action)) {
            return asMap(invokeTool("getScreenBuildings", new JSONObject()));
        }

        if ("bufferAnalysis".equals(action) && params.containsKey("lon") && params.containsKey("lat")) {
            JSONObject analyze = new JSONObject();
            analyze.put("lon", params.get("lon"));
            analyze.put("lat", params.get("lat"));
            analyze.put("radius", getDouble(params, "radius", 500));
            return asMap(invokeTool("analyzeArea", analyze));
        }

        return null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object result) {
        if (result instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        if (result instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.startsWith("{")) {
                try {
                    return new LinkedHashMap<>(JSON.parseObject(trimmed).getInnerMap());
                } catch (Exception ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private boolean isFailed(Map<String, Object> result) {
        String status = String.valueOf(result.getOrDefault("status", ""));
        return "Error".equalsIgnoreCase(status)
                || "Fail".equalsIgnoreCase(status)
                || "NoData".equalsIgnoreCase(status)
                || status.toLowerCase().contains("error");
    }

    private boolean isSuccess(Map<String, Object> result) {
        String status = String.valueOf(result.getOrDefault("status", ""));
        return status.isBlank()
                || "Success".equalsIgnoreCase(status)
                || "success".equalsIgnoreCase(status)
                || "ok".equalsIgnoreCase(status);
    }

    private boolean isValidMetrics(Map<String, Object> result) {
        return isSuccess(result)
                && result.containsKey("far")
                && getInt(result, "building_count", 0) > 0
                && getDouble(result, "site_area", getDouble(result, "site_area_sqm", 0)) > 0;
    }

    private void collectCommands(Map<String, Object> result, List<Map<String, Object>> pendingCommands) {
        if (!isSuccess(result)) {
            return;
        }

        if (result.containsKey("longitude") && result.containsKey("latitude")) {
            double lon = getDouble(result, "longitude", 0);
            double lat = getDouble(result, "latitude", 0);
            if (isValidCoordinate(lon, lat)) {
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("longitude", lon);
                params.put("latitude", lat);
                params.put("zoom", 17);
                pendingCommands.add(Map.of("action", "flyTo", "params", params));
            }
        }

        Object commandsObj = result.get("commands");
        if (commandsObj instanceof List<?> commands) {
            for (Object commandObj : commands) {
                if (commandObj instanceof Map<?, ?> command && command.get("action") != null) {
                    Map<String, Object> normalized = new LinkedHashMap<>();
                    normalized.put("action", command.get("action"));
                    if (command.get("params") != null) {
                        normalized.put("params", command.get("params"));
                    }
                    pendingCommands.add(normalized);
                }
            }
        }

        if (result.containsKey("action")) {
            Object actionObj = result.get("action");
            Object paramsObj = result.get("params");
            if ("addBuffer".equals(actionObj) && paramsObj instanceof Map<?, ?> params) {
                double lon = getDouble(params, "longitude", 0);
                double lat = getDouble(params, "latitude", 0);
                if (!isValidCoordinate(lon, lat)) {
                    return;
                }
            }

            Map<String, Object> cmd = new LinkedHashMap<>();
            cmd.put("action", actionObj);
            if (paramsObj != null) {
                cmd.put("params", paramsObj);
            }
            pendingCommands.add(cmd);
        }
    }

    private boolean isValidCoordinate(double lon, double lat) {
        return !(Math.abs(lon) < 0.1 && Math.abs(lat) < 0.1)
                && lon >= -180 && lon <= 180
                && lat >= -90 && lat <= 90;
    }

    private String synthesize(Map<String, Object> metrics, String userMessage) {
        try {
            String prompt = """
                    You are a professional GIS analyst. Write a concise user-facing answer in Chinese.
                    Use only the metrics below. Do not invent any numbers.
                    Mention when FAR is estimated from predicted floors or when confidence is low.

                    User request:
                    %s

                    Metrics JSON:
                    %s
                    """.formatted(userMessage, JSON.toJSONString(metrics));
            return chatLanguageModel.generate(prompt);
        } catch (Exception e) {
            return null;
        }
    }

    private String formatObservation(String toolName, Object result) {
        Map<String, Object> map = asMap(result);
        if (map == null) {
            return result == null ? "No result" : result.toString();
        }

        if (isFailed(map)) {
            return "status=" + map.getOrDefault("status", "Error")
                    + ", stage=" + map.getOrDefault("stage", toolName)
                    + ", message=" + map.getOrDefault("message", "");
        }

        if (map.containsKey("far")) {
            int count = getInt(map, "building_count", 0);
            double far = getDouble(map, "far", 0);
            double siteArea = getDouble(map, "site_area", getDouble(map, "site_area_sqm", 0));
            double buildingArea = getDouble(map, "building_area", getDouble(map, "total_const_area_sqm", 0));
            return "valid metrics: FAR=" + far
                    + ", buildings=" + count
                    + ", site_area_sqm=" + Math.round(siteArea)
                    + ", building_area_sqm=" + Math.round(buildingArea)
                    + ", source=" + map.getOrDefault("data_source", map.getOrDefault("source", "unknown"));
        }

        if (map.containsKey("building_count")) {
            return "building fetch result: status=" + map.getOrDefault("status", "")
                    + ", count=" + map.getOrDefault("building_count", 0)
                    + ", source=" + map.getOrDefault("source", "unknown");
        }

        if (map.containsKey("longitude") && map.containsKey("latitude")) {
            return "coordinates: lon=" + map.get("longitude") + ", lat=" + map.get("latitude")
                    + ", source=" + map.getOrDefault("source", "unknown");
        }

        return JSON.toJSONString(map);
    }

    private String buildMetricReply(Map<String, Object> metrics) {
        double far = getDouble(metrics, "far", 0);
        int count = getInt(metrics, "building_count", 0);
        double siteArea = getDouble(metrics, "site_area", getDouble(metrics, "site_area_sqm", 0));
        double buildingArea = getDouble(metrics, "building_area", getDouble(metrics, "total_const_area_sqm", 0));
        return String.format(
                "Analysis complete. I fetched valid building footprints and calculated FAR %.2f. The AOI area is %.2f ha, estimated total floor area is %.2f ha, and %d buildings were matched.",
                far, siteArea / 10000.0, buildingArea / 10000.0, count
        );
    }

    private void saveAnalysis(String userId, String userMessage, Map<String, Object> finalMetrics) {
        if (finalMetrics == null) {
            return;
        }
        try {
            String loc = String.valueOf(finalMetrics.getOrDefault("location", "server-aoi"));
            memoryStore.saveAnalysis(userId, loc, "urban_metrics", userMessage, finalMetrics);
        } catch (Exception ignored) {
        }
    }

    private List<Map<String, Object>> dedupeCommands(List<Map<String, Object>> commands) {
        List<Map<String, Object>> deduped = new ArrayList<>();
        Set<String> seenActions = new HashSet<>();
        for (int i = commands.size() - 1; i >= 0; i--) {
            String action = String.valueOf(commands.get(i).get("action"));
            if (seenActions.add(action)) {
                deduped.add(0, commands.get(i));
            }
        }
        return deduped;
    }

    private String buildPrompt(List<Map<String, Object>> messages) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> msg : messages) {
            sb.append("<").append(msg.get("role")).append(">\n")
                    .append(msg.get("content")).append("\n")
                    .append("</").append(msg.get("role")).append(">\n\n");
        }
        sb.append("Decide the next action. Return JSON only.");
        return sb.toString();
    }

    private String extractJson(String text) {
        if (text == null) {
            return "{}";
        }
        text = text.trim();
        if (text.startsWith("```")) {
            int start = text.indexOf('\n');
            if (start > 0) {
                text = text.substring(start).trim();
            }
        }
        if (text.endsWith("```")) {
            text = text.substring(0, text.length() - 3).trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    private String buildMemoryContext(String userId, String userMessage) {
        try {
            StringBuilder ctx = new StringBuilder();

            List<Map<String, Object>> recent = memoryStore.getRecentAnalyses(userId, 3);
            if (!recent.isEmpty()) {
                ctx.append("Recent analyses:\n");
                for (Map<String, Object> row : recent) {
                    ctx.append("- ")
                            .append(row.get("created_at"))
                            .append(" location=").append(row.get("location"))
                            .append(" type=").append(row.get("analysis_type"))
                            .append("\n");
                }
            }

            List<Map<String, Object>> similar = memoryStore.searchSimilarAnalyses(userId, userMessage, 2);
            if (!similar.isEmpty()) {
                ctx.append("Similar analyses:\n");
                for (Map<String, Object> row : similar) {
                    ctx.append("- ").append(row.get("location"))
                            .append(" similarity=").append(row.get("similarity"))
                            .append("\n");
                }
            }

            Map<String, String> prefs = memoryStore.getAllPreferences(userId);
            if (!prefs.isEmpty()) {
                ctx.append("Preferences:\n");
                prefs.forEach((key, value) -> ctx.append("- ").append(key).append(": ").append(value).append("\n"));
            }

            return ctx.length() > 0 ? ctx.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> parseSuggestions(JSONArray arr) {
        if (arr == null || arr.isEmpty()) {
            return null;
        }
        List<String> list = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            list.add(arr.getString(i));
        }
        return list;
    }

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private double getDouble(Map<?, ?> map, String key, double fallback) {
        Object value = map.get(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private int getInt(Map<String, Object> map, String key, int fallback) {
        Object value = map.get(key);
        if (value == null) {
            return fallback;
        }
        try {
            return (int) Math.round(Double.parseDouble(value.toString()));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public record AgentResult(
            String reply,
            List<String> suggestions,
            String clarification,
            boolean needsClarification,
            Map<String, Object> metrics,
            List<Map<String, Object>> commands
    ) {}
}

