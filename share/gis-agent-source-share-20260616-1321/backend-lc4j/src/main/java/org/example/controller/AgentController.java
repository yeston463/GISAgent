package org.example.controller;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.example.agent.AgentLoopService;
import org.example.agent.DynamicCodeGenerator;
import org.example.memory.PgVectorMemoryStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    @Autowired
    private DynamicCodeGenerator codeGenerator;

    @Autowired
    private AgentLoopService agentLoopService;

    @Autowired
    private PgVectorMemoryStore memoryStore;

    @CrossOrigin(origins = "http://localhost:5173")
    @PostMapping("/chat/agentic")
    public ResponseEntity<Map<String, Object>> agenticChat(@RequestBody ChatRequest request) {
        String message = request.message();
        String userId = request.memoryId() != null ? request.memoryId() : "default";
        String memoryId = request.memoryId() != null ? request.memoryId() : UUID.randomUUID().toString();

        AgentLoopService.AgentResult result = agentLoopService.execute(message, userId, memoryId);

        Map<String, Object> response = new LinkedHashMap<>();
        if (result.needsClarification()) {
            response.put("reply", result.clarification());
            response.put("needClarification", true);
            response.put("commands", new JSONArray());
        } else {
            JSONArray commands = new JSONArray();
            appendAgentCommands(commands, result.commands());

            if (result.metrics() != null && !hasCommand(commands, "comparePlanningScenarios")) {
                appendMetricCommands(commands, extractCommandsFromMetrics(new JSONObject(result.metrics())));
            }

            response.put("reply", result.reply());
            response.put("commands", commands);
            response.put("suggestions", result.suggestions());
            response.put("needClarification", false);
        }

        response.put("memoryId", memoryId);
        return ResponseEntity.ok(response);
    }

    @CrossOrigin(origins = "http://localhost:5173")
    @PostMapping("/preference")
    public Map<String, Object> savePreference(@RequestBody PreferenceRequest request) {
        String userId = request.userId != null ? request.userId : "default";
        memoryStore.savePreference(userId, request.key, request.value);
        return Map.of("status", "ok");
    }

    @CrossOrigin(origins = "http://localhost:5173")
    @GetMapping("/history")
    public Map<String, Object> getHistory(@RequestParam(defaultValue = "default") String userId) {
        List<Map<String, Object>> history = memoryStore.getRecentAnalyses(userId, 20);
        return Map.of("history", history);
    }

    @CrossOrigin(origins = "http://localhost:5173")
    @PostMapping("/execute")
    public Map<String, Object> executeDynamic(@RequestBody ExecuteRequest request) {
        DynamicCodeGenerator.CodeExecutionResult result =
                codeGenerator.generateAndExecute(request.requirement(), request.context());

        return Map.of(
                "status", result.status(),
                "code", result.generatedCode(),
                "result", result.result() != null ? result.result() : "no result"
        );
    }

    public record ChatRequest(String message, String memoryId) {}

    public record ExecuteRequest(String requirement, JSONObject context) {}

    public record PreferenceRequest(String userId, String key, String value) {}

    private void appendAgentCommands(JSONArray commands, List<Map<String, Object>> agentCommands) {
        if (agentCommands == null) {
            return;
        }
        for (Map<String, Object> cmd : agentCommands) {
            JSONObject jsonCmd = new JSONObject(cmd);
            if (isInvalidFlyTo(jsonCmd)) {
                continue;
            }
            commands.add(jsonCmd);
        }
    }

    private void appendMetricCommands(JSONArray commands, JSONArray metricCommands) {
        for (int i = 0; i < metricCommands.size(); i++) {
            JSONObject metricCommand = metricCommands.getJSONObject(i);
            if (!hasCommand(commands, metricCommand.getString("action"))) {
                commands.add(metricCommand);
            }
        }
    }

    private boolean hasCommand(JSONArray commands, String action) {
        for (int i = 0; i < commands.size(); i++) {
            if (action.equals(commands.getJSONObject(i).getString("action"))) {
                return true;
            }
        }
        return false;
    }

    private boolean isInvalidFlyTo(JSONObject command) {
        if (!"flyTo".equals(command.getString("action"))) {
            return false;
        }
        JSONObject params = command.getJSONObject("params");
        if (params == null) {
            return true;
        }
        double lon = params.getDoubleValue("longitude");
        double lat = params.getDoubleValue("latitude");
        return Math.abs(lon) < 0.1 && Math.abs(lat) < 0.1;
    }

    private JSONArray extractCommandsFromMetrics(JSONObject metrics) {
        JSONArray commands = new JSONArray();
        if (!metrics.containsKey("far") || !metrics.containsKey("building_count")) {
            return commands;
        }

        double siteArea = toDouble(metrics.getOrDefault("site_area", metrics.get("site_area_sqm")));
        double buildingArea = toDouble(metrics.getOrDefault("building_area", metrics.get("total_const_area_sqm")));
        int buildingCount = Integer.parseInt(metrics.get("building_count").toString());
        double far = toDouble(metrics.get("far"));

        JSONObject analysis = new JSONObject();
        analysis.put("action", "showAnalysisResult");
        JSONObject analysisParams = new JSONObject();
        analysisParams.put("far", far);
        analysisParams.put("site_area", siteArea);
        analysisParams.put("building_area", buildingArea);
        analysisParams.put("building_count", buildingCount);
        copyIfPresent(metrics, analysisParams, "building_density");
        copyIfPresent(metrics, analysisParams, "footprint_area_sqm");
        copyIfPresent(metrics, analysisParams, "green_rate");
        copyIfPresent(metrics, analysisParams, "lower_bound_far");
        copyIfPresent(metrics, analysisParams, "height_stats");
        copyIfPresent(metrics, analysisParams, "floor_stats");
        copyIfPresent(metrics, analysisParams, "building_types");
        copyIfPresent(metrics, analysisParams, "roof_types");
        copyIfPresent(metrics, analysisParams, "materials");
        copyIfPresent(metrics, analysisParams, "floor_confidence");
        analysis.put("params", analysisParams);
        commands.add(analysis);

        JSONObject popup = new JSONObject();
        popup.put("action", "showPopup");
        JSONObject popupParams = new JSONObject();
        popupParams.put("title", "Building Metrics");
        popupParams.put("content", buildPopupContent(metrics, far, buildingArea, siteArea, buildingCount));
        popup.put("params", popupParams);
        commands.add(popup);

        return commands;
    }

    private String buildPopupContent(JSONObject metrics, double far, double buildingArea, double siteArea, int buildingCount) {
        StringBuilder content = new StringBuilder(String.format(
                "FAR <b>%.2f</b><br>Total floor area <b>%.0f</b> ha<br>Site area <b>%.0f</b> ha<br>Buildings <b>%d</b>",
                far, buildingArea / 10000.0, siteArea / 10000.0, buildingCount));
        if (metrics.containsKey("lower_bound_far")) {
            content.append(String.format("<br>Observed lower bound FAR <b>%.2f</b>", toDouble(metrics.get("lower_bound_far"))));
        }
        if (metrics.containsKey("height_stats")) {
            JSONObject heightStats = metrics.getJSONObject("height_stats");
            content.append(String.format("<br>Average height <b>%s</b> m", heightStats.get("avg")));
        }
        if (metrics.containsKey("floor_stats")) {
            JSONObject floorStats = metrics.getJSONObject("floor_stats");
            content.append(String.format("<br>Average floors <b>%s</b>", floorStats.get("avg")));
        }
        if (metrics.containsKey("floor_confidence")) {
            content.append("<br>Floor confidence <b>").append(metrics.get("floor_confidence")).append("</b>");
        }
        if (metrics.containsKey("building_types")) {
            content.append("<br><br><b>Building types:</b><br>").append(formatMap(metrics.get("building_types")));
        }
        return content.toString();
    }

    private void copyIfPresent(JSONObject source, JSONObject target, String key) {
        if (source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }

    private double toDouble(Object value) {
        if (value == null) {
            return 0;
        }
        return Double.parseDouble(value.toString());
    }

    @SuppressWarnings("unchecked")
    private String formatMap(Object obj) {
        if (obj instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) obj;
            StringBuilder sb = new StringBuilder();
            map.forEach((key, value) -> sb.append("&nbsp;&nbsp;").append(key).append(": ").append(value).append("<br>"));
            return sb.toString();
        }
        return obj != null ? obj.toString() : "";
    }
}
