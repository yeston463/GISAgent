package org.example.spatial;

import com.alibaba.fastjson.JSONObject;
import org.example.tools.DynamicToolRegistry;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Executes an ordered collection of already-approved spatial plans. */
@Service
public class SpatialWorkflowService {
    private final SpatialWorkflowPlanner planner;
    private final SpatialPlanService plans;
    private final DynamicToolRegistry tools;
    private final SpatialResultQualityService quality;
    private final SpatialDataDiscoveryService discovery;
    private final SpatialReplanningService replanning;
    private final SpatialWorkflowExperienceStore experiences;

    public SpatialWorkflowService(SpatialWorkflowPlanner planner, SpatialPlanService plans, DynamicToolRegistry tools, SpatialResultQualityService quality,
                                  SpatialDataDiscoveryService discovery, SpatialReplanningService replanning, SpatialWorkflowExperienceStore experiences) {
        this.planner = planner;
        this.plans = plans;
        this.tools = tools;
        this.quality = quality;
        this.discovery = discovery; this.replanning = replanning; this.experiences = experiences;
    }

    public WorkflowResult execute(String message) {
        List<AnalysisPlanCompiler.Compilation> compilations = planner.compileWorkflow(message);
        if (compilations.size() < 2) return null;
        return executeCompilations(message, compilations);
    }
    public WorkflowResult executeCapabilities(String message, List<String> capabilityIds) {
        List<AnalysisPlanCompiler.Compilation> compilations = capabilityIds.stream().map(id -> plannerCompile(id, message)).toList();
        return executeCompilations(message, compilations);
    }
    private AnalysisPlanCompiler.Compilation plannerCompile(String id, String message) {
        return new AnalysisPlanCompiler.Compilation(plans.prepare(id, Map.of()).plan(), id, Map.of());
    }
    private WorkflowResult executeCompilations(String message, List<AnalysisPlanCompiler.Compilation> compilations) {
        List<SpatialPlanService.PreparedPlan> prepared = compilations.stream().map(item -> plans.prepare(item.plan())).toList();
        Set<String> missing = new LinkedHashSet<>();
        for (SpatialPlanService.PreparedPlan item : prepared) missing.addAll(item.validation().missingData());
        if (!missing.isEmpty()) {
            List<Map<String, Object>> provenance = new ArrayList<>();
            for (SpatialPlanService.PreparedPlan item : prepared) provenance.add(plans.record(item, Map.of()));
            return WorkflowResult.waiting(compilations.stream().map(AnalysisPlanCompiler.Compilation::capabilityId).toList(),
                    List.copyOf(missing), provenance, discovery.recommend(List.copyOf(missing)));
        }
        List<Map<String, Object>> results = new ArrayList<>();
        List<Map<String, Object>> provenance = new ArrayList<>();
        List<Map<String, Object>> commands = new ArrayList<>();
        for (int index = 0; index < prepared.size(); index++) {
            SpatialPlanService.PreparedPlan item = prepared.get(index);
            AnalysisPlanCompiler.Compilation compilation = compilations.get(index);
            Map<String, Object> result;
            try {
                Object invoked = tools.invoke(item.plan().tool(), new JSONObject(item.plan().params()));
                if (invoked instanceof Map<?, ?> values) {
                    Map<String, Object> mappedResult = new LinkedHashMap<>();
                    values.forEach((key, value) -> mappedResult.put(String.valueOf(key), value));
                    result = mappedResult;
                } else {
                    result = Map.of("status", "Error", "message", "tool_result_not_object");
                }
            } catch (Exception error) {
                result = Map.of("status", "Error", "message", error.getMessage() == null ? "tool_execution_failed" : error.getMessage());
            }
            result = new LinkedHashMap<>(result);
            result.put("quality", quality.assess(item.plan(), result));
            provenance.add(plans.record(item, result));
            Map<String, Object> child = new LinkedHashMap<>();
            child.put("capabilityId", compilation.capabilityId());
            child.put("tool", item.plan().tool());
            child.put("status", result.getOrDefault("status", "Success"));
            child.put("result", result);
            if ("Error".equalsIgnoreCase(String.valueOf(child.get("status")))) child.put("replan", replanning.replan(compilation.capabilityId(), result));
            results.add(child);
            Object rawCommands = result.get("commands");
            if (rawCommands instanceof List<?> list) for (Object command : list) {
                if (command instanceof Map<?, ?> map && map.get("action") != null) {
                    Map<String, Object> normalized = new LinkedHashMap<>();
                    map.forEach((key, value) -> normalized.put(String.valueOf(key), value));
                    commands.add(normalized);
                }
            }
        }
        boolean failed = results.stream().anyMatch(item -> "Error".equalsIgnoreCase(String.valueOf(item.get("status"))));
        String status = failed ? "PartialSuccess" : "Success";
        experiences.record(message, compilations.stream().map(AnalysisPlanCompiler.Compilation::capabilityId).toList(), status);
        return new WorkflowResult(compilations.stream().map(AnalysisPlanCompiler.Compilation::capabilityId).toList(), List.of(),
                results, provenance, commands, status, List.of());
    }

    public record WorkflowResult(List<String> capabilityIds, List<String> missingData, List<Map<String, Object>> results,
                                 List<Map<String, Object>> provenance, List<Map<String, Object>> commands, String status, List<Map<String, Object>> dataRecommendations) {
        static WorkflowResult waiting(List<String> capabilityIds, List<String> missing, List<Map<String, Object>> provenance, List<Map<String, Object>> recommendations) {
            return new WorkflowResult(capabilityIds, missing, List.of(), provenance, List.of(), "NeedsClarification", recommendations);
        }
        public boolean needsClarification() { return "NeedsClarification".equals(status); }
    }
}
