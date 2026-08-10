package org.example.spatial;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class SpatialCapabilityCatalog {
    private static final Set<String> APPROVED_TOOLS = Set.of(
            "analyzeCurrentView", "skylineAnalysis", "sunlightAnalysis", "floodAnalysis", "siteSelection");
    private final RestTemplate http = new RestTemplate();
    private volatile Snapshot snapshot;
    private Snapshot bundledSnapshot;
    private volatile Instant lastRefreshAttempt;
    private volatile String lastRefreshError;

    @Value("${spatial.knowledge-graph.url:}") private String remoteUrl;
    @Value("${spatial.knowledge-graph.refresh-ttl-seconds:300}") private long refreshTtlSeconds;

    @PostConstruct
    void load() {
        bundledSnapshot = parse(loadBundled(), "bundled", "local-1.2");
        snapshot = bundledSnapshot;
    }

    public Optional<Capability> find(String id) { refreshIfDue(); return Optional.ofNullable(snapshot.capabilities().get(id)); }
    public List<Capability> capabilities() { refreshIfDue(); return List.copyOf(snapshot.capabilities().values()); }
    public String version() { refreshIfDue(); return snapshot.version(); }

    public List<Constraint> getConstraints(String capabilityId) {
        refreshIfDue();
        Capability cap = snapshot.capabilities().get(capabilityId);
        return cap == null ? List.of() : cap.constraints();
    }

    public List<Relation> getRelations(String capabilityId) {
        refreshIfDue();
        Capability cap = snapshot.capabilities().get(capabilityId);
        return cap == null ? List.of() : cap.relations();
    }

    public List<Violation> validateMetrics(String capabilityId, Map<String, Object> metrics) {
        refreshIfDue();
        Capability cap = snapshot.capabilities().get(capabilityId);
        if (cap == null || metrics == null || metrics.isEmpty()) return List.of();
        List<Violation> violations = new ArrayList<>();
        for (Constraint constraint : cap.constraints()) {
            Object rawValue = metrics.get(constraint.metric());
            if (rawValue == null) continue;
            double value;
            try {
                value = ((Number) rawValue).doubleValue();
            } catch (ClassCastException e) {
                continue;
            }
            if (value > constraint.max()) {
                violations.add(new Violation(constraint.metric(), value, constraint.max(), constraint.unit(), constraint.source()));
            }
        }
        return List.copyOf(violations);
    }

    public List<Map<String, Object>> descriptors() {
        refreshIfDue();
        List<Map<String, Object>> result = new ArrayList<>();
        snapshot.capabilities().values().forEach(capability -> result.add(capability.asMap()));
        return result;
    }

    public Map<String, Object> status() {
        refreshIfDue();
        return statusOf(snapshot);
    }

    private Map<String, Object> statusOf(Snapshot current) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", current.version());
        result.put("source", current.source());
        result.put("loadedAt", current.loadedAt().toString());
        result.put("remoteConfigured", remoteUrl != null && !remoteUrl.isBlank());
        result.put("capabilityCount", current.capabilities().size());
        if (lastRefreshAttempt != null) result.put("lastRefreshAttempt", lastRefreshAttempt.toString());
        if (lastRefreshError != null) result.put("lastRefreshError", lastRefreshError);
        return result;
    }

    public synchronized Map<String, Object> refresh() {
        if (remoteUrl == null || remoteUrl.isBlank()) {
            return Map.of("refreshed", false, "reason", "remote_source_not_configured", "status", statusOf(snapshot));
        }
        lastRefreshAttempt = Instant.now();
        try {
            ResponseEntity<String> response = http.getForEntity(remoteUrl, String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) throw new IllegalArgumentException("HTTP " + response.getStatusCode());
            applyRemoteGraph(response.getBody());
            lastRefreshError = null;
            return Map.of("refreshed", true, "status", statusOf(snapshot));
        } catch (Exception error) {
            lastRefreshError = safeMessage(error);
            return Map.of("refreshed", false, "reason", "remote_graph_rejected", "detail", lastRefreshError, "status", statusOf(snapshot));
        }
    }

    public AnalysisPlan createPlan(String capabilityId, Map<String, Object> params) {
        Capability capability = find(capabilityId).orElseThrow(() -> new IllegalArgumentException("Unknown spatial capability: " + capabilityId));
        Snapshot current = snapshot;
        return new AnalysisPlan(current.version(), current.source(), capability.id(), "current_aoi", capability.operations(), capability.tool(), capability.outputs(),
                params == null ? Map.of() : new LinkedHashMap<>(params));
    }

    private void refreshIfDue() {
        Snapshot current = snapshot;
        Instant lastAttempt = lastRefreshAttempt == null ? current.loadedAt() : lastRefreshAttempt;
        if (remoteUrl == null || remoteUrl.isBlank() || Instant.now().minusSeconds(Math.max(15, refreshTtlSeconds)).isBefore(lastAttempt)) return;
        refresh();
    }

    private String loadBundled() {
        try (InputStream stream = new ClassPathResource("spatial-capabilities.json").getInputStream()) { return new String(stream.readAllBytes(), StandardCharsets.UTF_8); }
        catch (Exception error) { throw new IllegalStateException("Unable to load bundled spatial capability graph", error); }
    }

    private Snapshot parse(String raw, String source, String fallbackVersion) {
        JSONObject root = JSON.parseObject(raw);
        if (root == null || root.getJSONArray("capabilities") == null) throw new IllegalArgumentException("graph_schema_invalid");
        String version = root.getString("version");
        if (version == null || version.isBlank()) version = fallbackVersion;
        Map<String, Capability> capabilities = new LinkedHashMap<>();
        for (Object row : root.getJSONArray("capabilities")) {
            if (!(row instanceof JSONObject item)) throw new IllegalArgumentException("capability_not_object");
            Capability capability = capability(item);
            if (capabilities.putIfAbsent(capability.id(), capability) != null) throw new IllegalArgumentException("duplicate_capability:" + capability.id());
        }
        if (capabilities.isEmpty()) throw new IllegalArgumentException("capability_graph_empty");
        return new Snapshot(version, source, Instant.now(), Map.copyOf(capabilities));
    }

    synchronized void applyRemoteGraph(String raw) {
        snapshot = mergedSnapshot(raw, "remote_overlay", "remote");
    }

    public synchronized void applyPublishedGraph(String raw) {
        snapshot = mergedSnapshot(raw, "published_revision", "published");
    }

    public synchronized CandidatePreview preview(String raw) {
        Snapshot candidate = mergedSnapshot(raw, "candidate", "candidate");
        return new CandidatePreview(candidate.version(), candidate.capabilities().size(), semanticChanges(snapshot, candidate));
    }

    private Snapshot mergedSnapshot(String raw, String source, String fallbackVersion) {
        Snapshot remote = parse(raw, source, fallbackVersion);
        Map<String, Capability> merged = new LinkedHashMap<>(bundledSnapshot.capabilities());
        for (Map.Entry<String, Capability> entry : remote.capabilities().entrySet()) {
            Capability baseline = bundledSnapshot.capabilities().get(entry.getKey());
            merged.put(entry.getKey(), baseline == null
                    ? extension(entry.getValue())
                    : overlay(baseline, entry.getValue()));
        }
        return new Snapshot(remote.version(), source, Instant.now(), Map.copyOf(merged));
    }

    private List<Map<String, Object>> semanticChanges(Snapshot current, Snapshot candidate) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, Capability> entry : candidate.capabilities().entrySet()) {
            Capability before = current.capabilities().get(entry.getKey());
            Capability after = entry.getValue();
            boolean aliasesChanged = before == null || !before.aliases().equals(after.aliases());
            boolean dataReqChanged = before == null || !before.dataRequirements().equals(after.dataRequirements());
            boolean knowledgeChanged = before == null || !before.knowledge().equals(after.knowledge());
            boolean constraintsChanged = before == null || !before.constraints().equals(after.constraints());
            boolean relationsChanged = before == null || !before.relations().equals(after.relations());
            if (aliasesChanged || dataReqChanged || knowledgeChanged || constraintsChanged || relationsChanged) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("capabilityId", entry.getKey());
                change.put("aliasesChanged", aliasesChanged);
                change.put("dataRequirementsChanged", dataReqChanged);
                change.put("knowledgeChanged", knowledgeChanged);
                change.put("constraintsChanged", constraintsChanged);
                change.put("relationsChanged", relationsChanged);
                result.add(change);
            }
        }
        return List.copyOf(result);
    }

    private Capability overlay(Capability baseline, Capability remote) {
        if (!baseline.tool().equals(remote.tool())) {
            throw new IllegalArgumentException("remote_tool_mismatch:" + remote.id());
        }
        if (!baseline.operations().equals(remote.operations())
                || !baseline.outputs().equals(remote.outputs())
                || !baseline.rendererKinds().equals(remote.rendererKinds())
                || !baseline.requires().equals(remote.requires())
                || !baseline.optional().equals(remote.optional())) {
            throw new IllegalArgumentException("remote_execution_contract_mismatch:" + remote.id());
        }
        return new Capability(baseline.id(), baseline.enabled(), remote.aliases(), baseline.requires(), baseline.optional(),
                baseline.operations(), baseline.tool(), baseline.outputs(), baseline.rendererKinds(),
                remote.dataRequirements(), remote.knowledge(), baseline.constraints(), baseline.relations());
    }

    /** New capabilities may reuse, but never extend, an approved executable contract. */
    private Capability extension(Capability candidate) {
        Capability contract = bundledSnapshot.capabilities().values().stream()
                .filter(baseline -> baseline.tool().equals(candidate.tool())
                        && baseline.operations().equals(candidate.operations())
                        && baseline.outputs().equals(candidate.outputs())
                        && baseline.rendererKinds().equals(candidate.rendererKinds())
                        && baseline.requires().equals(candidate.requires())
                        && baseline.optional().equals(candidate.optional()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "new_capability_execution_contract_not_allowlisted:" + candidate.id()));
        return new Capability(candidate.id(), candidate.enabled(), candidate.aliases(), contract.requires(), contract.optional(),
                contract.operations(), contract.tool(), contract.outputs(), contract.rendererKinds(),
                candidate.dataRequirements(), candidate.knowledge(), candidate.constraints(), candidate.relations());
    }

    private Capability capability(JSONObject item) {
        String id = required(item, "id");
        String tool = required(item, "tool");
        if (!id.matches("[a-z][a-z0-9_]{1,80}")) throw new IllegalArgumentException("invalid_capability_id:" + id);
        if (!APPROVED_TOOLS.contains(tool)) throw new IllegalArgumentException("tool_not_allowlisted:" + tool);
        List<String> operations = strings(item.getJSONArray("operations"));
        if (operations.isEmpty()) throw new IllegalArgumentException("operations_required:" + id);
        return new Capability(id, item.getBooleanValue("enabled"), strings(item.getJSONArray("aliases")), strings(item.getJSONArray("requires")),
                strings(item.getJSONArray("optional")), operations, tool, strings(item.getJSONArray("outputs")), strings(item.getJSONArray("rendererKinds")),
                dataRequirements(item.getJSONArray("dataRequirements")), knowledge(item.getJSONObject("knowledge")),
                constraints(item.getJSONArray("constraints")), relations(item.getJSONArray("relations")));
    }

    private static String required(JSONObject item, String key) { String value = item.getString(key); if (value == null || value.isBlank()) throw new IllegalArgumentException("missing_" + key); return value; }
    private static List<String> strings(JSONArray values) { return values == null ? List.of() : values.toJavaList(String.class); }
    private static List<DataRequirement> dataRequirements(JSONArray values) {
        if (values == null) return List.of(); List<DataRequirement> result = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof JSONObject item)) throw new IllegalArgumentException("data_requirement_not_object");
            List<DataField> fields = new ArrayList<>(); JSONArray rawFields = item.getJSONArray("fields");
            if (rawFields != null) for (Object fieldValue : rawFields) {
                if (!(fieldValue instanceof JSONObject field)) throw new IllegalArgumentException("data_field_not_object");
                String type = required(field, "type"); if (!Set.of("number", "integer").contains(type)) throw new IllegalArgumentException("unsupported_field_type:" + type);
                String pattern = required(field, "pattern"); try { java.util.regex.Pattern.compile(pattern); } catch (Exception error) { throw new IllegalArgumentException("invalid_field_pattern"); }
                String key = required(field, "key");
                if (!key.matches("[a-z][a-zA-Z0-9]{0,80}")) throw new IllegalArgumentException("invalid_field_key:" + key);
                fields.add(new DataField(key, required(field,"label"), pattern, type, required(field,"example")));
            }
            String contextKey = required(item, "contextKey");
            if (!contextKey.matches("[a-z][a-z0-9_]{1,80}")) throw new IllegalArgumentException("invalid_context_key:" + contextKey);
            result.add(new DataRequirement(contextKey, required(item,"label"), item.getString("source"), item.getString("nameTemplate"), strings(item.getJSONArray("revisionTriggers")), List.copyOf(fields)));
        }
        return List.copyOf(result);
    }

    private static List<Constraint> constraints(JSONArray values) {
        if (values == null) return List.of();
        List<Constraint> result = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof JSONObject item)) throw new IllegalArgumentException("constraint_not_object");
            String metric = required(item, "metric");
            double max = item.getDoubleValue("max");
            String unit = item.getString("unit");
            if (unit == null) unit = "";
            String source = item.getString("source");
            if (source == null) source = "";
            result.add(new Constraint(metric, max, unit, source));
        }
        return List.copyOf(result);
    }

    private static List<Relation> relations(JSONArray values) {
        if (values == null) return List.of();
        List<Relation> result = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof JSONObject item)) throw new IllegalArgumentException("relation_not_object");
            String trigger = required(item, "trigger");
            String action = required(item, "action");
            String target = required(item, "target");
            result.add(new Relation(trigger, action, target));
        }
        return List.copyOf(result);
    }

    private static Map<String, Object> knowledge(JSONObject value) { if (value == null) return Map.of(); Map<String,Object> result=new LinkedHashMap<>(); value.forEach((k,v)->result.put(k,v)); return Map.copyOf(result); }
    private static String safeMessage(Exception error) { return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); }

    public record Capability(String id, boolean enabled, List<String> aliases, List<String> requires, List<String> optional, List<String> operations,
                             String tool, List<String> outputs, List<String> rendererKinds, List<DataRequirement> dataRequirements, Map<String,Object> knowledge,
                             List<Constraint> constraints, List<Relation> relations) {
        Map<String,Object> asMap() { Map<String,Object> map=new LinkedHashMap<>(); map.put("id",id); map.put("enabled",enabled); map.put("aliases",aliases); map.put("requires",requires); map.put("optional",optional); map.put("operations",operations); map.put("tool",tool); map.put("outputs",outputs); map.put("rendererKinds",rendererKinds); map.put("dataRequirements",dataRequirements); map.put("knowledge",knowledge); map.put("constraints",constraints); map.put("relations",relations); return map; }
    }
    public record DataRequirement(String contextKey, String label, String source, String nameTemplate, List<String> revisionTriggers, List<DataField> fields) { }
    public record DataField(String key, String label, String pattern, String type, String example) { }
    public record Constraint(String metric, double max, String unit, String source) { }
    public record Relation(String trigger, String action, String target) { }
    public record Violation(String metric, double value, double max, String unit, String source) { }
    public record CandidatePreview(String version, int capabilityCount, List<Map<String, Object>> changes) { }
    private record Snapshot(String version, String source, Instant loadedAt, Map<String, Capability> capabilities) { }
}
