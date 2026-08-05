package org.example.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session-scoped GIS context with restart persistence.
 *
 * A planning request can refer to "the current 500m analysis" after a Java
 * restart. Context therefore cannot live in one process-wide String.
 */
@Service
public class GisContextService {
    private static final String DEFAULT_SESSION = "default";
    private final Map<String, String> contexts = new ConcurrentHashMap<>();
    private final ThreadLocal<String> activeSession = ThreadLocal.withInitial(() -> DEFAULT_SESSION);
    private final Path contextFile;

    public GisContextService() {
        this(resolveContextFile());
    }

    public GisContextService(Path contextFile) {
        this.contextFile = contextFile;
    }

    public record SaveResult(long contextVersion, boolean conflict) {}

    @PostConstruct
    public synchronized void restore() {
        contexts.putIfAbsent(DEFAULT_SESSION, "{}");
        if (!Files.isRegularFile(contextFile)) {
            return;
        }
        try {
            JSONObject saved = JSON.parseObject(Files.readString(contextFile, StandardCharsets.UTF_8));
            if (saved != null) {
                saved.forEach((sessionId, value) -> {
                    if (value instanceof String json && !json.isBlank()) {
                        contexts.put(normalizeSession(sessionId), json);
                    }
                });
            }
        } catch (Exception ignored) {
            // A damaged local cache must never prevent the GIS service starting.
        }
    }

    public void activateSession(String sessionId) {
        activeSession.set(normalizeSession(sessionId));
    }

    public String activeSessionId() {
        return activeSession.get();
    }

    public synchronized SaveResult saveGeoJson(String newJson) {
        return mergeGeoJson(activeSession.get(), newJson);
    }

    public synchronized SaveResult saveGeoJson(String sessionId, String newJson) {
        return mergeGeoJson(sessionId, newJson);
    }

    public synchronized SaveResult saveGeoJson(String sessionId, String newJson, long expectedVersion) {
        return updateGeoJson(sessionId, newJson, expectedVersion, true);
    }

    private SaveResult mergeGeoJson(String sessionId, String newJson) {
        return updateGeoJson(sessionId, newJson, -1, false);
    }

    private SaveResult updateGeoJson(
            String sessionId,
            String newJson,
            long expectedVersion,
            boolean incrementVersion) {
        JSONObject newObj = JSON.parseObject(newJson);
        String key = normalizeSession(sessionId);
        JSONObject context = JSON.parseObject(contexts.getOrDefault(key, "{}"));
        long currentVersion = context.getLongValue("contextVersion");
        if (expectedVersion >= 0 && expectedVersion != currentVersion) {
            return new SaveResult(currentVersion, true);
        }
        if (newObj.containsKey("aoi")) {
            context.remove("buildings");
        }
        newObj.remove("contextVersion");
        context.putAll(newObj);
        long nextVersion = incrementVersion ? currentVersion + 1 : currentVersion;
        context.put("contextVersion", nextVersion);
        contexts.put(key, context.toJSONString());
        persist();
        return new SaveResult(nextVersion, false);
    }

    public String getGeoJson() {
        return contexts.getOrDefault(activeSession.get(), "{}");
    }

    public String getGeoJson(String sessionId) {
        return contexts.getOrDefault(normalizeSession(sessionId), "{}");
    }

    public long getContextVersion(String sessionId) {
        JSONObject context = JSON.parseObject(getGeoJson(sessionId));
        return context == null ? 0 : context.getLongValue("contextVersion");
    }

    private void persist() {
        try {
            Files.createDirectories(contextFile.getParent());
            Path temp = contextFile.resolveSibling(contextFile.getFileName() + ".tmp");
            Files.writeString(temp, JSON.toJSONString(contexts), StandardCharsets.UTF_8);
            Files.move(temp, contextFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) {
            // Context remains usable in memory if the optional local cache fails.
        }
    }

    public static String normalizeSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return DEFAULT_SESSION;
        }
        String normalized = sessionId.replaceAll("[^A-Za-z0-9_-]", "_");
        return normalized.substring(0, Math.min(120, normalized.length()));
    }

    private static Path resolveContextFile() {
        String configured = System.getenv("GIS_CONTEXT_FILE");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.dir"), "cityengine-workspace", "gis-context-sessions.json")
                .toAbsolutePath().normalize();
    }
}
