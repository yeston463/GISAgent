package org.example.controller;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.example.agent.AgentLoopService;
import org.example.agent.AgentTaskService;
import org.example.agent.DynamicCodeGenerator;
import org.example.agent.DynamicExecutionGuard;
import org.example.agent.CommandProtocol;
import org.example.service.GisContextService;
import org.example.spatial.SpatialCapabilityCatalog;
import org.example.spatial.AnalysisPlan;
import org.example.spatial.AnalysisProvenanceService;
import org.example.spatial.SpatialPlanService;
import org.example.spatial.KnowledgeGraphRevisionService;
import org.example.spatial.KnowledgeGraphAdminGuard;
import org.example.spatial.AnalysisPlanCompiler;
import org.example.spatial.SpatialRoutingTelemetry;
import org.example.memory.PgVectorMemoryStore;
import org.example.tools.DynamicToolRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    private AgentTaskService agentTaskService;

    @Autowired
    private PgVectorMemoryStore memoryStore;

    @Autowired
    private DynamicToolRegistry toolRegistry;

    @Autowired
    private DynamicExecutionGuard executionGuard;

    @Autowired
    private SpatialCapabilityCatalog spatialCapabilityCatalog;

    @Autowired
    private SpatialPlanService spatialPlanService;

    @Autowired
    private GisContextService gisContextService;

    @Autowired
    private AnalysisProvenanceService analysisProvenanceService;

    @Autowired
    private KnowledgeGraphRevisionService knowledgeGraphRevisionService;

    @Autowired
    private KnowledgeGraphAdminGuard knowledgeGraphAdminGuard;

    @Autowired
    private AnalysisPlanCompiler analysisPlanCompiler;

    @Autowired
    private ChatLanguageModel chatLanguageModel;

    @Autowired
    private SpatialRoutingTelemetry spatialRoutingTelemetry;

    @PostMapping("/chat/agentic")
    public ResponseEntity<Map<String, Object>> agenticChat(@RequestBody ChatRequest request) {
        String memoryId = resolveMemoryId(request.memoryId());
        AgentLoopService.AgentResult result = agentLoopService.execute(
                request.message(), userId(memoryId), memoryId);
        return ResponseEntity.ok(buildResponse(result, memoryId));
    }

    @PostMapping("/chat/jobs")
    public ResponseEntity<Map<String, Object>> submitAgentJob(@RequestBody ChatRequest request) {
        String memoryId = resolveMemoryId(request.memoryId());
        try {
            String jobId = agentTaskService.submit(request.message(), memoryId,
                    () -> agentLoopService.execute(request.message(), userId(memoryId), memoryId));
            return ResponseEntity.accepted().body(Map.of("jobId", jobId, "status", "queued", "memoryId", memoryId));
        } catch (AgentTaskService.CapacityExceededException error) {
            return ResponseEntity.status(503).body(Map.of("status", "Unavailable", "code", "agent_job_capacity_exhausted"));
        }
    }

    @GetMapping("/chat/jobs/{jobId}")
    public ResponseEntity<Map<String, Object>> agentJobStatus(@PathVariable String jobId) {
        Map<String, Object> task = new LinkedHashMap<>(agentTaskService.status(jobId));
        if ("NotFound".equals(task.get("status"))) return ResponseEntity.notFound().build();
        AgentLoopService.AgentResult result = agentTaskService.result(jobId);
        if (result != null) task.put("result", buildResponse(result, String.valueOf(task.get("memoryId"))));
        return ResponseEntity.ok(task);
    }

    @DeleteMapping("/chat/jobs/{jobId}")
    public ResponseEntity<Map<String, Object>> cancelAgentJob(@PathVariable String jobId) {
        Map<String, Object> task = agentTaskService.cancel(jobId);
        return "NotFound".equals(task.get("status")) ? ResponseEntity.notFound().build() : ResponseEntity.ok(task);
    }

    @PostMapping("/chat/jobs/{jobId}/retry")
    public ResponseEntity<Map<String, Object>> retryAgentJob(@PathVariable String jobId) {
        Map<String, Object> task = agentTaskService.retry(jobId);
        return "NotFound".equals(task.get("status")) ? ResponseEntity.notFound().build() : ResponseEntity.accepted().body(task);
    }

    @PostMapping("/chat/stream")
    public ResponseEntity<Map<String, Object>> agenticChatStream(@RequestBody ChatRequest request) {
        String memoryId = resolveMemoryId(request.memoryId());
        AgentLoopService.AgentResult result = agentLoopService.execute(
                request.message(), userId(memoryId), memoryId);
        return ResponseEntity.ok(buildResponse(result, memoryId));
    }

    @PostMapping("/preference")
    public Map<String, Object> savePreference(@RequestBody PreferenceRequest request) {
        String userId = request.userId != null ? request.userId : "default";
        memoryStore.savePreference(userId, request.key, request.value);
        return Map.of("status", "ok");
    }

    @GetMapping("/history")
    public Map<String, Object> getHistory(@RequestParam(defaultValue = "default") String userId) {
        List<Map<String, Object>> history = memoryStore.getRecentAnalyses(userId, 20);
        return Map.of("history", history);
    }

    @PostMapping("/execute")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> executeDynamic(@RequestBody ExecuteRequest request, HttpServletRequest httpRequest) {
        if (!executionGuard.isEnabled()) {
            return ResponseEntity.status(404)
                    .body(Map.of("status", "Disabled", "message", "动态执行功能未启用"));
        }
        if (!executionGuard.authorize(httpRequest)) {
            return ResponseEntity.status(403)
                    .body(Map.of("status", "Forbidden", "message", "动态执行未授权：缺少有效令牌或本机来源"));
        }
        DynamicCodeGenerator.CodeExecutionResult result =
                request.name() == null || request.name().isBlank()
                        ? codeGenerator.generateAndExecute(request.requirement(), request.context())
                        : codeGenerator.generateAndRegister(
                                request.name(), request.description(), request.requirement(), request.context());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", result.status());
        response.put("message", result.message());
        response.put("code", result.generatedCode());
        response.put("result", result.result() != null ? result.result() : "no result");
        if (result.registeredTool() != null) {
            response.put("registeredTool", result.registeredTool());
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping("/tools")
    public Map<String, Object> tools() {
        return Map.of("tools", toolRegistry.getToolDescriptors());
    }

    @GetMapping("/capabilities")
    public Map<String, Object> capabilities() {
        return Map.of("capabilities", spatialCapabilityCatalog.descriptors(), "graph", spatialCapabilityCatalog.status());
    }

    @GetMapping("/capabilities/status")
    public Map<String, Object> capabilityGraphStatus() { return spatialCapabilityCatalog.status(); }

    @PostMapping("/capabilities/refresh")
    public Map<String, Object> refreshCapabilityGraph() { return spatialCapabilityCatalog.refresh(); }

    @PostMapping("/capabilities/candidates/preview")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> previewCapabilityGraph(@RequestBody CapabilityGraphRequest request, HttpServletRequest httpRequest) {
        if (!knowledgeGraphAdminGuard.authorize(httpRequest)) return ResponseEntity.status(403).body(Map.of("valid", false, "code", "graph_admin_forbidden"));
        try {
            return ResponseEntity.ok(knowledgeGraphRevisionService.preview(request.graph(), request.acceptanceTests()));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(Map.of("valid", false, "code", "graph_rejected", "message", error.getMessage()));
        }
    }

    @PostMapping("/capabilities/candidates/ai-generate")
    public ResponseEntity<Map<String, Object>> generateCapabilityGraphCandidate(
            @RequestBody CapabilityGraphAiRequest request, HttpServletRequest httpRequest) {
        if (!knowledgeGraphAdminGuard.authorize(httpRequest)) {
            return ResponseEntity.status(403).body(Map.of("code", "graph_admin_forbidden"));
        }
        try {
            return ResponseEntity.ok(generateAiCapabilityCandidate(request));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(Map.of("code", "graph_generation_rejected", "message", error.getMessage()));
        } catch (Exception error) {
            return ResponseEntity.status(502).body(Map.of("code", "graph_generation_failed", "message", "AI graph generation failed: " + safeGraphError(error)));
        }
    }

    @PostMapping("/capabilities/publish")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> publishCapabilityGraph(@RequestBody CapabilityGraphRequest request, HttpServletRequest httpRequest) {
        if (!knowledgeGraphAdminGuard.authorize(httpRequest)) return ResponseEntity.status(403).body(Map.of("published", false, "code", "graph_admin_forbidden"));
        try {
            return ResponseEntity.ok(knowledgeGraphRevisionService.publish(request.graph(), request.author(), request.note(), request.acceptanceTests()));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.badRequest().body(Map.of("published", false, "code", "graph_rejected", "message", error.getMessage()));
        }
    }

    @GetMapping("/capabilities/revisions")
    public Map<String, Object> capabilityGraphRevisions() {
        return Map.of("revisions", knowledgeGraphRevisionService.list(), "active", spatialCapabilityCatalog.status());
    }

    @PostMapping("/capabilities/test-intents")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> testCapabilityIntents(@RequestBody CapabilityIntentTestRequest request, HttpServletRequest httpRequest) {
        if (!knowledgeGraphAdminGuard.authorize(httpRequest)) {
            return ResponseEntity.status(403).body(Map.of("code", "graph_admin_forbidden"));
        }
        List<String> utterances = request.utterances() == null ? List.of() : request.utterances().stream()
                .filter(item -> item != null && !item.isBlank()).limit(30).toList();
        List<Map<String, Object>> results = new java.util.ArrayList<>();
        for (String utterance : utterances) {
            AnalysisPlanCompiler.Compilation compilation = analysisPlanCompiler.compile(utterance).orElse(null);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("utterance", utterance);
            item.put("matched", compilation != null);
            if (compilation != null) {
                item.put("capabilityId", compilation.capabilityId());
                item.put("tool", compilation.plan().tool());
                item.put("operations", compilation.plan().operations());
                item.put("params", compilation.params());
            }
            results.add(item);
        }
        return ResponseEntity.ok(Map.of("results", results, "graph", spatialCapabilityCatalog.status()));
    }

    @PostMapping("/capabilities/rollback/{version}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> rollbackCapabilityGraph(@PathVariable String version, HttpServletRequest httpRequest) {
        if (!knowledgeGraphAdminGuard.authorize(httpRequest)) return ResponseEntity.status(403).body(Map.of("rolledBack", false, "code", "graph_admin_forbidden"));
        try {
            return ResponseEntity.ok(knowledgeGraphRevisionService.rollback(version));
        } catch (IllegalArgumentException error) {
            return ResponseEntity.status(404).body(Map.of("rolledBack", false, "code", "revision_not_found", "message", error.getMessage()));
        }
    }

    private Map<String, Object> generateAiCapabilityCandidate(CapabilityGraphAiRequest request) {
        if (request == null || request.capabilityId() == null || request.capabilityId().isBlank()) {
            throw new IllegalArgumentException("capability_id_required");
        }
        String capabilityId = request.capabilityId().trim();
        if (!capabilityId.matches("[a-z][a-z0-9_]{1,80}")) throw new IllegalArgumentException("invalid_capability_id");
        List<Map<String, Object>> registered = spatialCapabilityCatalog.descriptors();
        Map<String, Object> requestedCapability = registered.stream()
                .filter(item -> capabilityId.equals(item.get("id")))
                .findFirst()
                .orElse(null);
        String userRequest = request.request() == null ? "" : request.request().trim();
        if (userRequest.isBlank()) throw new IllegalArgumentException("generation_request_required");
        if (userRequest.length() > 4000) throw new IllegalArgumentException("generation_request_too_long");

        String prompt = """
                你是空间知识图谱编辑助手。为已注册能力生成语义草稿。
                只返回 JSON，不要 Markdown，不要解释。JSON 格式严格为：
                {"version":"候选版本","baseCapabilityId":"执行基准能力 ID","aliases":["至少一个中文别名"],"purpose":"知识说明","acceptanceUtterances":["能匹配别名的中文验收语句"]}
                不得生成 tool、operations、requires、outputs、rendererKinds、dataRequirements。
                候选能力 ID：%s。若它是新能力，必须从已注册能力中选择最匹配的 baseCapabilityId；
                系统只会复用该基准的已批准执行契约，不能新增工具或操作。
                已注册能力：%s
                用户需求：%s
                """.formatted(capabilityId, JSON.toJSONString(registered), userRequest);
        String raw = chatLanguageModel.generate(prompt);
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("ai_returned_empty_response");
        raw = raw.trim().replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        JSONObject semantic = JSON.parseObject(raw);
        if (semantic == null) throw new IllegalArgumentException("ai_returned_invalid_json");

        String baseCapabilityId = requestedCapability == null ? semantic.getString("baseCapabilityId") : capabilityId;
        if (baseCapabilityId == null || baseCapabilityId.isBlank()) throw new IllegalArgumentException("ai_candidate_requires_base_capability");
        Map<String, Object> baseline = registered.stream()
                .filter(item -> baseCapabilityId.trim().equals(item.get("id")))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("base_capability_not_registered:" + baseCapabilityId));

        List<String> aliases = graphStrings(semantic.getJSONArray("aliases"));
        List<String> utterances = graphStrings(semantic.getJSONArray("acceptanceUtterances"));
        String purpose = semantic.getString("purpose");
        if (aliases.isEmpty() || aliases.stream().noneMatch(alias -> alias.matches(".*[\\u4e00-\\u9fff].*"))) {
            throw new IllegalArgumentException("ai_candidate_requires_chinese_alias");
        }
        if (utterances.isEmpty()) throw new IllegalArgumentException("ai_candidate_requires_acceptance_utterance");
        if (purpose == null || purpose.isBlank()) throw new IllegalArgumentException("ai_candidate_requires_purpose");

        String version = request.candidateVersion() == null ? "" : request.candidateVersion().trim();
        if (version.isBlank()) version = semantic.getString("version");
        if (version == null || version.isBlank()) version = "ai-" + System.currentTimeMillis();
        if (!version.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,80}")) throw new IllegalArgumentException("invalid_candidate_version");

        JSONObject edited = JSON.parseObject(JSON.toJSONString(baseline));
        edited.put("id", capabilityId);
        edited.put("aliases", aliases);
        JSONObject knowledge = edited.getJSONObject("knowledge");
        if (knowledge == null) knowledge = new JSONObject();
        knowledge.put("purpose", purpose.trim());
        edited.put("knowledge", knowledge);
        JSONObject graph = new JSONObject();
        graph.put("version", version);
        JSONArray capabilities = new JSONArray();
        capabilities.add(edited);
        graph.put("capabilities", capabilities);
        String graphJson = graph.toJSONString();
        Map<String, List<String>> acceptanceTests = Map.of(capabilityId, utterances);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", version);
        result.put("aliases", aliases);
        result.put("purpose", purpose.trim());
        result.put("acceptanceUtterances", utterances);
        result.put("graph", graphJson);
        result.put("preview", knowledgeGraphRevisionService.preview(graphJson, acceptanceTests));
        return result;
    }

    private List<String> graphStrings(JSONArray values) {
        if (values == null) return List.of();
        return values.toJavaList(String.class).stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .limit(20)
                .toList();
    }

    private String safeGraphError(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) return error.getClass().getSimpleName();
        String compact = message.replace('\r', ' ').replace('\n', ' ').trim();
        return compact.substring(0, Math.min(compact.length(), 180));
    }

    @GetMapping("/runs")
    public Map<String, Object> recentRuns(@RequestParam(defaultValue = "20") int limit) {
        return Map.of("runs", analysisProvenanceService.recent(Math.max(1, Math.min(limit, 200))));
    }

    @GetMapping("/routing/metrics")
    public Map<String, Object> routingMetrics() {
        return spatialRoutingTelemetry.snapshot();
    }

    @PostMapping("/plans/validate")
    public ResponseEntity<Map<String, Object>> validatePlan(@RequestBody PlanRequest request) {
        String memoryId = resolveMemoryId(request.memoryId());
        gisContextService.activateSession(memoryId);
        try {
            SpatialPlanService.PreparedPlan prepared = request.plan() == null
                    ? spatialPlanService.prepare(request.capabilityId(), request.params())
                    : spatialPlanService.prepare(request.plan());
            Map<String, Object> provenance = spatialPlanService.record(prepared, Map.of());
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("memoryId", memoryId);
            response.put("plan", prepared.plan());
            response.put("availability", prepared.availability());
            response.put("validation", prepared.validation().asMap());
            response.put("provenance", provenance);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "InvalidPlan", "code", "capability_not_registered", "message", e.getMessage()));
        }
    }

    @DeleteMapping("/tools/{name}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> removeDynamicTool(@PathVariable String name, HttpServletRequest httpRequest) {
        if (!executionGuard.isEnabled()) {
            return ResponseEntity.status(404)
                    .body(Map.of("status", "Disabled", "message", "动态执行功能未启用"));
        }
        if (!executionGuard.authorize(httpRequest)) {
            return ResponseEntity.status(403)
                    .body(Map.of("status", "Forbidden", "message", "动态工具删除未授权：缺少有效令牌或本机来源"));
        }
        boolean removed = toolRegistry.removeDynamicTool(name);
        if (removed) {
            codeGenerator.forgetDynamicTool(name);
        }
        return ResponseEntity.ok(Map.of("status", removed ? "Success" : "NotFound", "tool", name));
    }

    @PostMapping("/tools/{name}/rollback")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Map<String, Object>> rollbackDynamicTool(
            @PathVariable String name, @RequestParam long version, HttpServletRequest httpRequest) {
        if (!executionGuard.isEnabled()) {
            return ResponseEntity.status(404).body(Map.of("status", "Disabled"));
        }
        if (!executionGuard.authorize(httpRequest)) {
            return ResponseEntity.status(403).body(Map.of("status", "Forbidden"));
        }
        Map<String, Object> result = codeGenerator.rollbackDynamicTool(name, version);
        return "Success".equals(result.get("status"))
                ? ResponseEntity.ok(result)
                : ResponseEntity.status(404).body(result);
    }

    public record ChatRequest(String message, String memoryId) {}

    public record ExecuteRequest(String requirement, JSONObject context, String name, String description) {}

    public record PreferenceRequest(String userId, String key, String value) {}

    public record PlanRequest(String capabilityId, String memoryId, Map<String, Object> params, AnalysisPlan plan) {}

    public record CapabilityGraphRequest(String graph, String author, String note, Map<String, List<String>> acceptanceTests) {}

    public record CapabilityGraphAiRequest(String capabilityId, String request, String candidateVersion) {}

    public record CapabilityIntentTestRequest(List<String> utterances) {}

    private Map<String, Object> buildResponse(AgentLoopService.AgentResult result, String memoryId) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("protocolVersion", CommandProtocol.VERSION);
        Map<String, Object> outcome = result.outcome() == null || result.outcome().isEmpty()
                ? Map.of("status", result.needsClarification() ? "NeedsClarification" : "Success")
                : result.outcome();
        response.put("outcome", outcome);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schemaVersion", CommandProtocol.VERSION);
        envelope.put("status", outcome.getOrDefault("status", "Success"));
        envelope.put("analysis", Map.of("id", memoryId));
        List<Map<String, Object>> outputs = new java.util.ArrayList<>();
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
            outputs.add(Map.of("kind", "commands", "data", commands));
        }
        if (result.metrics() != null) {
            response.put("metrics", result.metrics());
            outputs.add(Map.of("kind", "metric", "data", result.metrics()));
        }
        envelope.put("outputs", outputs);
        envelope.put("warnings", List.of());
        boolean failed = "Error".equals(outcome.get("status")) || "Failed".equals(outcome.get("status"));
        envelope.put("errors", failed ? List.of(outcome) : List.of());
        envelope.put("capability", "CapabilityPending".equals(outcome.get("status")) ? outcome : Map.of());
        envelope.put("provenance", outcome.getOrDefault("provenance", Map.of()));
        envelope.put("commands", response.getOrDefault("commands", new JSONArray()));
        response.put("resultEnvelope", envelope);
        response.put("trace", result.trace() == null ? List.of() : result.trace());
        response.put("memoryId", memoryId);
        return response;
    }

    private String resolveMemoryId(String requested) {
        return requested == null || requested.isBlank() ? UUID.randomUUID().toString() : requested;
    }

    private String userId(String memoryId) {
        return memoryId == null || memoryId.isBlank() ? "default" : memoryId;
    }

    private void appendAgentCommands(JSONArray commands, List<Map<String, Object>> agentCommands) {
        if (agentCommands == null) {
            return;
        }
        for (Map<String, Object> cmd : CommandProtocol.normalize(agentCommands)) {
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
            if (!hasEquivalentCommand(commands, metricCommand)) {
                metricCommand.putIfAbsent("protocolVersion", CommandProtocol.VERSION);
                metricCommand.putIfAbsent("commandId", "metric-" + i + "-" + metricCommand.getString("action"));
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

    private boolean hasEquivalentCommand(JSONArray commands, JSONObject candidate) {
        String candidateKey = CommandProtocol.dedupeKey(candidate.getInnerMap());
        for (int i = 0; i < commands.size(); i++) {
            if (candidateKey.equals(CommandProtocol.dedupeKey(commands.getJSONObject(i).getInnerMap()))) {
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
