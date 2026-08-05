package org.example.spatial;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import org.springframework.stereotype.Component;

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
import java.util.UUID;

@Component
public class AnalysisProvenanceService {
    private final Path file = Path.of(System.getProperty("user.dir"), "cityengine-workspace", "analysis-runs.json")
            .toAbsolutePath().normalize();

    public synchronized Map<String, Object> record(
            AnalysisPlan plan,
            DataAvailabilityChecker.Availability availability,
            SpatialPlanValidator.Validation validation,
            Map<String, Object> result) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("runId", "run-" + UUID.randomUUID());
        record.put("recordedAt", Instant.now().toString());
        record.put("planVersion", plan.planVersion());
        record.put("knowledgeGraphVersion", plan.planVersion());
        record.put("knowledgeGraphSource", plan.knowledgeGraphSource());
        record.put("capabilityId", plan.capabilityId());
        record.put("operations", plan.operations());
        record.put("tool", plan.tool());
        record.put("contextVersion", availability.contextVersion());
        record.put("availableData", availability.available());
        record.put("missingData", validation.missingData());
        record.put("validation", validation.status());
        record.put("resultStatus", result == null ? validation.status() : result.getOrDefault("status", "Unknown"));
        if (result != null) {
            record.put("result", new LinkedHashMap<>(result));
            copyIfPresent(result, record, "data_source");
            copyIfPresent(result, record, "gis_backend");
            copyIfPresent(result, record, "limitations");
            copyIfPresent(result, record, "quality");
        }
        persist(record);
        return record;
    }

    public synchronized List<Map<String, Object>> recent(int limit) {
        List<Map<String, Object>> records = new ArrayList<>();
        try {
            if (!Files.isRegularFile(file)) return records;
            JSONArray rows = JSON.parseArray(Files.readString(file, StandardCharsets.UTF_8));
            if (rows != null) {
                for (Object row : rows) {
                    if (row instanceof Map<?, ?> map) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        map.forEach((key, value) -> item.put(String.valueOf(key), value));
                        records.add(item);
                    }
                }
            }
        } catch (Exception ignored) {
            return records;
        }
        int size = Math.max(1, Math.min(limit, records.size()));
        List<Map<String, Object>> result = new ArrayList<>();
        for (int index = records.size() - 1; index >= records.size() - size; index--) {
            result.add(records.get(index));
        }
        return result;
    }

    public synchronized Map<String, Object> latestSuccessful(String memoryId) {
        for (Map<String, Object> record : recent(200)) {
            if ("Valid".equals(record.get("validation")) && "Success".equals(record.get("resultStatus"))) {
                return record;
            }
        }
        return null;
    }

    private void copyIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key)) target.put(key, source.get(key));
    }

    private void persist(Map<String, Object> record) {
        List<Object> rows = new ArrayList<>();
        try {
            if (Files.isRegularFile(file)) {
                JSONArray existing = JSON.parseArray(Files.readString(file, StandardCharsets.UTF_8));
                if (existing != null) rows.addAll(existing);
            }
            rows.add(record);
            if (rows.size() > 200) rows = new ArrayList<>(rows.subList(rows.size() - 200, rows.size()));
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, JSON.toJSONString(rows), StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) {
            // Audit persistence is best effort and must not block analysis results.
        }
    }
}
