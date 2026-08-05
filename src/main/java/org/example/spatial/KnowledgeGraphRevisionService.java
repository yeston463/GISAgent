package org.example.spatial;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.annotation.PostConstruct;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

/** Local, versioned publication store for reviewed knowledge-graph overlays. */
@Component
public class KnowledgeGraphRevisionService {
    private final SpatialCapabilityCatalog catalog;
    private final Path file;

    @Autowired
    public KnowledgeGraphRevisionService(SpatialCapabilityCatalog catalog) {
        this(catalog, Path.of(System.getProperty("user.dir"), "cityengine-workspace", "knowledge-graph-revisions.json"));
    }

    KnowledgeGraphRevisionService(SpatialCapabilityCatalog catalog, Path file) {
        this.catalog = catalog;
        this.file = file.toAbsolutePath().normalize();
    }

    /** Restore the active published revision after a normal process restart. */
    @PostConstruct
    void restoreActiveRevision() {
        for (Map<String, Object> revision : revisions()) {
            if (Boolean.TRUE.equals(revision.get("active"))) {
                catalog.applyPublishedGraph(JSON.toJSONString(revision.get("graph")));
                audit("restored", String.valueOf(revision.get("version")), "startup");
                return;
            }
        }
    }

    public synchronized Map<String, Object> preview(String graph, Map<String, List<String>> acceptanceTests) {
        SpatialCapabilityCatalog.CandidatePreview preview = catalog.preview(graph);
        Map<String, List<String>> tests = acceptanceTests == null ? Map.of() : acceptanceTests;
        List<Map<String, Object>> gates = qualityGates(graph, preview, tests);
        boolean passed = gates.stream().allMatch(gate -> Boolean.TRUE.equals(gate.get("passed")));
        return Map.of("valid", passed, "preview", preview, "qualityGates", gates, "active", catalog.status());
    }

    public synchronized Map<String, Object> publish(String graph, String author, String note, Map<String, List<String>> acceptanceTests) {
        SpatialCapabilityCatalog.CandidatePreview preview = catalog.preview(graph);
        List<Map<String, Object>> gates = qualityGates(graph, preview, acceptanceTests == null ? Map.of() : acceptanceTests);
        if (gates.stream().anyMatch(gate -> !Boolean.TRUE.equals(gate.get("passed")))) {
            throw new IllegalArgumentException("quality_gates_failed");
        }
        JSONObject parsed = JSON.parseObject(graph);
        String version = parsed.getString("version");
        if (version == null || version.isBlank()) throw new IllegalArgumentException("graph_version_required");
        for (Map<String, Object> revision : revisions()) {
            if (version.equals(revision.get("version"))) throw new IllegalArgumentException("graph_version_already_published:" + version);
        }
        Map<String, Object> revision = new LinkedHashMap<>();
        revision.put("version", version);
        revision.put("publishedAt", Instant.now().toString());
        revision.put("author", author == null || author.isBlank() ? "local" : author);
        revision.put("note", note == null ? "" : note);
        revision.put("graph", JSON.parse(graph));
        revision.put("changes", preview.changes());
        revision.put("qualityGates", gates);
        List<Map<String, Object>> all = revisions();
        all.forEach(item -> item.put("active", false));
        revision.put("active", true);
        all.add(revision);
        persist(all);
        catalog.applyPublishedGraph(graph);
        audit("published", version, String.valueOf(revision.get("author")));
        return Map.of("published", true, "revision", summary(revision), "active", catalog.status());
    }

    public synchronized Map<String, Object> rollback(String version) {
        for (Map<String, Object> revision : revisions()) {
            if (version.equals(revision.get("version"))) {
                List<Map<String, Object>> all = revisions();
                all.forEach(item -> item.put("active", version.equals(item.get("version"))));
                persist(all);
                catalog.applyPublishedGraph(JSON.toJSONString(revision.get("graph")));
                audit("rolled_back", version, "api");
                return Map.of("rolledBack", true, "revision", summary(revision), "active", catalog.status());
            }
        }
        throw new IllegalArgumentException("published_graph_version_not_found:" + version);
    }

