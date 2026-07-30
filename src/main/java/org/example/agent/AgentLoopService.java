package org.example.agent;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.example.memory.PgVectorMemoryStore;
import org.example.tools.DynamicToolRegistry;
import org.example.agent.DynamicExecutionConfig;
import org.example.service.KnowledgeService;
import org.example.service.PromptResourceService;
import org.example.service.GisContextService;
import org.example.tools.pyGisTools;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    @Autowired
    private KnowledgeService knowledgeService;

    @Autowired
    private PromptResourceService promptResources;

    @Autowired
    private DynamicCodeGenerator dynamicCodeGenerator;

    @Autowired
    private DynamicExecutionConfig dynamicExecutionConfig;

    @Autowired
    private pyGisTools gisTools;

    @Autowired
    private GisContextService gisContextService;

    @Value("${agent.max-rounds:8}")
    private int maxRounds;

    public AgentResult execute(String userMessage, String userId, String memoryId) {
        return execute(userMessage, userId, memoryId, trace -> { });
    }

    public AgentResult execute(
            String userMessage,
            String userId,
            String memoryId,
            Consumer<ExecutionTrace> traceListener) {
        gisContextService.activateSession(memoryId);
        List<ExecutionTrace> trace = new ArrayList<>();
        NavigationRequest navigationRequest = parseNavigationRequest(userMessage);
        if (navigationRequest != null) {
            return executeNavigation(navigationRequest, trace, traceListener);
        }
        TaskPlan taskPlan = createTaskPlan(userMessage);
        if (taskPlan.isModelPipeline()) {
            return executePlanningDemo(userMessage, userId, taskPlan, trace, traceListener);
        }
        if (isPlanningDemoRequest(userMessage)) {
            return executePlanningDemo(userMessage, userId, taskPlan, trace, traceListener);
        }

        if (shouldAskClarification(userMessage)) {
            String question = clarificationEngine.ask(userMessage);
            if (question != null) {
                addTrace(trace, traceListener, 0, "ask", "需要补充信息", question, "waiting");
                return new AgentResult(null, null, question, true, null, List.of(), trace);
            }
        }

        // A clear "place + radius" request is deterministic GIS work. Do not
        // make a transient planner-format failure block geocoding and analysis.
        AgentResult directPlaceResult = taskPlan.isPlaceAnalysis()
                ? executeExplicitPlaceAnalysis(userMessage, userId, trace, traceListener)
                : null;
        if (directPlaceResult != null) {
            return directPlaceResult;
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        String systemPrompt = promptResources.load("system.txt")
                + "\n\n当前运行时工具清单：\n"
                + JSON.toJSONString(toolRegistry.getToolDescriptions());
        messages.add(Map.of("role", "system", "content", systemPrompt));

        String memoryContext = buildMemoryContext(userId, userMessage);
        if (memoryContext != null) {
            messages.add(Map.of("role", "system", "content", "User memory:\n" + memoryContext));
        }
        messages.add(Map.of("role", "user", "content", userMessage == null ? "" : userMessage));

        Map<String, Object> finalMetrics = null;
        String finalReply = null;
        List<String> finalSuggestions = null;
        List<Map<String, Object>> pendingCommands = new ArrayList<>();

        int rounds = Math.max(1, maxRounds);
        int lastRound = 0;
        for (int round = 1; round <= rounds; round++) {
            lastRound = round;
            JSONObject decision = askModelForDecision(messages);
            if (decision == null) {
                addTrace(trace, traceListener, round, "error", "无法解析模型决策", "本轮没有得到有效 JSON", "error");
                break;
            }

            String action = decision.getString("action");
            String summary = decision.getString("summary");
            if (action == null || action.isBlank()) {
                addTrace(trace, traceListener, round, "error", "决策缺少动作", "模型返回了空 action", "error");
                break;
            }

            addTrace(trace, traceListener, round, "decision", "第 " + round + " 轮决策",
                    summarizeDecision(action, summary), "running");

            if ("respond".equals(action)) {
                finalReply = decision.getString("content");
                finalSuggestions = parseSuggestions(decision.getJSONArray("suggestions"));
                addTrace(trace, traceListener, round, "complete", "生成最终答复", "已完成工具结果汇总", "success");
                break;
            }

            if ("ask".equals(action)) {
                String clarification = decision.getString("content");
                addTrace(trace, traceListener, round, "ask", "等待用户补充", clarification, "waiting");
                return new AgentResult(null, null, clarification, true, null, List.of(), trace);
            }

            JSONObject params = decision.getJSONObject("params");
            if (params == null) {
                params = new JSONObject();
            }
            addTrace(trace, traceListener, round, "action", "调用 " + action,
                    summarizeParams(action, params), "running");

            Object rawResult = invokeTool(action, params);
            Map<String, Object> resultMap = asMap(rawResult);
            Map<String, Object> observedMap = resultMap;
            String observation = formatObservation(action, rawResult);
            boolean observationFailed = resultMap != null && isFailed(resultMap);

            if (resultMap != null) {
                collectCommands(resultMap, pendingCommands);

                if (isValidMetrics(resultMap)) {
                    finalMetrics = validationLayer.validateMetrics(resultMap);
                    String synthesis = synthesize(finalMetrics, userMessage);
                    if (synthesis != null && !synthesis.isBlank()) {
                        observation = synthesis;
                    }
                } else if (isAdvancedAnalysis(resultMap)) {
                    finalMetrics = resultMap;
                } else if (isFailed(resultMap)) {
                    Map<String, Object> fallback = tryFallback(action, params, resultMap, round - 1);
                    if (fallback != null) {
                        observationFailed = isFailed(fallback);
                        observedMap = fallback;
                        collectCommands(fallback, pendingCommands);
                        observation = "主工具失败，已尝试降级：" + formatObservation("fallback", fallback);
                        if (isValidMetrics(fallback)) {
                            finalMetrics = validationLayer.validateMetrics(fallback);
                            String synthesis = synthesize(finalMetrics, userMessage);
                            if (synthesis != null && !synthesis.isBlank()) {
                                observation = synthesis;
                            }
                        } else if (isAdvancedAnalysis(fallback)) {
                            finalMetrics = fallback;
                        }
                        addTrace(trace, traceListener, round, "fallback", "执行降级策略",
                                formatTraceDetail(fallback), isFailed(fallback) ? "error" : "success");
                    }
                }
            }

            addTrace(trace, traceListener, round, "observation", "获得工具结果",
                    formatTraceDetail(observedMap == null ? rawResult : observedMap),
                    observationFailed ? "error" : "success");
            messages.add(Map.of("role", "assistant", "content",
                    safe(summary, "选择 " + action)));
            messages.add(Map.of("role", "user", "content",
                    "Tool " + action + " returned:\n" + observation));
        }

        if (finalReply == null) {
            if (finalMetrics != null && isAdvancedAnalysis(finalMetrics)) {
                finalReply = buildAdvancedReply(finalMetrics);
            } else {
                finalReply = finalMetrics != null
                        ? buildMetricReply(finalMetrics)
                        : "暂未获取足够的有效 GIS 数据。请提供更具体的地点，或绘制/上传 AOI。";
            }
        }

        if (finalSuggestions == null || finalSuggestions.isEmpty()) {
            finalSuggestions = suggestionEngine.generateSuggestions(finalMetrics);
        }

        saveAnalysis(userId, userMessage, finalMetrics);
        memoryStore.cleanupExpired();
        addTrace(trace, traceListener, Math.max(1, lastRound), "complete", "任务结束", "已返回结果和可执行地图命令", "success");

        return new AgentResult(finalReply, finalSuggestions, null, false,
                finalMetrics, dedupeCommands(pendingCommands), trace);
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

    private boolean isCurrentContextPlanningRequest(String userMessage) {
        if (userMessage == null) {
            return false;
        }
        String lower = userMessage.toLowerCase();
        return lower.contains("cityengine")
                && (lower.contains("geoscene")
                || lower.contains("slpk")
                || lower.contains("publish")
                || lower.contains("generate"));
    }

    private TaskPlan createTaskPlan(String message) {
        PlaceAnalysisRequest placeRequest = parsePlaceAnalysisRequest(message);
        String lower = message == null ? "" : message.toLowerCase();
        boolean model = lower.contains("cityengine") || lower.contains("slpk") || lower.contains("geoscene");
        Subject subject = placeRequest != null ? Subject.PLACE
                : lower.contains("current") || (message != null && message.contains("当前")) ? Subject.CURRENT_CONTEXT : Subject.AOI;
        return new TaskPlan(model ? Intent.MODEL : placeRequest != null ? Intent.ANALYZE : Intent.OPEN, subject, placeRequest);
    }

    private record TaskPlan(Intent intent, Subject subject, PlaceAnalysisRequest placeRequest) {
        static TaskPlan legacyParse(String message) {
            String lower = message == null ? "" : message.toLowerCase();
            boolean model = lower.contains("cityengine") || lower.contains("slpk") || lower.contains("geoscene");
            boolean place = Pattern.compile("(?:周边|附近|周围|半径|范围|\\d+(?:\\.\\d+)?\\s*(?:公里|千米|km|米|m))")
                    .matcher(message == null ? "" : message).find();
            Subject subject = lower.contains("current") || (message != null && message.contains("当前"))
                    ? Subject.CURRENT_CONTEXT : place ? Subject.PLACE : Subject.AOI;
            return new TaskPlan(model ? Intent.MODEL : place ? Intent.ANALYZE : Intent.OPEN, subject, null);
        }

        boolean isModelPipeline() { return intent == Intent.MODEL; }
        boolean isPlaceAnalysis() { return intent == Intent.ANALYZE && subject == Subject.PLACE; }
    }

    private enum Intent { ANALYZE, MODEL, OPEN }
    private enum Subject { CURRENT_CONTEXT, PLACE, AOI }

    private AgentResult executePlanningDemo(
            String userMessage,
            String userId,
            TaskPlan taskPlan,
            List<ExecutionTrace> trace,
            Consumer<ExecutionTrace> traceListener) {
        List<Map<String, Object>> planningCommands = new ArrayList<>();
        if (taskPlan.subject() == Subject.PLACE && taskPlan.placeRequest() != null) {
            addTrace(trace, traceListener, 1, "action", "按任务计划恢复地点上下文",
                    "地点：" + taskPlan.placeRequest().locationName() + "；半径："
                            + taskPlan.placeRequest().radiusMeters() + " 米", "running");
            AgentResult recovered = executeExplicitPlaceAnalysis(userMessage, userId, trace, traceListener);
            if (recovered == null || recovered.metrics() == null) {
                return recovered == null
                        ? new AgentResult("无法根据请求恢复地点分析上下文。", List.of("提供地点和半径"), null, false, null, List.of(), trace)
                        : recovered;
            }
            if (recovered.commands() != null) {
                planningCommands.addAll(recovered.commands());
            }
        }
        addTrace(trace, traceListener, 1, "action", "检索规划知识库",
                "提取 CityEngine、CGA 与规划控制资料", "running");
        String ragContext = knowledgeService.search(
                "CityEngine 建筑生成 CGA 规划控制 建筑高度 退界 楼层 立面 导出 SLPK OBJ " + userMessage,
                5
        );
        String requirementsJson = generateCityEngineRequirements(userMessage, ragContext);
        JSONObject params = new JSONObject();
        params.put("requirementsJson", requirementsJson);
        params.put("ragContext", ragContext);
        params.put("userRequest", userMessage);
        params.put("useDemoCase", userMessage.contains("比赛案例") || userMessage.toLowerCase().contains("competition demo"));

        addTrace(trace, traceListener, 2, "action", "提交 CityEngine 规划任务",
                "使用当前 AOI、建筑和受约束规划参数", "running");
        Map<String, Object> result = asMap(invokeTool("submitCityEnginePlanningJob", params));
        collectCommands(result, planningCommands);
        if (result != null && isFailed(result) && parsePlaceAnalysisRequest(userMessage) != null) {
            addTrace(trace, traceListener, 2, "fallback", "从请求恢复空间上下文",
                    "当前上下文不可用；根据请求中的地点和半径重新定位、分析并构建 AOI", "running");
            AgentResult recovered = executeExplicitPlaceAnalysis(userMessage, userId, trace, traceListener);
            if (recovered != null && recovered.metrics() != null) {
                if (recovered.commands() != null) {
                    planningCommands.addAll(recovered.commands());
                }
                addTrace(trace, traceListener, 2, "action", "重新提交 CityEngine 规划任务",
                        "已恢复地点 AOI 与建筑数据，继续生成三维成果", "running");
                result = asMap(invokeTool("submitCityEnginePlanningJob", params));
                collectCommands(result, planningCommands);
            }
        }
        if (result == null || isFailed(result)) {
            String message = result == null
                    ? "CityEngine 任务没有返回有效结果。"
                    : String.valueOf(result.getOrDefault("message", "CityEngine 任务提交失败。"));
            addTrace(trace, traceListener, 2, "error", "规划任务提交失败", message, "error");
            return new AgentResult(message, List.of("检查 Python GIS 服务与 CityEngine 2025.1"),
                    null, false, null, dedupeCommands(planningCommands), trace);
        }

        // The planning endpoints wrap the deterministic building metrics in
        // `current.metrics`.  Keep that object as the source for the result
        // commands; validating the outer planning envelope means the
        // controller cannot emit `showAnalysisResult`, so the metrics panel
        // silently disappears even though the CityEngine job succeeds.
        Map<String, Object> metrics = validationLayer.validateMetrics(
                extractPlanningMetrics(result));
        Map<String, Object> cityEngineJob = asMap(result.get("cityEngineJob"));
        String jobId = cityEngineJob == null ? "未知" : String.valueOf(cityEngineJob.getOrDefault("jobId", "未知"));
        String script = cityEngineJob == null ? "" : String.valueOf(cityEngineJob.getOrDefault("generatedScript", ""));
        String rule = cityEngineJob == null ? "" : String.valueOf(cityEngineJob.getOrDefault("generatedRule", ""));
        addTrace(trace, traceListener, 3, "action", "CityEngine 正在生成模型",
                "作业编号 " + jobId + "，正在执行 CGA 规则并生成三维建筑", "running");
        Map<String, Object> completed = waitForPlanningPipeline(jobId, trace, traceListener, 600);
        String status = completed == null ? "queued" : String.valueOf(completed.getOrDefault("status", "queued"));
        String reply;
        List<String> suggestions;
        if ("completed".equalsIgnoreCase(status)) {
            Map<String, Object> outputs = asMap(completed.get("outputs"));
            StringBuilder outputText = new StringBuilder();
            if (outputs != null && outputs.containsKey("slpk")) {
                outputText.append("\n- SLPK 下载：/analysis/cityengine/jobs/").append(jobId).append("/download/slpk");
            }
            if (completed.get("sceneServiceUrl") != null) {
                outputText.append("\n- Scene Service：").append(completed.get("sceneServiceUrl"));
            } else {
                Map<String, Object> publication = asMap(completed.get("publication"));
                if (publication != null && "failed".equalsIgnoreCase(String.valueOf(publication.get("status")))) {
                    outputText.append("\n- 自动发布：失败（").append(publication.get("message")).append("）");
                }
            }
            if (outputs != null && outputs.containsKey("obj")) {
                outputText.append("\n- OBJ ZIP 下载：/analysis/cityengine/jobs/").append(jobId).append("/download/obj");
            }
            reply = "RAG 与 LLM 已生成规划参数，CityEngine 已完成建筑生成和成果导出。"
                    + "\n\n- CityEngine 作业：" + jobId
                    + "\n- Python 脚本：" + script
                    + "\n- CGA 规则：" + rule
                    + outputText;
            suggestions = List.of("下载并检查 SLPK", "将 SLPK 加载到 ArcGIS Scene", "根据效果继续调整规划参数");
        } else if ("failed".equalsIgnoreCase(status)) {
            String error = String.valueOf(completed.getOrDefault("message", "CityEngine 执行失败"));
            reply = "CityEngine 作业执行失败。\n\n- 作业：" + jobId + "\n- 错误：" + error;
            suggestions = List.of("检查生成脚本与 CGA 规则", "检查 CityEngine 日志", "调整规划参数后重新生成");
        } else {
            reply = "RAG 与 LLM 已生成规划要求和 CityEngine 自动化脚本，作业仍在执行。"
                    + "\n\n- CityEngine 作业：" + jobId
                    + "\n- Python 脚本：" + script
                    + "\n- CGA 规则：" + rule;
            suggestions = List.of("稍后查询 CityEngine 作业状态", "检查 CityEngine 运行窗口", "完成后下载 SLPK 或 OBJ");
        }
        boolean pipelineComplete = completed != null && completed.get("sceneServiceUrl") != null;
        boolean pipelineFailed = completed != null
                && asMap(completed.get("publicationProgress")) != null
                && "error".equalsIgnoreCase(String.valueOf(
                        asMap(completed.get("publicationProgress")).get("status")));
        addTrace(trace, traceListener, 8, pipelineFailed ? "error" : "complete", "规划成果流水线状态",
                pipelineComplete ? "CityEngine、GeoScene 发布与前端加载准备已完成" : "当前状态：" + status,
                pipelineComplete ? "success" : pipelineFailed ? "error" : "waiting");
        saveAnalysis(userId, userMessage, metrics);
        return new AgentResult(reply, suggestions, null, false, metrics, dedupeCommands(planningCommands), trace);
    }

    private Map<String, Object> waitForPlanningPipeline(
            String jobId,
            List<ExecutionTrace> trace,
            Consumer<ExecutionTrace> traceListener,
            int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + Math.max(1, timeoutSeconds) * 1000L;
        Set<String> emittedStages = new HashSet<>();
        Map<String, Object> latest = Map.of("status", "queued", "jobId", jobId);

        while (System.currentTimeMillis() < deadline) {
            latest = gisTools.getCityEngineJob(jobId);
            String jobStatus = String.valueOf(latest.getOrDefault("status", "queued"));
            if ("failed".equalsIgnoreCase(jobStatus) || "not_found".equalsIgnoreCase(jobStatus)
                    || "Error".equalsIgnoreCase(jobStatus)) {
                addTrace(trace, traceListener, 3, "error", "CityEngine 模型生成失败",
                        String.valueOf(latest.getOrDefault("message", "作业未完成")), "error");
                return latest;
            }

            if ("completed".equalsIgnoreCase(jobStatus) && emittedStages.add("slpk_exported")) {
                Map<String, Object> outputs = asMap(latest.get("outputs"));
                if (outputs != null && outputs.get("slpk") != null) {
                    updateTrace(trace, traceListener, 3, "CityEngine 正在生成模型",
                            "CGA 规则执行完成，三维建筑模型已生成", "success");
                    addTrace(trace, traceListener, 4, "artifact", "SLPK 导出完成",
                            String.valueOf(outputs.get("slpk")), "success");
                }
            }

            if ("completed".equalsIgnoreCase(jobStatus)
                    && asMap(latest.get("outputs")) != null
                    && asMap(latest.get("outputs")).get("slpk") != null
                    && latest.get("sceneServiceUrl") == null
                    && emittedStages.add("publication_requested")) {
                addTrace(trace, traceListener, 5, "publish", "正在上传 GeoScene Portal",
                        "SLPK 已生成，开始发布托管 Scene Service", "running");
                latest = gisTools.publishCityEngineJob(jobId);
            }

            emitPublicationTimeline(latest, emittedStages, trace, traceListener);
            if (latest.get("sceneServiceUrl") != null) {
                return latest;
            }
            Map<String, Object> progress = asMap(latest.get("publicationProgress"));
            if (progress != null && "error".equalsIgnoreCase(String.valueOf(progress.get("status")))) {
                return latest;
            }

            try {
                Thread.sleep(2000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return latest;
            }
        }
        latest = new LinkedHashMap<>(latest);
        latest.put("waitTimedOut", true);
        return latest;
    }

    private void emitPublicationTimeline(
            Map<String, Object> result,
            Set<String> emittedStages,
            List<ExecutionTrace> trace,
            Consumer<ExecutionTrace> traceListener) {
        Object timelineValue = result.get("publicationTimeline");
        if (!(timelineValue instanceof List<?> timeline)) {
            Map<String, Object> progress = asMap(result.get("publicationProgress"));
            if (progress != null) {
                emitPublicationStage(progress, emittedStages, trace, traceListener);
            }
            return;
        }
        for (Object item : timeline) {
            Map<String, Object> event = asMap(item);
            if (event != null) {
                emitPublicationStage(event, emittedStages, trace, traceListener);
            }
        }
    }

    private void emitPublicationStage(
            Map<String, Object> event,
            Set<String> emittedStages,
            List<ExecutionTrace> trace,
            Consumer<ExecutionTrace> traceListener) {
        String stage = String.valueOf(event.getOrDefault("stage", ""));
        Set<String> visibleStages = Set.of(
                "portal_uploading", "portal_uploaded",
                "scene_publishing", "scene_published",
                "geoscene_hosted", "publication_failed");
        if (stage.isBlank() || !visibleStages.contains(stage) || !emittedStages.add(stage)) {
            return;
        }
        String detail = String.valueOf(event.getOrDefault("message", stage));
        String status = String.valueOf(event.getOrDefault("status", "running"));
        int round = switch (stage) {
            case "portal_uploading", "portal_uploaded" -> 5;
            case "scene_publishing", "scene_published" -> 6;
            case "geoscene_hosting", "geoscene_hosted" -> 7;
            default -> 7;
        };
        String title = switch (stage) {
            case "portal_uploading" -> "正在上传 GeoScene Portal";
            case "portal_uploaded" -> "正在上传 GeoScene Portal";
            case "scene_publishing" -> "正在发布 Scene Service";
            case "scene_published" -> "正在发布 Scene Service";
            case "geoscene_hosting" -> "正在验证 GeoScene 托管";
            case "geoscene_hosted" -> "GeoScene 托管完成";
            case "publication_failed" -> "GeoScene 发布失败";
            default -> "GeoScene 发布状态";
        };
        if ("portal_uploaded".equals(stage) || "scene_published".equals(stage)) {
            updateTrace(trace, traceListener, round, title, detail, "success");
        } else {
            addTrace(trace, traceListener, round,
                    "error".equalsIgnoreCase(status) ? "error" : "publish",
                    title, detail, status);
        }
    }

    private void updateTrace(
            List<ExecutionTrace> trace,
            Consumer<ExecutionTrace> listener,
            int round,
            String title,
            String detail,
            String status) {
        ExecutionTrace updated = new ExecutionTrace(round, "publish", title, detail, status);
        for (int index = trace.size() - 1; index >= 0; index--) {
            ExecutionTrace existing = trace.get(index);
            if (existing.round() == round && existing.title().equals(title)) {
                trace.set(index, updated);
                if (listener != null) {
                    try {
                        listener.accept(updated);
                    } catch (Exception ignored) {
                        // A disconnected SSE client must not abort publication.
                    }
                }
                return;
            }
        }
        trace.add(updated);
        if (listener != null) {
            try {
                listener.accept(updated);
            } catch (Exception ignored) {
                // A disconnected SSE client must not abort publication.
            }
        }
    }

    private String generateCityEngineRequirements(String userMessage, String ragContext) {
        String prompt = promptResources.render("prompts/cityengine-requirements.txt", Map.of(
                "USER_REQUEST", userMessage == null ? "" : userMessage,
                "RAG_CONTEXT", ragContext == null ? "" : ragContext
        ));
        try {
            String raw = chatLanguageModel.generate(prompt);
            return extractJson(raw);
        } catch (Exception ex) {
            return "{\"maxBuildingHeight\":54,\"floorHeight\":3.0,\"setback\":0,\"lotCoverage\":0.75,\"facadeStyle\":\"modern\",\"roofType\":\"flat\",\"primaryColor\":\"#65d6c4\",\"exportFormats\":[\"slpk\",\"obj\"],\"designSummary\":\"使用默认规划参数生成建筑。\"}";
        }
    }
    private String synthesizePlanningDemo(Map<String, Object> result, String userMessage) {
        try {
            String prompt = promptResources.render("prompts/planning-synthesis.txt", Map.of(
                    "USER_REQUEST", userMessage == null ? "" : userMessage,
                    "RESULT_JSON", JSON.toJSONString(result)
            ));
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

    private AgentResult executeNavigation(
            NavigationRequest request,
            List<ExecutionTrace> trace,
            Consumer<ExecutionTrace> traceListener) {
        addTrace(trace, traceListener, 1, "decision", "识别地图定位请求",
                "目标地点：" + request.locationName(), "running");

        JSONObject aiParams = new JSONObject();
        aiParams.put("locationName", request.locationName());
        Map<String, Object> aiCandidate = asMap(invokeTool("aiGeocode", aiParams));
        String cityHint = getString(aiCandidate, "city", "");

        JSONObject amapParams = new JSONObject();
        amapParams.put("locationName", request.locationName());
        if (!cityHint.isBlank()) {
            amapParams.put("city", cityHint);
        }
        Map<String, Object> amapCandidate = asMap(invokeTool("geocodeWithCity", amapParams));
        Map<String, Object> geocode = hasValidCoordinate(amapCandidate) ? amapCandidate
                : hasValidCoordinate(aiCandidate) ? aiCandidate : null;
        if (geocode == null) {
            addTrace(trace, traceListener, 1, "error", "地点定位失败", "未返回有效坐标", "error");
            return new AgentResult("未能定位“" + request.locationName() + "”。请提供更完整的地点名称或城市。",
                    List.of("例如：北京市清华大学", "例如：上海市人民广场"), null, false,
                    null, List.of(), trace);
        }

        double lon = getDouble(geocode, "longitude", getDouble(geocode, "lon", Double.NaN));
        double lat = getDouble(geocode, "latitude", getDouble(geocode, "lat", Double.NaN));
        if (!isValidCoordinate(lon, lat) || !matchesCityHint(cityHint, lon, lat)) {
            addTrace(trace, traceListener, 1, "error", "地点坐标校验失败",
                    "定位结果未通过坐标校验", "error");
            return new AgentResult("“" + request.locationName() + "”的坐标未通过校验，未执行地图跳转。",
                    List.of("提供城市名称后重试"), null, false, null, List.of(), trace);
        }

        List<Map<String, Object>> commands = new ArrayList<>();
        collectCommands(geocode, commands);
        if (commands.isEmpty()) {
            commands.add(Map.of("action", "flyTo", "params", Map.of(
                    "longitude", lon, "latitude", lat, "zoom", 17)));
        }
        addTrace(trace, traceListener, 2, "complete", "地图定位完成",
                "已定位“" + request.locationName() + "”，正在调整三维视角", "success");
        return new AgentResult("已定位“" + request.locationName() + "”。", List.of(), null, false,
                null, dedupeCommands(commands), trace);
    }

    private AgentResult executeExplicitPlaceAnalysis(
            String userMessage,
            String userId,
            List<ExecutionTrace> trace,
            Consumer<ExecutionTrace> traceListener) {
        PlaceAnalysisRequest request = parsePlaceAnalysisRequest(userMessage);
        if (request == null) {
            return null;
        }

        addTrace(trace, traceListener, 1, "decision", "识别地点分析请求",
                "地点：" + request.locationName() + "；半径：" + request.radiusMeters() + " 米", "running");
        JSONObject aiGeocodeParams = new JSONObject();
        aiGeocodeParams.put("locationName", request.locationName());
        Map<String, Object> aiCandidate = asMap(invokeTool("aiGeocode", aiGeocodeParams));
        String cityHint = getString(aiCandidate, "city", "");

        JSONObject geocodeParams = new JSONObject();
        geocodeParams.put("locationName", request.locationName());
        if (!cityHint.isBlank()) {
            geocodeParams.put("city", cityHint);
        }
        // geocodeWithCity first uses an exact POI search and refuses ambiguous
        // same-name POIs before falling back to city-constrained address lookup.
        Map<String, Object> amapCandidate = asMap(invokeTool("geocodeWithCity", geocodeParams));
        Map<String, Object> geocode = null;
        if (hasValidCoordinate(amapCandidate)
                && !"inconsistent".equalsIgnoreCase(getString(amapCandidate, "verification", ""))) {
            geocode = amapCandidate;
            addTrace(trace, traceListener, 1, "observation", "地点定位完成",
                    "千问识别城市“" + safe(cityHint, "未提供") + "”，高德已返回受城市约束的坐标", "success");
        } else if (hasValidCoordinate(aiCandidate)) {
            addTrace(trace, traceListener, 1, "fallback", "地理编码降级",
                    cityHint.isBlank()
                            ? "高德 POI 搜索未返回唯一候选，采用千问地点语义坐标"
                            : "高德未返回可校验结果，采用千问地点语义坐标，并在结果中标记来源",
                    "running");
            geocode = aiCandidate;
        }
        if (geocode == null || isFailed(geocode)) {
            String detail = geocode == null ? "未返回地点坐标" : formatTraceDetail(geocode);
            addTrace(trace, traceListener, 1, "error", "地点定位失败", detail, "error");
            return new AgentResult(
                    "未能定位“" + request.locationName() + "”。请检查地点名称，或配置高德地理编码后重试。",
                    List.of("绘制 AOI 后分析", "上传建筑 GeoJSON"), null, false, null, List.of(), trace);
        }

        double lon = getDouble(geocode, "longitude", getDouble(geocode, "lon", Double.NaN));
        double lat = getDouble(geocode, "latitude", getDouble(geocode, "lat", Double.NaN));
        if (!isValidCoordinate(lon, lat)) {
            addTrace(trace, traceListener, 1, "error", "地点坐标无效", formatTraceDetail(geocode), "error");
            return new AgentResult(
                    "“" + request.locationName() + "”未返回有效坐标，无法开始空间分析。",
                    List.of("绘制 AOI 后分析", "提供经纬度坐标"), null, false, null, List.of(), trace);
        }
        if (!matchesCityHint(cityHint, lon, lat)) {
            String detail = "地点“" + request.locationName() + "”应位于" + cityHint
                    + "，但地理编码返回了范围外坐标：" + lon + ", " + lat;
            addTrace(trace, traceListener, 1, "error", "地点定位校验失败", detail, "error");
            return new AgentResult(
                    "已拒绝使用“" + request.locationName() + "”的异常定位结果，避免在错误城市执行分析。请重试或提供完整地址。",
                    List.of("提供“北京市清华大学”", "绘制 AOI 后分析"), null, false, null, List.of(), trace);
        }

        List<Map<String, Object>> commands = new ArrayList<>();
        collectCommands(geocode, commands);
        addTrace(trace, traceListener, 1, "observation", "地点坐标确认",
                "坐标：" + lon + ", " + lat + "；来源：" + getString(geocode, "source", "unknown"), "success");

        JSONObject analysisParams = new JSONObject();
        analysisParams.put("lon", lon);
        analysisParams.put("lat", lat);
        analysisParams.put("radius", request.radiusMeters());
        addTrace(trace, traceListener, 2, "action", "调用 analyzeArea",
                "以“" + request.locationName() + "”为中心分析 " + request.radiusMeters() + " 米范围", "running");
        Map<String, Object> analysis = asMap(invokeTool("analyzeArea", analysisParams));
        if (analysis == null || isFailed(analysis)) {
            addTrace(trace, traceListener, 2, "fallback", "切换前端场景建筑数据",
                    "OSM 建筑服务暂不可用；保持 " + request.radiusMeters()
                            + " 米范围，从当前 SceneLayer 提取建筑并回传服务端校验", "running");
            // The SceneLayer fallback needs the same explicit AOI as the
            // failed server-side request. Without this command, a flyTo-only
            // workflow has no buffer geometry to query on the frontend.
            commands.add(Map.of("action", "addBuffer", "params", Map.of(
                    "longitude", lon, "latitude", lat, "radius", request.radiusMeters())));
            commands.add(Map.of("action", "getScreenBuildings", "params", Map.of(
                    "longitude", lon, "latitude", lat, "radius", request.radiusMeters())));
            return new AgentResult(
                    "地点已定位，但在线建筑数据暂不可用；正在从当前三维场景提取建筑轮廓。",
                    List.of("等待场景建筑同步", "稍后重试在线数据"), null, false, null,
                    dedupeCommands(commands), trace);
        }
        collectCommands(analysis == null ? Map.of() : analysis, commands);
        addTrace(trace, traceListener, 2, "observation", "获得空间分析结果",
                formatTraceDetail(analysis), analysis != null && !isFailed(analysis) ? "success" : "error");

        if (analysis != null && isValidMetrics(analysis)) {
            Map<String, Object> metrics = validationLayer.validateMetrics(analysis);
            saveAnalysis(userId, userMessage, metrics);
            memoryStore.cleanupExpired();
            addTrace(trace, traceListener, 3, "complete", "地点分析完成", "已返回 GIS 真值指标与地图命令", "success");
            return new AgentResult(buildMetricReply(metrics), suggestionEngine.generateSuggestions(metrics), null,
                    false, metrics, dedupeCommands(commands), trace);
        }

        String detail = analysis == null ? "分析服务未返回结果" : formatTraceDetail(analysis);
        return new AgentResult("已定位“" + request.locationName() + "”，但未取得可用建筑指标：" + detail,
                List.of("扩大分析半径", "绘制 AOI 后分析"), null, false, null,
                dedupeCommands(commands), trace);
    }

    private PlaceAnalysisRequest parsePlaceAnalysisRequest(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return null;
        }
        String normalizedMessage = userMessage.trim().replaceFirst("^(?:基于)?当前", "");
        Matcher placeMatcher = Pattern.compile(
                "(?:分析|查询|查看|评估|统计|计算)?\\s*(.+?)(?:周边|附近|周围|半径|(?=\\d+(?:\\.\\d+)?\\s*(?:公里|千米|km|米|m))|范围)"
        ).matcher(normalizedMessage);
        if (!placeMatcher.find()) {
            return null;
        }
        // Natural Chinese requests often start with "对/针对某地…分析".
        // These are request prepositions, never part of the place name.
        String locationName = placeMatcher.group(1)
                .replaceFirst("^(?:请)?(?:针对|对于|对)\\s*", "")
                .replaceAll("^[：:，,。.!！?？\\s]+", "")
                .trim();
        if (locationName.isBlank() || locationName.contains("当前") || locationName.contains("红线") || locationName.contains("AOI")) {
            return null;
        }
        Matcher radiusMatcher = Pattern.compile("(?i)(\\d+(?:\\.\\d+)?)\\s*(公里|千米|km|米|m)").matcher(userMessage);
        if (!radiusMatcher.find()) {
            return null;
        }
        double radius = Double.parseDouble(radiusMatcher.group(1));
        String unit = radiusMatcher.group(2).toLowerCase();
        if (unit.contains("公里") || unit.contains("千米") || "km".equals(unit)) {
            radius *= 1000;
        }
        int radiusMeters = Math.max(50, Math.min((int) Math.round(radius), 5000));
        return new PlaceAnalysisRequest(locationName, radiusMeters);
    }

    private NavigationRequest parseNavigationRequest(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return null;
        }
        Matcher matcher = Pattern.compile(
                "^(?:\u8BF7|\u5E2E\u6211)?(?:\u5C06\u5730\u56FE)?"
                        + "(?:\u98DE\u5230|\u98DE\u5F80|\u5B9A\u4F4D\u5230|"
                        + "\u5BFC\u822A\u5230|\u524D\u5F80|\u53BB)\\s*(.+?)\\s*$"
        ).matcher(userMessage.trim());
        if (!matcher.matches()) {
            return null;
        }
        String locationName = matcher.group(1).replaceAll("[\u3002\uFF01!\uFF1F?]+$", "").trim();
        return locationName.isBlank() ? null : new NavigationRequest(locationName);
    }

    private boolean matchesCityHint(String cityHint, double lon, double lat) {
        if (cityHint == null || cityHint.isBlank()) {
            return true;
        }
        // Broad municipal bounds are intentionally used only as a wrong-city guard,
        // not as a replacement for authoritative geocoding.
        if ("北京市".equals(cityHint)) {
            return lon >= 115.7 && lon <= 117.4 && lat >= 39.4 && lat <= 41.1;
        }
        return true;
    }

    private boolean hasValidCoordinate(Map<String, Object> candidate) {
        if (candidate == null || isFailed(candidate)) {
            return false;
        }
        return isValidCoordinate(
                getDouble(candidate, "longitude", getDouble(candidate, "lon", Double.NaN)),
                getDouble(candidate, "latitude", getDouble(candidate, "lat", Double.NaN)));
    }

    private String getString(Map<?, ?> map, String key, String fallback) {
        if (map == null || map.get(key) == null) {
            return fallback;
        }
        String value = String.valueOf(map.get(key)).trim();
        return value.isBlank() || "null".equalsIgnoreCase(value) ? fallback : value;
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
            if ("generateDynamicTool".equals(action)) {
                // 受功能开关控制，避免模型在 /execute 关闭时仍能经 Agent 内部生成动态工具。
                if (dynamicCodeGenerator.isDisabled()) {
                    return Map.of("status", "Disabled",
                            "message", "动态工具生成已关闭（DYNAMIC_EXECUTION_ENABLED=false）", "tool", "generateDynamicTool");
                }
                String name = params.getString("name");
                String description = params.getString("description");
                String requirement = params.getString("requirement");
                JSONObject context = params.getJSONObject("context");
                DynamicCodeGenerator.CodeExecutionResult generated =
                        dynamicCodeGenerator.generateAndRegister(name, description, requirement, context);
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("status", generated.status());
                response.put("message", generated.message());
                response.put("result", generated.result());
                if (generated.registeredTool() != null) {
                    response.put("registered_tool", generated.registeredTool());
                }
                return response;
            }
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

    private Map<String, Object> extractPlanningMetrics(Map<String, Object> planningResult) {
        if (planningResult == null) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> current = asMap(planningResult.get("current"));
        Map<String, Object> nested = current == null ? null : asMap(current.get("metrics"));
        if (nested != null && !nested.isEmpty()) {
            return nested;
        }
        Map<String, Object> direct = asMap(planningResult.get("metrics"));
        return direct != null && !direct.isEmpty() ? direct : planningResult;
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

    private boolean isAdvancedAnalysis(Map<String, Object> result) {
        String type = String.valueOf(result.getOrDefault("analysis_type", ""));
        return "skyline".equalsIgnoreCase(type)
                || "sunlight".equalsIgnoreCase(type)
                || "advanced_analysis".equalsIgnoreCase(type);
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
            String prompt = promptResources.render("prompts/metric-synthesis.txt", Map.of(
                    "USER_REQUEST", userMessage == null ? "" : userMessage,
                    "METRICS_JSON", JSON.toJSONString(metrics)
            ));
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

    private String buildAdvancedReply(Map<String, Object> result) {
        String type = String.valueOf(result.getOrDefault("analysis_type", "advanced"));
        if ("skyline".equalsIgnoreCase(type)) {
            return String.format(
                    "已完成天际线形态筛查：分析了 %d 栋建筑，最高建筑约 %.1f 米，平均高度约 %.1f 米。结果为基于建筑中心点和属性高度的方向剖面，不包含地形遮挡。",
                    getInt(result, "building_count", 0),
                    getDouble(result, "max_height", 0),
                    getDouble(result, "mean_height", 0));
        }
        return String.format(
                "已完成日照/阴影筛查：采样 %d 个时段，日照高度合格时段约 %.0f%%，最大估算阴影长度约 %.1f 米。结果用于方案比选，不替代法定日照审查。",
                getInt(result, "sample_count", 0),
                getDouble(result, "sunlight_window_percent", 0),
                getDouble(result, "max_shadow_length_m", 0));
    }

    private void addTrace(
            List<ExecutionTrace> trace,
            Consumer<ExecutionTrace> listener,
            int round,
            String phase,
            String title,
            String detail,
            String status) {
        ExecutionTrace event = new ExecutionTrace(
                round,
                phase,
                title == null ? "" : title,
                detail == null ? "" : detail,
                status == null ? "running" : status
        );
        trace.add(event);
        if (listener != null) {
            try {
                listener.accept(event);
            } catch (Exception ignored) {
                // A disconnected SSE client must not abort the GIS task.
            }
        }
    }

    private String summarizeDecision(String action, String summary) {
        if (summary != null && !summary.isBlank() && !summary.equalsIgnoreCase("null")) {
            return summary.length() > 180 ? summary.substring(0, 180) + "…" : summary;
        }
        return "选择受控工具：" + action;
    }

    private String summarizeParams(String action, JSONObject params) {
        if (params == null || params.isEmpty()) {
            return "无额外参数";
        }
        List<String> keys = new ArrayList<>();
        for (String key : params.keySet()) {
            if ("context".equalsIgnoreCase(key) || "geoJson".equalsIgnoreCase(key)
                    || "buildings".equalsIgnoreCase(key) || "aoi".equalsIgnoreCase(key)) {
                continue;
            }
            keys.add(key + "=" + String.valueOf(params.get(key)));
        }
        return keys.isEmpty() ? "已提供空间上下文" : String.join("，", keys);
    }

    private String formatTraceDetail(Object result) {
        if (result == null) return "无返回值";
        Map<String, Object> map = asMap(result);
        String detail;
        if (map == null) {
            detail = String.valueOf(result);
        } else {
            List<String> fields = new ArrayList<>();
            for (String key : List.of("status", "stage", "analysis_type", "building_count", "far",
                    "max_height", "mean_height", "sample_count", "sunlight_window_percent",
                    "max_shadow_length_m", "registered_tool", "message")) {
                if (map.containsKey(key)) fields.add(key + "=" + map.get(key));
            }
            detail = fields.isEmpty() ? "工具已返回结构化结果" : String.join("，", fields);
        }
        detail = detail.replaceAll("\\s+", " ").trim();
        return detail.length() > 240 ? detail.substring(0, 240) + "…" : detail;
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
            List<Map<String, Object>> commands,
            List<ExecutionTrace> trace
    ) {}

    public record ExecutionTrace(
            int round,
            String phase,
            String title,
            String detail,
            String status
    ) {}

    private record PlaceAnalysisRequest(String locationName, int radiusMeters) {}
    private record NavigationRequest(String locationName) {}
}
