package org.example.spatial;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Small local experience memory. Stores only plan outcome, never raw spatial data. */
@Service
public class SpatialWorkflowExperienceStore {
    private final Path file = Path.of(System.getProperty("user.dir"), "cityengine-workspace", "spatial-workflow-experiences.json");
    public synchronized void record(String request, List<String> capabilities, String status) {
        try { List<Object> rows = Files.isRegularFile(file) ? new ArrayList<>(JSON.parseArray(Files.readString(file, StandardCharsets.UTF_8))) : new ArrayList<>();
            rows.add(Map.of("at", Instant.now().toString(), "request", request, "capabilities", capabilities, "status", status));
            if (rows.size() > 100) rows = new ArrayList<>(rows.subList(rows.size()-100, rows.size())); Files.createDirectories(file.getParent()); Files.writeString(file, JSON.toJSONString(rows), StandardCharsets.UTF_8);
        } catch (Exception ignored) { }
    }
}