    public synchronized List<Map<String, Object>> list() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> revision : revisions()) result.add(summary(revision));
        return result;
    }

    private List<Map<String, Object>> qualityGates(String graph, SpatialCapabilityCatalog.CandidatePreview preview,
                                                    Map<String, List<String>> tests) {
        JSONObject root = JSON.parseObject(graph);
        JSONArray capabilities = root.getJSONArray("capabilities");
        List<Map<String, Object>> gates = new ArrayList<>();
        for (Object row : capabilities) {
            JSONObject capability = (JSONObject) row;
            String id = capability.getString("id");
            JSONArray aliasRows = capability.getJSONArray("aliases");
            List<String> aliases = aliasRows == null ? List.of() : aliasRows.toJavaList(String.class);
            boolean hasChineseAlias = aliases.stream().anyMatch(alias -> alias != null && alias.matches(".*[\\u4e00-\\u9fff].*"));
            gates.add(gate(id, "chinese_alias", hasChineseAlias, "至少需要一个中文别名"));
            List<String> samples = tests.getOrDefault(id, List.of());
            boolean matched = samples.stream().anyMatch(sample -> matches(sample, aliases));
            gates.add(gate(id, "acceptance_intent", matched, "至少需要一条可匹配当前别名的验收语句"));
            JSONArray requirements = capability.getJSONArray("dataRequirements");
            if (requirements != null) {
                for (Object requirement : requirements) {
                    JSONArray fields = ((JSONObject) requirement).getJSONArray("fields");
                    if (fields != null) for (Object field : fields) {
                        JSONObject value = (JSONObject) field;
                        boolean complete = value.getString("example") != null && !value.getString("example").isBlank()
                                && value.getString("pattern") != null && !value.getString("pattern").isBlank();
                        gates.add(gate(id, "data_field_example_and_pattern", complete,
                                "数据字段必须包含示例和正则：" + value.getString("key")));
                    }
                }
            }
        }
        for (Map<String, Object> change : preview.changes()) {
            List<String> impacts = new ArrayList<>();
            if (Boolean.TRUE.equals(change.get("aliasesChanged"))) impacts.add("Agent 表达解析");
            if (Boolean.TRUE.equals(change.get("dataRequirementsChanged"))) impacts.add("数据补全对话");
            if (Boolean.TRUE.equals(change.get("knowledgeChanged"))) impacts.add("分析报告知识说明");
            change.put("impacts", impacts);
        }
        return List.copyOf(gates);
    }

    private Map<String, Object> gate(String capabilityId, String name, boolean passed, String message) {
        return Map.of("capabilityId", capabilityId, "name", name, "passed", passed, "message", message);
    }

    private boolean matches(String message, List<String> aliases) {
        if (message == null) return false;
        String normalized = message.toLowerCase(Locale.ROOT);
        return aliases.stream().filter(alias -> alias != null && !alias.isBlank())
                .anyMatch(alias -> normalized.contains(alias.toLowerCase(Locale.ROOT)));
    }

    private List<Map<String, Object>> revisions() {
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            if (!Files.isRegularFile(file)) return result;
            JSONArray rows = JSON.parseArray(Files.readString(file, StandardCharsets.UTF_8));
            if (rows == null) return result;
            for (Object row : rows) {
                if (row instanceof Map<?, ?> map) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    map.forEach((key, value) -> item.put(String.valueOf(key), value));
                    result.add(item);
                }
            }
            return result;
        } catch (Exception error) {
            throw new IllegalStateException("knowledge_graph_revision_store_unavailable", error);
        }
    }

    private Map<String, Object> summary(Map<String, Object> revision) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", revision.get("version"));
        result.put("publishedAt", revision.get("publishedAt"));
        result.put("author", revision.get("author"));
        result.put("note", revision.get("note"));
        result.put("changes", revision.getOrDefault("changes", List.of()));
        result.put("qualityGates", revision.getOrDefault("qualityGates", List.of()));
        return result;
    }

    private void persist(List<Map<String, Object>> revisions) {
        try {
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, JSON.toJSONString(revisions), StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException error) {
            throw new IllegalStateException("knowledge_graph_revision_store_unavailable", error);
        }
    }

    private void audit(String action, String version, String actor) {
        try {
            Path audit = file.resolveSibling("knowledge-graph-audit.jsonl");
            Map<String, Object> entry = Map.of("at", Instant.now().toString(), "action", action, "version", version, "actor", actor);
            Files.createDirectories(audit.getParent());
            Files.writeString(audit, JSON.toJSONString(entry) + System.lineSeparator(), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Graph publication is already durable; an audit-write failure should not revert it.
        }
    }
}
