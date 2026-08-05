package org.example.spatial;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.example.service.GisContextService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Workflow continuation state, persisted in the same session context as spatial data. */
@Service
public class PendingSpatialWorkflowService {
    private static final String KEY = "_pendingSpatialWorkflow";
    private final GisContextService context;

    public PendingSpatialWorkflowService(GisContextService context) { this.context = context; }

    public void remember(String originalRequest, List<String> capabilityIds) {
        context.saveGeoJson(JSON.toJSONString(Map.of(KEY, Map.of("request", originalRequest, "capabilityIds", capabilityIds,
                "createdAt", Instant.now().toString()))));
    }

    public PendingWorkflow peek() {
        try {
            JSONObject saved = JSON.parseObject(context.getGeoJson()).getJSONObject(KEY);
            if (saved == null || saved.getString("request") == null) return null;
            return new PendingWorkflow(saved.getString("request"), saved.getJSONArray("capabilityIds").toJavaList(String.class));
        } catch (Exception ignored) { return null; }
    }

    public void clear() { context.saveGeoJson("{\"" + KEY + "\":null}"); }

    public record PendingWorkflow(String request, List<String> capabilityIds) { }
}
