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
import org.example.service.PendingAnalysisIntentService;
import org.example.spatial.SpatialPlanService;
import org.example.spatial.AnalysisPlanCompiler;
import org.example.spatial.SpatialPlanValidator;
import org.example.spatial.SpatialCapabilityCatalog;
import org.example.spatial.SpatialWorkflowService;
import org.example.spatial.PendingSpatialWorkflowService;
import org.example.spatial.SpatialResultQualityService;
import org.example.spatial.GeoDataDiscoveryAgent;
import org.example.spatial.LlmSpatialRouter;
import org.example.tools.pyGisTools;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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

    @Autowired
    private PendingAnalysisIntentService pendingAnalysisIntentService;

    @Autowired
    private SpatialPlanService spatialPlanService;

    @Autowired
    private AnalysisPlanCompiler analysisPlanCompiler;

    @Autowired
    private SpatialCapabilityCatalog spatialCapabilityCatalog;

    @Autowired
    private SpatialWorkflowService spatialWorkflowService;

    @Autowired
    private PendingSpatialWorkflowService pendingSpatialWorkflowService;

    @Autowired
    private SpatialResultQualityService spatialResultQualityService;

    @Autowired
    private GeoDataDiscoveryAgent geoDataDiscoveryAgent;
    @Autowired
    private LlmSpatialRouter llmSpatialRouter;
    @Autowired
    private IntentClassifier intentClassifier;
    @Value("${agent.llm-routing.enabled:true}") private boolean llmRoutingEnabled;

    private final AgentDecisionParser decisionParser = new AgentDecisionParser();
    private static final Pattern RAINFALL_MM = Pattern.compile("(?i)(\\d+(?:\\.\\d+)?)\\s*(?:mm|毫米)\\b");
    private static final Pattern DURATION_HOURS = Pattern.compile("(?i)(\\d+(?:\\.\\d+)?)\\s*(?:h|hr|hrs|hour|hours|小时|时)\\b");
    private static final Pattern RETURN_PERIOD_YEARS = Pattern.compile("(?i)(\\d{1,4})\\s*(?:years?|yrs?|yr|年(?:一遇)?)");

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
        AgentResult rainfallUpdate = captureRainfallScenario(userMessage, trace, traceListener);
        if (rainfallUpdate != null) {
            return rainfallUpdate;
        }
        if (isParameterQuestion(userMessage)) {
            return answerParameterQuestion(userMessage, memoryId, trace, traceListener);
        }
        if (!isSpatialQuestion(userMessage)) {
            return answerGeneralQuestion(userMessage, trace, traceListener);
        }
        if (isCurrentContextPlanningRequest(userMessage)) {
            return executePlanningDemo(userMessage, userId,
                    new TaskPlan(Intent.MODEL, Subject.CURRENT_CONTEXT, null), trace, traceListener);
        }
        if (llmRoutingEnabled) { AgentResult routed = executeLlmSpatialRoute(userMessage, userId, memoryId, trace, traceListener); if (routed != null) return routed; }
        if (!llmRoutingEnabled) {
            AgentResult confirmedImport = executeConfirmedDataImport(userMessage, trace, traceListener);
            if (confirmedImport != null) return confirmedImport;
            AgentResult dataDiscovery = executeDataDiscovery(userMessage, trace, traceListener);
            if (dataDiscovery != null) return dataDiscovery;
        }
        AgentResult collectedWorkflowData = collectPendingWorkflowData(userMessage, userId, memoryId, trace, traceListener);
        if (collectedWorkflowData != null) return collectedWorkflowData;
        AgentResult resumedWorkflow = resumePendingWorkflow(trace, traceListener);
        if (resumedWorkflow != null) return resumedWorkflow;
        AgentResult collectedData = collectPendingCapabilityData(userMessage, userId, memoryId, trace, traceListener);
        if (collectedData != null) return collectedData;
        AgentResult revisedData = reviseCurrentCapabilityData(userMessage, userId, memoryId, trace, traceListener);
        if (revisedData != null) return revisedData;
        if (!llmRoutingEnabled) { AgentResult workflow = executeSpatialWorkflow(userMessage, memoryId, trace, traceListener); if (workflow != null) return workflow; }
        String capabilityId = llmRoutingEnabled ? null : analysisPlanCompiler.compile(userMessage)
                .map(AnalysisPlanCompiler.Compilation::capabilityId)
                .orElse(null);
        if (capabilityId != null) {
            AgentResult inlineData = collectInlineCapabilityData(capabilityId, userMessage, userId, memoryId, trace, traceListener);
            if (inlineData != null) return inlineData;
        }
        if (capabilityId == null && isCurrentRangeReply(userMessage)) {
            capabilityId = normalizePendingCapability(pendingAnalysisIntentService.consume(memoryId));
        }
        if (capabilityId != null) {
            return executeGraphPlan(capabilityId, userMessage, userId, memoryId, trace, traceListener);
        }
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

        if (isFloodAnalysisRequest(userMessage)) {
            return executeFloodAnalysisRequest(trace, traceListener);
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

        // The frontend uses this fixed instruction immediately after a
        // hand-drawn AOI has been synchronized.  It is deterministic GIS
        // work, not a planning decision: do not let a malformed LLM JSON
        // response prevent the already-uploaded redline from being analysed.
        if (isCurrentContextMetricsRequest(userMessage)) {
            return executeCurrentContextMetrics(userMessage, userId, trace, traceListener);
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        String systemPrompt = promptResources.load("system.txt")
                + "\n\n当前运行时工具清单：\n"
                + JSON.toJSONString(toolRegistry.getToolDescriptors());
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
        String terminalFailure = null;

        int rounds = Math.max(1, maxRounds);
        int lastRound = 0;
        for (int round = 1; round <= rounds; round++) {
            lastRound = round;
            JSONObject decision = askModelForDecision(messages);
            if (decision == null) {
                addTrace(trace, traceListener, round, "error", "无法解析模型决策", "本轮没有得到有效 JSON", "error");
                terminalFailure = "planner_invalid_json";
                break;
            }

            String action = decision.getString("action");
            String summary = decision.getString("summary");
            if (action == null || action.isBlank()) {
                addTrace(trace, traceListener, round, "error", "决策缺少动作", "模型返回了空 action", "error");
                terminalFailure = "planner_missing_action";
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
            boolean capabilityPending = resultMap != null && isCapabilityPending(resultMap);

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
                    observationFailed ? "error" : capabilityPending ? "waiting" : "success");
            if (capabilityPending) {
                String detail = String.valueOf(resultMap.getOrDefault("message", "当前系统尚未配置该分析能力。"));
                addTrace(trace, traceListener, round, "wait", "分析能力待配置", detail, "waiting");
                finalReply = detail;
                finalSuggestions = List.of("保留当前 AOI", "补充该分析所需数据", "查看当前可用分析能力");
                break;
            }
            messages.add(Map.of("role", "assistant", "content",
                    safe(summary, "选择 " + action)));
            messages.add(Map.of("role", "user", "content",
                    "Tool " + action + " returned:\n" + observation));
        }

        if (finalReply == null) {
            if (terminalFailure != null) {
                finalReply = "Agent 未能生成可执行的工具决策，未执行空间分析。请重试；若问题持续存在，请检查模型 JSON 输出配置。";
            } else if (finalMetrics != null && isAdvancedAnalysis(finalMetrics)) {
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
        addTrace(trace, traceListener, Math.max(1, lastRound),
                terminalFailure == null ? "complete" : "error",
                terminalFailure == null ? "任务结束" : "任务失败",
                terminalFailure == null ? "已返回结果和可执行地图命令" : "模型决策协议错误: " + terminalFailure,
                terminalFailure == null ? "success" : "error");

        return new AgentResult(finalReply, finalSuggestions, null, false,
                finalMetrics, dedupeCommands(pendingCommands), trace,
                terminalFailure == null ? Map.of("status", "Success")
                        : Map.of("status", "Error", "code", terminalFailure));
    }

    private boolean isSpatialQuestion(String message) {
        return intentClassifier.classify(message) == IntentClassifier.Intent.SPATIAL_ANALYSIS;
    }

    private static final List<String> RAINFALL_INTENT_WORDS = List.of(
            "降雨", "降水", "雨量", "毫米", "mm", "小时", "历时", "重现期", "年一遇",
            "rainfall", "hour", "flood", "洪水", "内涝", "淹没");

    private AgentResult captureRainfallScenario(
            String message, List<ExecutionTrace> trace, Consumer<ExecutionTrace> listener) {
        if (message == null || message.isBlank()) return null;
        Matcher rainfall = RAINFALL_MM.matcher(message);
        Matcher duration = DURATION_HOURS.matcher(message);
        Matcher period = RETURN_PERIOD_YEARS.matcher(message);
        String lower = message.toLowerCase();
        boolean regexHit = rainfall.find() || duration.find() || period.find();
        boolean rainfallIntent = RAINFALL_INTENT_WORDS.stream().anyMatch(lower::contains);
        if (!regexHit && !rainfallIntent) return null;

        JSONObject existing = contextObject("rainfall_scenario");
        boolean revising = existing != null && !existing.isEmpty();
        // fresh 只含本次消息明确提到的数值，避免把会话里残留的旧情景当作"这次说的"
        JSONObject fresh = new JSONObject();
        if (rainfall.reset().find()) fresh.put("rainfallMm", Double.parseDouble(rainfall.group(1)));
        if (duration.reset().find()) fresh.put("durationHours", Double.parseDouble(duration.group(1)));
        if (period.reset().find()) fresh.put("returnPeriodYears", Integer.parseInt(period.group(1)));
        boolean gotNewValue = !fresh.isEmpty();

        // 消息明显在描述降雨情景，但正则未能完整解析（中文说法、复合句等）
        // → 用 LLM 语义抽取补全，正则作为快速路径/兜底。
        boolean complete = fresh.containsKey("rainfallMm")
                && fresh.containsKey("durationHours")
                && fresh.containsKey("returnPeriodYears");
        if (rainfallIntent && !complete) {
            JSONObject llmValues = extractRainfallByLlm(message);
            if (llmValues != null) {
                llmValues.forEach(fresh::put);
                gotNewValue = true;
            }
        }
        // 消息里没有任何降雨数值（如用户只说"执行洪水分析"）时不得拦截：
        // 会话里残留的旧情景不能当作"这次说的"重新记录。
        if (!gotNewValue) return null;
        JSONObject values = existing == null ? new JSONObject() : existing;
        fresh.forEach(values::put);
        values.put("source", revising ? "conversation_user_revised" : "conversation_user_provided");
        values.put("name", applyNameTemplate("{returnPeriodYears}yr-{durationHours}h-{rainfallMm}mm", values));
        gisContextService.saveGeoJson(JSON.toJSONString(Map.of("rainfall_scenario", values)));
        addTrace(trace, listener, 1, "chat", "已记录降雨情景", values.toJSONString(), "success");
        AgentResult resumedWorkflow = resumePendingWorkflow(trace, listener);
        if (resumedWorkflow != null) {
            return resumedWorkflow;
        }
        String reply = "已记录这组降雨情景：" + values.get("rainfallMm") + " mm、"
                + values.get("durationHours") + " 小时、" + values.get("returnPeriodYears")
                + " 年一遇。现在只更新会话参数，不会自动执行洪水分析。";
        return new AgentResult(reply, List.of(), null, false, null, List.of(), trace,
                Map.of("status", "Success", "mode", "rainfall_parameter_update"));
    }

    /**
     * LLM 语义抽取降雨情景参数：理解任意中文/英文表达（含复合句），
     * 输出经范围校验的 JSON。任何失败都返回 null，由正则路径兜底。
     */
    private JSONObject extractRainfallByLlm(String message) {
        String prompt = "你是降雨情景参数抽取器。从用户消息中提取三个参数："
                + "rainfallMm（降雨量，毫米）、durationHours（降雨历时，小时）、returnPeriodYears（重现期，年）。"
                + "规则：只输出一个 JSON 对象，不要输出任何其他文字或 Markdown；"
                + "消息中没有明确提到的字段必须为 null，绝对不要猜测或推断数值；"
                + "数值使用数字类型。用户消息：" + message;
        try {
            String raw = chatLanguageModel.generate(prompt);
            if (raw == null || raw.isBlank()) return null;
            String trimmed = raw.trim();
            if (trimmed.startsWith("```")) {
                int firstLine = trimmed.indexOf('\n');
                if (firstLine >= 0) trimmed = trimmed.substring(firstLine + 1);
                if (trimmed.endsWith("```")) trimmed = trimmed.substring(0, trimmed.length() - 3);
                trimmed = trimmed.trim();
            }
            JSONObject parsed = JSON.parseObject(trimmed);
            if (parsed == null) return null;
            JSONObject result = new JSONObject();
            Double mm = parsed.getDouble("rainfallMm");
            if (mm != null && mm > 0 && mm <= 500) result.put("rainfallMm", mm);
            Double hours = parsed.getDouble("durationHours");
            if (hours != null && hours > 0 && hours <= 720) result.put("durationHours", hours);
            Integer years = parsed.getInteger("returnPeriodYears");
            if (years != null && years >= 1 && years <= 1000) result.put("returnPeriodYears", years);
            return result.isEmpty() ? null : result;
        } catch (RuntimeException error) {
            return null;
        }
    }

    /**
     * 规划前后对比：现状指标（规划前） vs CityEngine 方案参数（规划后）。
     * 让评审能直观看到规划带来的差异。
     */
    private String buildPlanningComparison(Map<String, Object> beforeMetrics, Map<String, Object> planningResult) {
        StringBuilder sb = new StringBuilder("\n\n【规划前后对比】");
        int count = getInt(beforeMetrics, "building_count", 0);
        double meanH = getDouble(beforeMetrics, "mean_height", getDouble(beforeMetrics, "avgHeightM", 0));
        double far = getDouble(beforeMetrics, "far", 0);
        if (count > 0 || meanH > 0 || far > 0) {
            sb.append("\n规划前（现状）：");
            if (count > 0) sb.append(count).append(" 栋建筑");
            if (meanH > 0) sb.append("，平均高度约 ").append(Math.round(meanH)).append(" 米");
            if (far > 0) sb.append("，现状容积率 ").append(String.format(Locale.ROOT, "%.2f", far));
        }
        Map<String, Object> requirements = asMap(planningResult == null ? null : planningResult.get("requirements"));
        if (requirements != null) {
            sb.append("\n规划后（方案）：");
            Object maxH = requirements.get("maxBuildingHeight");
            if (maxH != null) sb.append("目标最高 ").append(maxH).append(" 米");
            Object floors = requirements.get("floorHeight");
            if (floors != null) sb.append("，层高 ").append(floors).append(" 米");
            Object lot = requirements.get("lotCoverage");
            if (lot != null) sb.append("，地块覆盖率 ").append(lot);
            Object setback = requirements.get("setback");
            if (setback != null) sb.append("，退界 ").append(setback).append(" 米");
            Object roof = requirements.get("roofType");
            if (roof != null) sb.append("，屋顶 ").append(roof);
        }
        if (sb.toString().trim().endsWith("【规划前后对比】")) return "";
        return sb.toString();
    }

    private boolean isParameterQuestion(String message) {        if (message == null || message.isBlank()) return false;
        String value = message.toLowerCase();
        boolean asksAboutSettings = List.of("参数", "配置", "默认值", "阈值", "输入要求", "需要什么数据", "怎么设置", "取值范围")
                .stream().anyMatch(value::contains);
        boolean asksToRun = List.of("执行", "运行", "计算", "开始分析", "进行分析", "做分析", "开始洪水", "设置为", "改为", "调整为")
                .stream().anyMatch(value::contains);
        return asksAboutSettings && !asksToRun;
    }

    private AgentResult answerParameterQuestion(
            String message, String memoryId, List<ExecutionTrace> trace, Consumer<ExecutionTrace> listener) {
        String value = message == null ? "" : message.toLowerCase();
        String context = currentParameterSnapshot(memoryId, value);
        String prompt = "你是 GISAgent 的参数问答助手。用户只是在询问参数，不要执行任何空间分析，不要返回 JSON 或编号清单。"
                + "请根据当前会话内存快照，用自然中文回答；区分‘当前内存实际值’与‘系统默认/约束’。"
                + "如果内存没有该值，明确说当前没有，不要编造。\n会话内存快照：\n" + context
                + "\n用户问题：" + (message == null ? "" : message);
        String reply = null;
        try { reply = chatLanguageModel.generate(prompt); } catch (RuntimeException ignored) { }
        if (reply == null || reply.isBlank()) {
            reply = "当前会话参数快照如下：\n" + context;
        }
        addTrace(trace, listener, 1, "chat", "参数说明", "只读取配置，不执行空间分析", "success");
        return new AgentResult(reply.trim(), List.of(), null, false, null, List.of(), trace,
                Map.of("status", "Success", "mode", "parameter_help"));
    }

    private String currentParameterSnapshot(String memoryId, String question) {
        try {
            JSONObject context = JSON.parseObject(gisContextService.getGeoJson(memoryId));
            if (context == null) context = new JSONObject();
            StringBuilder snapshot = new StringBuilder();
            snapshot.append("contextVersion=").append(context.getLongValue("contextVersion"));
            snapshot.append("; hasAoi=").append(context.containsKey("aoi"));
            snapshot.append("; hasDem=").append(context.containsKey("dem"));
            Object rainfall = context.get("rainfall_scenario");
            snapshot.append("; rainfall_scenario=").append(rainfall == null ? "当前内存没有" : JSON.toJSONString(rainfall));
            if (question.contains("洪水") || question.contains("内涝") || question.contains("降雨")
                    || question.contains("降水") || question.contains("重现期") || question.contains("flood")) {
                snapshot.append("; systemDefaults=rainfallMm 必须大于0且建议不超过500mm，returnPeriodYears 默认20年且允许1-1000年，durationHours建议填写；当前模型为Priority-Flood+D8径流筛查");
            }
            return snapshot.toString();
        } catch (RuntimeException error) {
            return "当前内存暂不可读；系统默认值：rainfallMm>0，returnPeriodYears默认20年，允许1-1000年。";
        }
    }

    private AgentResult answerGeneralQuestion(
            String message, List<ExecutionTrace> trace, Consumer<ExecutionTrace> listener) {
        addTrace(trace, listener, 1, "chat", "普通问答", "不调用空间分析工具", "running");
        String prompt = """
                你是 GISAgent 的本地助手。回答普通问答，不调用工具，不要求 JSON。
                对系统参数、使用方法、功能说明的问题，简洁说明：当前可用空间能力包括 AOI、建筑指标、天际线、日照、DEM/内涝、选址、方案比选和三维成果；具体运行参数以界面、环境配置和工具结果为准。不要编造未提供的配置值。
                用户问题：
                """ + (message == null ? "" : message);
        try {
            String reply = chatLanguageModel.generate(prompt);
            if (reply != null && !reply.isBlank()) {
                addTrace(trace, listener, 1, "complete", "普通问答完成", "已生成回答", "success");
                return new AgentResult(reply.trim(), List.of(), null, false, null, List.of(), trace,
                        Map.of("status", "Success", "mode", "chat"));
            }
        } catch (RuntimeException ignored) {
            // Keep a usable local reply when the chat model is temporarily unavailable.
        }
        String fallback = "当前可用功能包括 AOI、建筑指标、天际线、日照、DEM/内涝、选址、方案比选和三维成果。"
                + "具体运行参数请查看界面状态、.env 配置或对应分析结果。";
        addTrace(trace, listener, 1, "complete", "普通问答降级完成", "模型不可用，返回本地说明", "success");
        return new AgentResult(fallback, List.of(), null, false, null, List.of(), trace,
                Map.of("status", "Success", "mode", "chat_fallback"));
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
        boolean cityEngineTarget = lower.contains("cityengine") || lower.contains("geoscene") || lower.contains("slpk");
        boolean modelAction = lower.contains("publish") || lower.contains("generate")
                || userMessage.contains("三维") || userMessage.contains("建模") || userMessage.contains("发布");
        return cityEngineTarget && modelAction;
    }

    private boolean isFloodAnalysisRequest(String userMessage) {
        if (userMessage == null) return false;
        String lower = userMessage.toLowerCase();
        return userMessage.contains("洪水") || userMessage.contains("内涝") || userMessage.contains("淹没")
                || lower.contains("flood") || lower.contains("inundation");
    }

    private String normalizePendingCapability(String pending) {
        if (pending == null || pending.isBlank()) return null;
        return switch (pending) {
            case "skyline" -> "skyline_analysis";
            case "sunlight" -> "sunlight_analysis";
            default -> pending;
        };
    }

    private AgentResult executeLlmSpatialRoute(String message, String userId, String memoryId, List<ExecutionTrace> trace, Consumer<ExecutionTrace> listener) {
        LlmSpatialRouter.Route route = llmSpatialRouter.route(message);
        if (route == null || "none".equals(route.kind())) return null;
        if ("unavailable".equals(route.kind())) {
            addTrace(trace, listener, 1, "llm_plan", "LLM 空间路由不可用，回退常规流程", route.diagnostic(), "waiting");
            return null;
        }
        addTrace(trace, listener, 1, "llm_plan", "LLM 选择空间计划", "kind=" + route.kind(), "success");
        if ("discovery".equals(route.kind())) return executeDataDiscovery(route.datasets(), trace, listener);
        if ("import_osm".equals(route.kind())) return executeOsmImport(route.dataset(), trace, listener);
        if ("ground_dem".equals(route.kind())) {
            if (route.location() != null) {
                Map<String, Object> boundary = asMap(invokeTool("resolveAdministrativeBoundary",
                        new JSONObject(Map.of("locationName", route.location()))));
                Map<String, Object> aoi = boundary == null ? null : asMap(boundary.get("aoi"));
                if (boundary == null || isFailed(boundary) || aoi == null || aoi.isEmpty()) {
                    addTrace(trace, listener, 2, "clarification", "行政区边界不可用",
                            "location=" + route.location() + "；" + formatTraceDetail(boundary), "waiting");
                    return new AgentResult(null, null, "未能取得“" + route.location() + "”的可用行政区边界，因此不会用中心点或当前地图范围替代。请稍后重试或上传该区域边界。", true, null,
                            List.of(), trace, Map.of("status", "NeedsClarification", "analysisType", "ground_dem_request", "targetLocation", route.location()));
                }
                addTrace(trace, listener, 2, "command", "获取行政区边界", "location=" + route.location() + "；source=" + getString(boundary, "source", "unknown"), "success");
                List<Map<String, Object>> commands = List.of(
                        Map.of("action", "setAoi", "params", Map.of("aoi", aoi, "title", route.location() + " 行政区范围")),
                        Map.of("action", "requestPublicDem", "params", Map.of()));
                return new AgentResult("已获取“" + route.location() + "”行政区边界，正在下载、拼接并裁剪覆盖该范围的公共 GeoTIFF DEM。", null, null, false, null,
                        commands, trace, Map.of("status", "Success", "analysisType", "ground_dem_request", "targetLocation", route.location(), "source", getString(boundary, "source", "unknown")));
            }
            addTrace(trace, listener, 2, "command", "请求 ArcGIS World Elevation 高程", "当前 AOI 的规则网格采样", "running");
            return new AgentResult("已请求从 ArcGIS World Elevation 对当前 AOI 采样高程点。采样完成后会写入当前会话；该数据用于初步地形筛查，不等同于原始 DEM 栅格。", null, null, false, null,
                    List.of(Map.of("action", "requestGroundDem", "params", Map.of())), trace,
                    Map.of("status", "Success", "analysisType", "ground_dem_request"));
        }
        if (!"analysis".equals(route.kind())) return null;
        if (route.location() != null) {
            Map<String, Object> boundary = asMap(invokeTool("resolveAdministrativeBoundary",
                    new JSONObject(Map.of("locationName", route.location()))));
            Map<String, Object> aoi = boundary == null ? null : asMap(boundary.get("aoi"));
            if (boundary == null || isFailed(boundary) || aoi == null || aoi.isEmpty()) {
                return new AgentResult(null, null, "未能获取“" + route.location() + "”的行政区边界，未沿用上一地区的数据。", true, null,
                        List.of(), trace, Map.of("status", "NeedsClarification", "targetLocation", route.location()));
            }
            addTrace(trace, listener, 2, "command", "切换分析区域", "location=" + route.location(), "success");
            return new AgentResult("已切换至“" + route.location() + "”，正在下载该区域 GeoTIFF DEM，完成后将复用当前降雨情景继续洪水分析。", null, null, false, null,
                    List.of(Map.of("action", "setAoi", "params", Map.of("aoi", aoi, "title", route.location() + " 行政区范围")),
                            Map.of("action", "requestPublicDem", "params", Map.of("resumeMessage", "洪水分析"))),
                    trace, Map.of("status", "Success", "targetLocation", route.location(), "analysisType", "area_switch"));
        }
        if (route.capabilityIds().size() > 1) {
            SpatialWorkflowService.WorkflowResult result = spatialWorkflowService.executeCapabilities(message, route.capabilityIds());
            if (result.needsClarification()) {
                pendingSpatialWorkflowService.remember(message, result.capabilityIds());
                String question="综合分析缺少："+String.join("、",result.missingData())+"。";
                return new AgentResult(null,null,question,true,null,List.of(),trace,Map.of("status","NeedsClarification","analysisType","spatial_workflow","missingData",result.missingData(),"capabilityIds",result.capabilityIds(),"dataRecommendations",result.dataRecommendations()));
            }
            return workflowResultToAgentResult(result, trace, listener);
        }
        String capabilityId=route.capabilityIds().get(0); AgentResult inline=collectInlineCapabilityData(capabilityId,message,userId,memoryId,trace,listener);
        return inline != null ? inline : executeGraphPlan(capabilityId,message,userId,memoryId,trace,listener);
    }

    private AgentResult executeDataDiscovery(String message, List<ExecutionTrace> trace, Consumer<ExecutionTrace> listener) {
        List<String> datasets = discoveryDatasets(message);
        if (datasets.isEmpty() || !isDiscoveryRequest(message)) return null;
        return executeDataDiscovery(datasets, trace, listener);
    }
    private AgentResult executeDataDiscovery(List<String> datasets, List<ExecutionTrace> trace, Consumer<ExecutionTrace> listener) {
        addTrace(trace, listener, 1, "discovery", "检索真实地理数据", "datasets=" + String.join(",", datasets), "running");
        Map<String, Object> found = geoDataDiscoveryAgent.discover(datasets);
        List<?> candidates = (List<?>) found.getOrDefault("candidates", List.of());
        String reply = "已检索 " + String.join("、", datasets) + " 候选数据，共 " + candidates.size()
                + " 条。候选仅供查看；输入“确认导入 OSM 建筑/道路/水系”才会写入当前会话。";
        addTrace(trace, listener, 2, "complete", "数据候选已返回", "candidateCount=" + candidates.size(), "success");
        return new AgentResult(reply, List.of("确认导入 OSM 建筑", "确认导入 OSM 水系"), null, false, found, List.of(), trace,
                Map.of("status", "Success", "analysisType", "data_discovery", "dataDiscovery", found));
    }

    private AgentResult executeConfirmedDataImport(String message, List<ExecutionTrace> trace, Consumer<ExecutionTrace> listener) {
        String dataset = confirmedOsmDataset(message); if (dataset == null) return null;
        return executeOsmImport(dataset, trace, listener);
    }
    private AgentResult executeOsmImport(String dataset, List<ExecutionTrace> trace, Consumer<ExecutionTrace> listener) {
        addTrace(trace, listener, 1, "import", "确认导入 OSM 数据", "dataset=" + dataset, "running");
        try {
            JSONObject data = geoDataDiscoveryAgent.importOsm(dataset);
            String target = "roads".equals(dataset) ? "road_network" : "waterways".equals(dataset) ? "river_network" : "buildings";
            gisContextService.saveGeoJson(JSON.toJSONString(Map.of(target, data)));
            int count = data.getJSONArray("features").size();
            addTrace(trace, listener, 2, "complete", "OSM 数据已导入", "featureCount=" + count, "success");
            return new AgentResult("已导入 OSM " + dataset + "，共 " + count + " 个要素。数据已进入当前会话，等待你的分析请求。", List.of("执行建筑指标分析"), null,
                    false, Map.of("source", "osm_overpass", "dataset", target, "featureCount", count), List.of(), trace,
                    Map.of("status", "Success", "analysisType", "data_import", "source", "osm_overpass", "dataset", target));
        } catch (IllegalArgumentException error) {
            JSONObject existingBuildings = contextObject("buildings");
            int existingBuildingCount = existingBuildings == null || existingBuildings.getJSONArray("features") == null
                    ? 0 : existingBuildings.getJSONArray("features").size();
            String detail = "OSM 导入失败：" + error.getMessage();
            addTrace(trace, listener, 2, "error", "OSM 数据未导入", detail, "error");
            if (existingBuildingCount > 0) {
                return new AgentResult(detail + "。当前会话已有 " + existingBuildingCount
                                + " 个建筑要素，未被本次失败覆盖；可直接执行建筑指标分析，或稍后重试 OSM 导入。",
                        List.of("执行建筑指标分析", "重试导入 OSM 建筑"), null, false, null, List.of(), trace,
                        Map.of("status", "PartialSuccess", "code", error.getMessage(),
                                "source", "current_context", "buildingCount", existingBuildingCount));
            }
            return new AgentResult(null, null, detail, true, null, List.of(), trace,
                    Map.of("status", "NeedsClarification", "code", error.getMessage()));
        }
    }

    private boolean isDiscoveryRequest(String message) {
        if (message == null) return false; String lower = message.toLowerCase();
        return message.contains("检索") || message.contains("搜索") || message.contains("查找") || lower.contains("search") || lower.contains("discover");
    }
    private List<String> discoveryDatasets(String message) {
        if (message == null) return List.of(); String lower=message.toLowerCase(); List<String> values=new ArrayList<>();
        if (message.contains("建筑") || lower.contains("building")) values.add("buildings");
        if (message.contains("道路") || lower.contains("road")) values.add("roads");
        if (message.contains("水系") || message.contains("河网") || lower.contains("water")) values.add("waterways");
        if (message.contains("高程") || message.contains("DEM") || lower.contains("dem")) values.add("dem");
        return values;
    }
    private String confirmedOsmDataset(String message) {
        if (message == null || !message.toLowerCase().contains("osm") || !(message.contains("确认") || message.toLowerCase().contains("confirm"))) return null;
        if (message.contains("建筑")) return "buildings"; if (message.contains("道路")) return "roads"; if (message.contains("水系") || message.contains("河网")) return "waterways"; return null;
    }

    private AgentResult executeSpatialWorkflow(String userMessage, String memoryId, List<ExecutionTrace> trace,
                                               Consumer<ExecutionTrace> traceListener) {
        SpatialWorkflowService.WorkflowResult workflow = spatialWorkflowService.execute(userMessage);
        if (workflow == null) return null;
        addTrace(trace, traceListener, 1, "workflow", "编排综合空间分析",
                "capabilities=" + String.join(",", workflow.capabilityIds()), "running");
        if (workflow.needsClarification()) {
            pendingSpatialWorkflowService.remember(userMessage, workflow.capabilityIds());
            String question = "综合分析还缺少：" + String.join("、", workflow.missingData()) + "。补齐后将依次执行："
                    + String.join("、", workflow.capabilityIds()) + "。";
            addTrace(trace, traceListener, 2, "ask", "汇总数据缺失", question, "waiting");
            return new AgentResult(null, null, question, true, null, List.of(), trace,
                    Map.of("status", "NeedsClarification", "code", "workflow_data_required", "analysisType", "spatial_workflow",
                            "capabilityIds", workflow.capabilityIds(), "missingData", workflow.missingData(), "dataRecommendations", workflow.dataRecommendations(), "provenance", workflow.provenance()));
        }
        pendingSpatialWorkflowService.clear();
        for (Map<String, Object> result : workflow.results()) {
            addTrace(trace, traceListener, 2, "observation", "完成 " + result.get("capabilityId"),
                    "tool=" + result.get("tool") + ", status=" + result.get("status"), "Success".equals(result.get("status")) ? "success" : "error");
        }
        addTrace(trace, traceListener, 3, "complete", "综合空间分析完成", "已汇总 " + workflow.results().size() + " 个子分析", "success");
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("analysis_type", "spatial_workflow");
        metrics.put("subAnalyses", workflow.results());
        metrics.put("limitations", "各子分析使用各自图谱定义的数据要求与局限；综合结果不替代法定审查。");
        String reply = "已完成综合空间分析：" + String.join("、", workflow.capabilityIds()) + "。结果已按子分析汇总，详见分析面板与溯源记录。";
        return new AgentResult(reply, List.of("导出分析报告"), null, false, metrics, dedupeCommands(workflow.commands()), trace,
                Map.of("status", workflow.status(), "analysisType", "spatial_workflow", "capabilityIds", workflow.capabilityIds(),
                        "subAnalyses", workflow.results(), "provenance", workflow.provenance()));
    }

    private AgentResult collectPendingWorkflowData(String userMessage, String userId, String memoryId,
                                                   List<ExecutionTrace> trace, Consumer<ExecutionTrace> traceListener) {
        PendingSpatialWorkflowService.PendingWorkflow pending = pendingSpatialWorkflowService.peek();
        if (pending == null) return null;
        for (String capabilityId : pending.capabilityIds()) {
            SpatialCapabilityCatalog.Capability capability = spatialCapabilityCatalog.find(capabilityId).orElse(null);
            if (capability == null) continue;
            for (SpatialCapabilityCatalog.DataRequirement requirement : capability.dataRequirements()) {
                if (!contextContains(requirement.contextKey())) {
                    AgentResult collected = collectRequirement(capabilityId, requirement, userMessage, userId, memoryId, trace, traceListener, false);
                    if (collected != null) return collected;
                }
            }
        }
        return null;
    }

    private AgentResult resumePendingWorkflow(List<ExecutionTrace> trace, Consumer<ExecutionTrace> traceListener) {
        PendingSpatialWorkflowService.PendingWorkflow pending = pendingSpatialWorkflowService.peek();
        if (pending == null) return null;
        SpatialWorkflowService.WorkflowResult workflow = spatialWorkflowService.execute(pending.request());
        if (workflow == null) { pendingSpatialWorkflowService.clear(); return null; }
        addTrace(trace, traceListener, 1, "workflow", "恢复综合空间分析",
                "capabilities=" + String.join(",", workflow.capabilityIds()), "running");
        if (workflow.needsClarification()) {
            String question = "综合分析仍缺少：" + String.join("、", workflow.missingData()) + "。已保留工作流，补齐后会自动继续。";
            addTrace(trace, traceListener, 2, "ask", "等待工作流数据", question, "waiting");
            return new AgentResult(null, null, question, true, null, List.of(), trace,
                    Map.of("status", "NeedsClarification", "code", "workflow_data_required", "analysisType", "spatial_workflow",
                            "capabilityIds", workflow.capabilityIds(), "missingData", workflow.missingData(), "dataRecommendations", workflow.dataRecommendations(), "provenance", workflow.provenance()));
        }
        pendingSpatialWorkflowService.clear();
        return workflowResultToAgentResult(workflow, trace, traceListener);
    }

    private AgentResult workflowResultToAgentResult(SpatialWorkflowService.WorkflowResult workflow, List<ExecutionTrace> trace,
                                                    Consumer<ExecutionTrace> traceListener) {
        int total = workflow.results().size();
        for (int index = 0; index < total; index++) {
            Map<String, Object> result = workflow.results().get(index);
            addTrace(trace, traceListener, index + 2, "observation", "子分析 " + (index + 1) + "/" + total + "：" + result.get("capabilityId"),
                    "tool=" + result.get("tool") + ", status=" + result.get("status"), "Success".equals(result.get("status")) ? "success" : "error");
        }
        addTrace(trace, traceListener, total + 2, "complete", "综合空间分析完成", "已汇总 " + total + " 个子分析", "success");
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("analysis_type", "spatial_workflow"); metrics.put("subAnalyses", workflow.results());
        metrics.put("limitations", "各子分析使用各自图谱定义的数据要求与局限；综合结果不替代法定审查。");
        return new AgentResult("已完成综合空间分析：" + String.join("、", workflow.capabilityIds()) + "。", List.of("导出分析报告"), null, false,
                metrics, dedupeCommands(workflow.commands()), trace, Map.of("status", workflow.status(), "analysisType", "spatial_workflow",
                        "capabilityIds", workflow.capabilityIds(), "subAnalyses", workflow.results(), "provenance", workflow.provenance()));
    }

    private AgentResult executeGraphPlan(
            String capabilityId,
            String userMessage,
            String userId,
            String memoryId,
            List<ExecutionTrace> trace,
            Consumer<ExecutionTrace> traceListener) {
        AnalysisPlanCompiler.Compilation compilation = analysisPlanCompiler.compile(capabilityId, userMessage);
        SpatialPlanService.PreparedPlan prepared = spatialPlanService.prepare(compilation.plan());
        SpatialPlanValidator.Validation validation = prepared.validation();
        String displayName = switch (capabilityId) {
            case "skyline_analysis" -> "天际线分析";
            case "sunlight_analysis" -> "日照与阴影筛查";
            case "flood_analysis" -> "洪水分析";
            case "urban_metrics" -> "建筑指标分析";
            case "nearest_facility_distance" -> "nearest facility distance";
            default -> capabilityId;
        };

        if (!validation.canExecute()) {
            Map<String, Object> provenance = spatialPlanService.record(prepared, Map.of());
            List<String> missing = validation.missingData();
            String message;
            if ("CapabilityPending".equals(validation.status())) {
                message = "已识别“" + displayName + "”，但该能力尚未启用。"
                        + (missing.isEmpty() ? "" : "仍缺少数据：" + String.join("、", missing) + "。");
            } else if ("flood_analysis".equals(capabilityId)) {
                message = describeFloodMissingData(missing);
            } else if (missing.contains("aoi")) {
                message = "请先绘制、上传或选择分析范围（AOI），再执行“" + displayName + "”。";
            } else {
                message = "“" + displayName + "”需要的数据尚未就绪：" + String.join("、", missing) + "。";
            }
            if ("NeedsClarification".equals(validation.status())) {
                pendingAnalysisIntentService.remember(memoryId, capabilityId);
            }
            addTrace(trace, traceListener, 1, "plan", "编译空间分析计划",
                    "capability=" + capabilityId + ", validation=" + validation.status(), "success");
            addTrace(trace, traceListener, 2,
                    "CapabilityPending".equals(validation.status()) ? "wait" : "ask",
                    displayName + "等待条件", message, "waiting");
            return new AgentResult(
                    "CapabilityPending".equals(validation.status()) ? message : null,
                    "CapabilityPending".equals(validation.status()) ? List.of("保留当前 AOI", "查看当前可用分析能力") : null,
                    "NeedsClarification".equals(validation.status()) ? message : null,
                    "NeedsClarification".equals(validation.status()), null, List.of(), trace,
                    planOutcome(validation.status(), validation.code(), capabilityId, missing, provenance, compilation));
        }

        pendingAnalysisIntentService.clear(memoryId);
        addTrace(trace, traceListener, 1, "plan", "编译空间分析计划",
                "capability=" + capabilityId + ", tool=" + prepared.plan().tool(), "running");
        JSONObject params = new JSONObject();
        params.putAll(prepared.plan().params());
        if (toolRegistry.isDynamicTool(prepared.plan().tool())) {
            // 动态工具（知识图谱注入的 JS 能力）需要访问当前会话上下文
            // （AOI/建筑/DEM 等），否则其代码只能看到注册时的空快照。
            JSONObject session = JSON.parseObject(gisContextService.getGeoJson());
            if (session != null) {
                session.forEach((key, value) -> params.putIfAbsent(key, value));
            }
        }
        Map<String, Object> result = asMap(invokeTool(prepared.plan().tool(), params));
        if (result != null) {
            result = new LinkedHashMap<>(result);
            result.put("quality", spatialResultQualityService.assess(prepared.plan(), result));
        }
        Map<String, Object> provenance = spatialPlanService.record(prepared, result == null ? Map.of() : result);
        if (result == null || isFailed(result)) {
            String detail = result == null ? "分析工具未返回结果"
                    : String.valueOf(result.getOrDefault("message", "分析工具执行失败"));
            addTrace(trace, traceListener, 2, "ask", displayName + "等待数据", detail, "waiting");
            pendingAnalysisIntentService.remember(memoryId, capabilityId);
            return new AgentResult(null, null,
                    "已生成“" + displayName + "”计划，但暂不能完成执行：" + detail,
                    true, null, List.of(), trace,
                    planOutcome("NeedsClarification", "execution_data_required", capabilityId, List.of(), provenance, compilation));
        }

        List<Map<String, Object>> commands = new ArrayList<>();
        collectCommands(result, commands);
        Map<String, Object> metrics = null;
        String reply;
        if (isAdvancedAnalysis(result)) {
            metrics = result;
            reply = buildAdvancedReply(result);
        } else if (isValidMetrics(result)) {
            metrics = validationLayer.validateMetrics(result);
            reply = buildMetricReply(metrics);
            saveAnalysis(userId, userMessage, metrics);
        } else if (toolRegistry.isDynamicTool(prepared.plan().tool())) {
            // 知识图谱注入的动态能力：结果直接作为指标呈现（不套标准指标结构）。
            metrics = result;
            reply = buildDynamicReply(result, displayName);
        } else {
            reply = "“" + displayName + "”已执行，但未返回标准指标。";
        }
        addTrace(trace, traceListener, 2, "observation", "获得" + displayName + "结果",
                formatTraceDetail(result), "success");
        addTrace(trace, traceListener, 3, "complete", displayName + "完成",
                "已返回结果、地图命令与执行溯源。", "success");
        return new AgentResult(reply, suggestionEngine.generateSuggestions(metrics), null, false,
                metrics, dedupeCommands(commands), trace,
                planOutcome("Success", "", capabilityId, List.of(), provenance, compilation));
    }

    private Map<String, Object> planOutcome(
            String status,
            String code,
            String capabilityId,
            List<String> missingData,
            Map<String, Object> provenance,
            AnalysisPlanCompiler.Compilation compilation) {
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("status", status);
        if (code != null && !code.isBlank()) outcome.put("code", code);
        outcome.put("analysisType", capabilityId);
        outcome.put("missingData", missingData);
        outcome.put("plan", compilation.plan());
        outcome.put("provenance", provenance);
        return outcome;
    }

    private AgentResult collectPendingCapabilityData(String userMessage, String userId, String memoryId,
            List<ExecutionTrace> trace, Consumer<ExecutionTrace> traceListener) {
        String capabilityId = pendingAnalysisIntentService.peek(memoryId);
        if (capabilityId == null) return null;
        SpatialCapabilityCatalog.Capability capability = spatialCapabilityCatalog.find(capabilityId).orElse(null);
        if (capability == null) return null;
        return capability.dataRequirements().stream()
                .filter(requirement -> !contextContains(requirement.contextKey()))
                .map(requirement -> collectRequirement(capabilityId, requirement, userMessage, userId, memoryId, trace, traceListener, false))
                .filter(result -> result != null).findFirst().orElse(null);
    }

    private AgentResult collectInlineCapabilityData(String capabilityId, String userMessage, String userId, String memoryId,
            List<ExecutionTrace> trace, Consumer<ExecutionTrace> traceListener) {
        SpatialCapabilityCatalog.Capability capability = spatialCapabilityCatalog.find(capabilityId).orElse(null);
        if (capability == null) return null;
        return capability.dataRequirements().stream()
                .filter(requirement -> !contextContains(requirement.contextKey()))
                .map(requirement -> collectRequirement(capabilityId, requirement, userMessage, userId, memoryId, trace, traceListener, false))
                .filter(result -> result != null).findFirst().orElse(null);
    }

    private AgentResult reviseCurrentCapabilityData(String userMessage, String userId, String memoryId,
            List<ExecutionTrace> trace, Consumer<ExecutionTrace> traceListener) {
        for (SpatialCapabilityCatalog.Capability capability : spatialCapabilityCatalog.capabilities()) {
            for (SpatialCapabilityCatalog.DataRequirement requirement : capability.dataRequirements()) {
                if (contextContains(requirement.contextKey()) && matchesAnyTrigger(requirement.revisionTriggers(), userMessage)) {
                    AgentResult result = collectRequirement(capability.id(), requirement, userMessage, userId, memoryId, trace, traceListener, true);
                    if (result != null) return result;
                }
            }
        }
        return null;
    }

    private AgentResult collectRequirement(String capabilityId, SpatialCapabilityCatalog.DataRequirement requirement,
            String userMessage, String userId, String memoryId, List<ExecutionTrace> trace,
            Consumer<ExecutionTrace> traceListener, boolean revision) {
        Map<String, Object> values = new LinkedHashMap<>();
        JSONObject existing = contextObject(revision ? requirement.contextKey() : requirement.contextKey() + "_collection");
        if (existing != null) values.putAll(existing.getInnerMap());
        boolean recognized = false;
        for (SpatialCapabilityCatalog.DataField field : requirement.fields()) {
            Object parsed = parseField(field, userMessage);
            if (parsed != null) { values.put(field.key(), parsed); recognized = true; }
        }
        if (!recognized) {
            JSONObject aiValues = extractRequirementWithAi(requirement, userMessage);
            if (aiValues != null) for (SpatialCapabilityCatalog.DataField field : requirement.fields()) {
                Object parsed = aiValues.get(field.key());
                if (isValidFieldValue(field, parsed)) { values.put(field.key(), parsed); recognized = true; }
            }
        }
        if (!recognized) return null;
        List<SpatialCapabilityCatalog.DataField> missing = requirement.fields().stream()
                .filter(field -> !values.containsKey(field.key())).toList();
        if (!missing.isEmpty()) {
            gisContextService.saveGeoJson(JSON.toJSONString(Map.of(requirement.contextKey() + "_collection", values)));
            String needed = missing.stream().map(field -> field.label() + "（例如 " + field.example() + "）")
                    .reduce((left, right) -> left + "、" + right).orElse("");
            String message = "已记录“" + requirement.label() + "”的部分信息；还需要" + needed + "。";
            addTrace(trace, traceListener, 1, "ask", "补充" + requirement.label(), message, "waiting");
            return new AgentResult(null, null, message, true, null, List.of(), trace,
                    Map.of("status", "NeedsClarification", "code", "capability_data_incomplete", "missingData", missing.stream().map(SpatialCapabilityCatalog.DataField::key).toList()));
        }
        values.put("name", applyNameTemplate(requirement.nameTemplate(), values));
        values.put("source", revision ? "conversation_user_revised" : requirement.source());
        gisContextService.saveGeoJson(JSON.toJSONString(Map.of(requirement.contextKey(), values)));
        addTrace(trace, traceListener, 1, "observation", (revision ? "已更新" : "已记录") + requirement.label(),
                values.toString() + "，自动继续执行分析。", "success");
        if (pendingSpatialWorkflowService.peek() != null) {
            return resumePendingWorkflow(trace, traceListener);
        }
        return executeGraphPlan(capabilityId, capabilityId, userId, memoryId, trace, traceListener);
    }

    private Object parseField(SpatialCapabilityCatalog.DataField field, String message) {
        try {
            Matcher matcher = Pattern.compile(field.pattern()).matcher(message == null ? "" : message);
            if (!matcher.find()) return null;
            return "integer".equals(field.type()) ? Integer.parseInt(matcher.group(1)) : Double.parseDouble(matcher.group(1));
        } catch (RuntimeException ignored) { return null; }
    }

    /** LLM is the primary semantic parser; values still pass the graph schema gate below. */
    private JSONObject extractRequirementWithAi(SpatialCapabilityCatalog.DataRequirement requirement, String message) {
        try {
            List<Map<String, String>> fields = requirement.fields().stream().map(field -> Map.of(
                    "key", field.key(), "type", field.type(), "description", field.label(), "example", field.example())).toList();
            String prompt = """
                    You extract user-provided GIS analysis data. Return JSON only, with exactly the requested keys.
                    Do not infer or invent values. A value not explicitly stated is null.
                    Numeric values must be JSON numbers. Understand Chinese expressions such as '80mm，24h，20年' and '20年一遇'.
                    Required data schema: %s
                    User message: %s
                    """.formatted(JSON.toJSONString(fields), message == null ? "" : message);
            String raw = chatLanguageModel.generate(prompt);
            if (raw == null || raw.isBlank()) return null;
            raw = raw.trim().replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
            return JSON.parseObject(raw);
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean isValidFieldValue(SpatialCapabilityCatalog.DataField field, Object value) {
        if (value == null) return false;
        try {
            if ("integer".equals(field.type())) Integer.parseInt(String.valueOf(value));
            else Double.parseDouble(String.valueOf(value));
            return true;
        } catch (NumberFormatException ignored) { return false; }
    }

    private boolean contextContains(String key) { return contextObject(key) != null; }

    private JSONObject contextObject(String key) {
        try { return JSON.parseObject(gisContextService.getGeoJson()).getJSONObject(key); }
        catch (Exception ignored) { return null; }
    }

    private boolean matchesAnyTrigger(List<String> triggers, String message) {
        String text = message == null ? "" : message.toLowerCase();
        return triggers.stream().anyMatch(trigger -> text.contains(trigger.toLowerCase()));
    }

    private String applyNameTemplate(String template, Map<String, Object> values) {
        String name = template == null || template.isBlank() ? "conversation-data" : template;
        for (Map.Entry<String, Object> entry : values.entrySet()) name = name.replace("{" + entry.getKey() + "}", String.valueOf(entry.getValue()));
        return name;
    }

    private String describeFloodMissingData(List<String> missing) {
        List<String> descriptions = new ArrayList<>();
        if (missing.contains("aoi")) descriptions.add("分析范围（AOI）");
        if (missing.contains("dem")) descriptions.add("地形高程数据（建议上传 ASC 或 GeoTIFF DEM；底图采样仅适合初步筛查）");
        if (missing.contains("rainfall_scenario")) descriptions.add("降雨情景数据（至少包括雨量、历时和重现期，或可追溯的设计暴雨资料）");
        if (descriptions.isEmpty()) descriptions.addAll(missing);
        return "当前不能执行洪水分析，缺少：" + String.join("；", descriptions)
                + "。可选补充排水管网、河网和建筑物数据，以提高积水与暴露度筛查的解释性。";
    }

    private boolean isCurrentRangeReply(String userMessage) {
        if (userMessage == null) return false;
        String lower = userMessage.toLowerCase();
        return userMessage.contains("\u5f53\u524d") || userMessage.contains("\u8fd9\u4e2a\u8303\u56f4")
                || userMessage.contains("\u8be5\u8303\u56f4") || lower.contains("current")
                || lower.contains("this range") || lower.contains("aoi") || lower.contains("redline");
    }

    private AgentResult executeFloodAnalysisRequest(
            List<ExecutionTrace> trace,
            Consumer<ExecutionTrace> traceListener) {
        SpatialPlanService.PreparedPlan prepared = spatialPlanService.prepare("flood_analysis", Map.of());
        Map<String, Object> provenance = spatialPlanService.record(prepared, Map.of());
        JSONObject context;
        try {
            context = JSON.parseObject(gisContextService.getGeoJson());
        } catch (Exception ignored) {
            context = null;
        }
        if (prepared.availability().missing().contains("aoi")) {
            String question = "请先绘制或上传洪水分析范围（AOI），再提交洪水分析请求。";
            addTrace(trace, traceListener, 1, "ask", "洪水分析缺少范围", question, "waiting");
            return new AgentResult(null, null, question, true, null, List.of(), trace,
                    Map.of("status", "NeedsClarification", "code", "flood_aoi_required",
                            "missingData", prepared.validation().missingData(), "provenance", provenance));
        }

        addTrace(trace, traceListener, 1, "decision", "识别洪水分析请求",
                "当前 AOI 已有效，跳过自由格式模型决策。", "success");
        String message = "当前 AOI 已有效，但系统尚未配置洪水分析模型，未执行洪水模拟。"
                + "需要接入 DEM、降雨/重现期情景、河网或排水设施数据后才能输出淹没范围和水深。";
        addTrace(trace, traceListener, 2, "wait", "洪水分析能力待配置", message, "waiting");
        Map<String, Object> outcome = Map.of(
                "status", prepared.validation().status(),
                "code", prepared.validation().code(),
                "analysisType", "flood",
                "requiredInputs", prepared.validation().missingData(),
                "provenance", provenance);
        return new AgentResult(message,
                List.of("查看当前 AOI 建筑指标", "执行天际线分析", "执行日照与阴影筛查"),
                null, false, null, List.of(), trace, outcome);
    }

    private boolean isCurrentContextMetricsRequest(String userMessage) {
        if (userMessage == null) {
            return false;
        }
        String lower = userMessage.toLowerCase();
        return lower.contains("analyzecurrentview")
                || (userMessage.contains("当前已上传红线")
                && (userMessage.contains("容积率") || userMessage.contains("建筑指标")));
    }

    private AgentResult executeCurrentContextMetrics(
            String userMessage,
            String userId,
            List<ExecutionTrace> trace,
            Consumer<ExecutionTrace> traceListener) {
        SpatialPlanService.PreparedPlan prepared = spatialPlanService.prepare("urban_metrics", Map.of());
        if (!prepared.validation().canExecute()) {
            String question = prepared.validation().missingData().contains("aoi")
                    ? "请先绘制或上传 AOI，再计算当前范围建筑指标。"
                    : "当前 AOI 尚未同步可用建筑数据，请先完成建筑轮廓提取或上传 GeoJSON。";
            Map<String, Object> provenance = spatialPlanService.record(prepared, Map.of());
            addTrace(trace, traceListener, 1, "ask", "建筑指标分析等待数据", question, "waiting");
            return new AgentResult(null, null, question, true, null, List.of(), trace,
                    Map.of("status", prepared.validation().status(), "code", prepared.validation().code(),
                            "missingData", prepared.validation().missingData(), "provenance", provenance));
        }
        addTrace(trace, traceListener, 1, "decision", "识别当前红线指标请求",
                "直接使用已同步的 AOI 与建筑上下文，不依赖模型工具 JSON", "running");
        Map<String, Object> result = asMap(invokeTool("analyzeCurrentView", new JSONObject()));
        if (result == null || isFailed(result) || !isValidMetrics(result)) {
            String detail = result == null
                    ? "GIS 分析工具未返回结果"
                    : String.valueOf(result.getOrDefault("message", "未取得有效建筑指标"));
            addTrace(trace, traceListener, 1, "error", "当前红线分析未完成", detail, "error");
            return new AgentResult("当前红线未能完成指标计算：" + detail,
                    List.of("检查 AOI 内是否有完整建筑轮廓", "调整红线后重新分析"),
                    null, false, null, List.of(), trace);
        }

        Map<String, Object> metrics = validationLayer.validateMetrics(result);
        List<Map<String, Object>> commands = new ArrayList<>();
        collectCommands(result, commands);
        addTrace(trace, traceListener, 1, "observation", "获得空间分析结果",
                formatTraceDetail(metrics), "success");
        addTrace(trace, traceListener, 2, "complete", "当前红线分析完成",
                "已返回容积率和建筑指标", "success");
        saveAnalysis(userId, userMessage, metrics);
        memoryStore.cleanupExpired();
        return new AgentResult(buildMetricReply(metrics), suggestionEngine.generateSuggestions(metrics),
                null, false, metrics, dedupeCommands(commands), trace);
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
            String failedJobId = result == null
                    ? "未创建"
                    : String.valueOf(result.getOrDefault("jobId", "未创建"));
            String review = reviewExecutionFailure(userMessage, "CityEngine 任务提交", failedJobId, message);
            addTrace(trace, traceListener, 2, "error", "规划任务提交失败", message, "error");
            addTrace(trace, traceListener, 2, "review", "AI 失败复核", review, "success");
            return new AgentResult(message + "\n\nAI 复核：" + review,
                    List.of("检查 Python GIS 服务与 CityEngine 2025.1", "按 AI 复核建议处理后重试"),
                    null, false, null, dedupeCommands(planningCommands), trace);
        }

        // The planning endpoints wrap the deterministic building metrics in
        // `current.metrics`.  Keep that object as the source for the result
        // commands; validating the outer planning envelope means the
        // controller cannot emit `showAnalysisResult`, so the metrics panel
        // silently disappears even though the CityEngine job succeeds.
        Map<String, Object> metrics = validationLayer.validateMetrics(
                extractPlanningMetrics(result));
        appendPlanningComparisonCommand(planningCommands, metrics, result);
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
                    + buildPlanningComparison(metrics, completed)
                    + outputText;
            suggestions = List.of("下载并检查 SLPK", "将 SLPK 加载到 ArcGIS Scene", "根据效果继续调整规划参数");
        } else if ("failed".equalsIgnoreCase(status)) {
            String error = String.valueOf(completed.getOrDefault("message", "CityEngine 执行失败"));
            String review = reviewExecutionFailure(userMessage, "CityEngine 模型生成", jobId, error);
            addTrace(trace, traceListener, 3, "review", "AI 失败复核", review, "success");
            reply = "CityEngine 作业执行失败。\n\n- 作业：" + jobId + "\n- 错误：" + error
                    + "\n- AI 复核：" + review;
            suggestions = List.of("检查生成脚本与 CGA 规则", "检查 CityEngine 日志", "按 AI 复核建议处理后重新生成");
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
        if (pipelineFailed) {
            Map<String, Object> publicationProgress = asMap(completed.get("publicationProgress"));
            String error = String.valueOf(publicationProgress.getOrDefault("message", "GeoScene 发布失败"));
            String review = reviewExecutionFailure(userMessage, "GeoScene 发布", jobId, error);
            addTrace(trace, traceListener, 6, "review", "AI 失败复核", review, "success");
            reply += "\n- AI 发布失败复核：" + review;
            List<String> reviewedSuggestions = new ArrayList<>(suggestions);
            reviewedSuggestions.add("按 AI 复核建议修复 GeoScene 后重新发布");
            suggestions = reviewedSuggestions;
        }
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

    private String reviewExecutionFailure(String userRequest, String stage, String jobId, String error) {
        try {
            String prompt = promptResources.render("prompts/execution-failure-review.txt", Map.of(
                    "USER_REQUEST", userRequest == null ? "" : userRequest,
                    "STAGE", stage == null ? "unknown" : stage,
                    "JOB_ID", jobId == null ? "unknown" : jobId,
                    "ERROR", error == null ? "unknown" : error
            ));
            String review = chatLanguageModel.generate(prompt);
            return review == null || review.isBlank()
                    ? "AI 未返回复核结论；请保留作业日志并检查服务状态。"
                    : review.trim();
        } catch (Exception ex) {
            return "AI 复核调用失败：" + ex.getClass().getSimpleName()
                    + "；请检查作业日志、CityEngine 和 GeoScene Data Store。";
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
        // “基于武汉大学 500m 缓冲区” means Wuhan University is the address;
        // 基于/当前 are request prefixes, never part of a geocoding query.
        String normalizedMessage = userMessage.trim().replaceFirst(
                "^(?:(?:请|帮我|发|建立|创建|生成|构建)\\s*)?(?:(?:基于|当前)\\s*)+", "");
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
            return decisionParser.parse(llmResponse);
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
        } catch (IllegalArgumentException e) {
            return Map.of("status", "CapabilityPending", "code", "tool_not_available",
                    "tool", action, "message", "当前系统尚未配置该分析能力，已保留当前空间上下文。");
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

    /**
     * 生成“方案对比”命令：before=现状指标（地理数据实测），
     * after=规划后指标（Python 按真实建筑几何与规划参数推算）。
     * 前端 AnalysisDashboard 的 comparison 面板消费该命令。
     */
    private void appendPlanningComparisonCommand(
            List<Map<String, Object>> planningCommands,
            Map<String, Object> beforeMetrics,
            Map<String, Object> planningResult) {
        try {
            Map<String, Object> current = asMap(planningResult.get("current"));
            Map<String, Object> plannedEnvelope = asMap(planningResult.get("planned"));
            Map<String, Object> planned = plannedEnvelope == null ? null : asMap(plannedEnvelope.get("metrics"));
            if (planned == null || planned.isEmpty()) {
                Map<String, Object> job = asMap(planningResult.get("cityEngineJob"));
                planned = job == null ? null : asMap(job.get("plannedMetrics"));
            }
            if (planned == null || planned.isEmpty()) {
                return;
            }
            Map<String, Object> heightStats = asMap(beforeMetrics.get("height_stats"));
            double beforeFar = getDouble(beforeMetrics, "far", 0);
            double beforeDensity = getDouble(beforeMetrics, "building_density",
                    getDouble(beforeMetrics, "buildingDensity", 0));
            double beforeHeight = heightStats == null ? 0 : getDouble(heightStats, "max", 0);
            double beforeGreen = getDouble(beforeMetrics, "green_rate", getDouble(beforeMetrics, "greenRate", 0));

            Map<String, Object> comparison = new LinkedHashMap<>();
            comparison.put("far", Map.of("before", round2(beforeFar), "after", round2(getDouble(planned, "far", beforeFar))));
            comparison.put("buildingDensity", Map.of(
                    "before", round2(beforeDensity),
                    "after", round2(getDouble(planned, "building_density", getDouble(planned, "building_density_pct", beforeDensity)))));
            comparison.put("buildingHeight", Map.of(
                    "before", round1(beforeHeight),
                    "after", round1(getDouble(planned, "buildingHeight", beforeHeight))));
            comparison.put("greenRate", Map.of(
                    "before", round2(beforeGreen),
                    "after", round2(getDouble(planned, "green_rate", getDouble(planned, "greenRate", beforeGreen)))));
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("comparison", comparison);
            planningCommands.add(Map.of("action", "comparePlanningScenarios", "params", params));
        } catch (RuntimeException ignored) {
            // 对比命令是增强展示，生成失败不应阻断规划流程。
        }
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
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

    private boolean isCapabilityPending(Map<String, Object> result) {
        return "CapabilityPending".equalsIgnoreCase(String.valueOf(result.getOrDefault("status", "")));
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
                || "flood".equalsIgnoreCase(type)
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

    /**
     * 动态能力（知识图谱注入的 JS）的通用结果回复：把返回的数值/文本字段
     * 拼成自然语言说明，控制字段（status/commands/trace 等）不展示。
     */
    private String buildDynamicReply(Map<String, Object> result, String displayName) {
        List<String> ignored = List.of("status", "analysis_type", "analysisType", "commands",
                "trace", "provenance", "quality", "message", "missing_data", "missingData", "stage");
        StringBuilder reply = new StringBuilder("已完成“" + displayName + "”：");
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Object> entry : result.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (ignored.contains(key) || value == null) continue;
            if (value instanceof Number number) {
                parts.add(key + "=" + (number.doubleValue() == Math.rint(number.doubleValue())
                        ? String.valueOf(number.longValue()) : String.format(Locale.ROOT, "%.2f", number.doubleValue())));
            } else if (value instanceof String text && !text.isBlank()) {
                parts.add(key + "=" + text);
            }
        }
        if (parts.isEmpty()) {
            reply.append("动态计算已完成（未返回可展示字段）。");
        } else {
            reply.append(String.join("，", parts)).append("。");
        }
        return reply.toString();
    }

    private String buildAdvancedReply(Map<String, Object> result) {
        String type = String.valueOf(result.getOrDefault("analysis_type", "advanced"));        if ("skyline".equalsIgnoreCase(type)) {
            return String.format(
                    "已完成天际线形态筛查：分析了 %d 栋建筑，最高建筑约 %.1f 米，平均高度约 %.1f 米。结果为基于建筑中心点和属性高度的方向剖面，不包含地形遮挡。",
                    getInt(result, "building_count", 0),
                    getDouble(result, "max_height", 0),
                    getDouble(result, "mean_height", 0));
        }
        if ("flood".equalsIgnoreCase(type)) {
            boolean buildingExposureAvailable = Boolean.TRUE.equals(result.get("building_exposure_available"));
            String buildingExposure = buildingExposureAvailable
                    ? String.format("%d potentially affected buildings", getInt(result, "affected_building_count", 0))
                    : "building exposure was not evaluated because no complete building dataset is available";
            return String.format(
                    "Flood hydrologic terrain screening completed: %d high-risk cells, %d medium-risk cells, and %s. "
                            + "Under %.0f mm rainfall, the maximum screened depth is %.3f m (DEM depression fill, D8 routing, and drainage reduction).",
                    getInt(result, "high_risk_cell_count", 0),
                    getInt(result, "medium_risk_cell_count", 0),
                    buildingExposure,
                    getDouble(result, "rainfall_mm", 0),
                    getDouble(result, "max_estimated_depth_m", 0));
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
                    "max_shadow_length_m", "high_risk_cell_count", "medium_risk_cell_count",
                    "max_estimated_depth_m", "registered_tool", "message")) {
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
            Map<String, Object> command = commands.get(i);
            String key = CommandProtocol.dedupeKey(command);
            if (seenActions.add(key)) {
                deduped.add(0, command);
            }
        }
        return CommandProtocol.normalize(deduped);
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
            List<ExecutionTrace> trace,
            Map<String, Object> outcome
    ) {
        public AgentResult(
                String reply,
                List<String> suggestions,
                String clarification,
                boolean needsClarification,
                Map<String, Object> metrics,
                List<Map<String, Object>> commands,
                List<ExecutionTrace> trace) {
            this(reply, suggestions, clarification, needsClarification, metrics, commands, trace,
                    Map.of("status", needsClarification ? "NeedsClarification" : "Success"));
        }
    }

    public record ExecutionTrace(
            int round,
            String phase,
            String title,
            String detail,
            String status
    ) {}

    private record PlaceAnalysisRequest(String locationName, int radiusMeters) {}
    private record NavigationRequest(String locationName) {}

    private final List<ScenarioStrategy> scenarioStrategies = List.of(
            new ConservativeStrategy(),
            new BalancedStrategy(),
            new AggressiveStrategy()
    );

    public List<Scenario> generateScenarios(
            Map<String, Object> aoi,
            List<Map<String, Object>> buildings,
            SpatialCapabilityCatalog catalog) {
        double siteArea = extractSiteArea(aoi);
        double heightLimit = extractHeightLimit(catalog);
        Map<String, Double> context = new LinkedHashMap<>();
        context.put("siteArea", siteArea);
        context.put("heightLimit", heightLimit);
        context.put("targetFar", 2.0);
        context.put("densityFactor", 0.85);
        List<Scenario> scenarios = new ArrayList<>();
        for (ScenarioStrategy strategy : scenarioStrategies) {
            List<Map<String, Object>> adjustedBuildings = strategy.apply(buildings, context);
            Map<String, Double> params = new LinkedHashMap<>(context);
            params.put("strategyId", (double) strategy.id().hashCode());
            scenarios.add(new Scenario(
                    strategy.id(),
                    strategy.name(),
                    strategy.description(),
                    params,
                    adjustedBuildings
            ));
        }
        return scenarios;
    }

    public List<ScenarioResult> evaluateScenarios(
            List<Scenario> scenarios,
            String capabilityId,
            double originalFar) {
        ScenarioEvaluator evaluator = new ScenarioEvaluator(spatialCapabilityCatalog);
        return evaluator.evaluate(scenarios, capabilityId, originalFar);
    }

    private double extractSiteArea(Map<String, Object> aoi) {
        if (aoi == null) return 10000.0;
        Object area = aoi.get("siteArea");
        if (area == null) area = aoi.get("site_area");
        if (area == null) area = aoi.get("site_area_sqm");
        if (area == null) return 10000.0;
        try { return Math.max(1, Double.parseDouble(area.toString())); }
        catch (NumberFormatException e) { return 10000.0; }
    }

    private double extractHeightLimit(SpatialCapabilityCatalog catalog) {
        if (catalog == null) return 24.0;
        List<SpatialCapabilityCatalog.Constraint> constraints = catalog.getConstraints("urban_metrics");
        for (SpatialCapabilityCatalog.Constraint c : constraints) {
            if ("buildingHeight".equals(c.metric()) || "building_height".equals(c.metric())) {
                return c.max();
            }
        }
        return 24.0;
    }
}
