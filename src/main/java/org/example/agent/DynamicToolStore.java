package org.example.agent;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Durable metadata/code store for generated tools. Local by default. */
@Component
public class DynamicToolStore {
    private final Path file;

    public DynamicToolStore() {
        String configured = System.getenv("DYNAMIC_TOOL_STORE");
        this.file = Path.of(configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.dir"), "cityengine-workspace", "dynamic-tools.json").toString()
                : configured).toAbsolutePath().normalize();
    }

    public synchronized List<Map<String, Object>> load() {
        return loadAll().stream()
                .filter(row -> !Boolean.FALSE.equals(row.get("active")))
                .toList();
    }

    public synchronized List<Map<String, Object>> history(String name) {
        return loadAll().stream()
                .filter(row -> name.equals(String.valueOf(row.get("name"))))
                .toList();
    }

    public synchronized Map<String, Object> rollback(String name, long version) {
        List<Map<String, Object>> rows = new ArrayList<>(loadAll());
        Map<String, Object> selected = null;
        for (Map<String, Object> row : rows) {
            if (name.equals(String.valueOf(row.get("name")))
                    && String.valueOf(row.get("version")).equals(String.valueOf(version))) {
                selected = row;
            }
        }
        if (selected == null) return null;
        for (Map<String, Object> row : rows) {
            if (name.equals(String.valueOf(row.get("name")))) {
                row.put("active", row == selected);
            }
        }
        write(rows);
        return new LinkedHashMap<>(selected);
    }

    private synchronized List<Map<String, Object>> loadAll() {
        if (!Files.isRegularFile(file)) return List.of();
        try {
            JSONArray rows = JSON.parseArray(Files.readString(file, StandardCharsets.UTF_8));
            List<Map<String, Object>> result = new ArrayList<>();
            if (rows != null) {
                for (Object row : rows) {
                    if (row instanceof JSONObject json) result.add(new LinkedHashMap<>(json.getInnerMap()));
                }
            }
            return result;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    public synchronized void upsert(String name, String description, String code, JSONObject context) {
        List<Map<String, Object>> rows = new ArrayList<>(loadAll());
        for (Map<String, Object> row : rows) {
            if (name.equals(String.valueOf(row.get("name")))) row.put("active", false);
        }
        rows.add(new LinkedHashMap<>(Map.of("name", name, "description", description == null ? "" : description,
                "code", code, "context", context == null ? new JSONObject() : context,
                "version", System.currentTimeMillis(), "active", true)));
        write(rows);
    }

    public synchronized void remove(String name) {
        List<Map<String, Object>> rows = new ArrayList<>(loadAll());
        if (rows.removeIf(row -> name.equals(String.valueOf(row.get("name"))))) write(rows);
    }

    private void write(List<Map<String, Object>> rows) {
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, JSON.toJSONString(rows), StandardCharsets.UTF_8);
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) {
            // Persistence is best effort; in-memory execution remains available.
        }
    }
}
