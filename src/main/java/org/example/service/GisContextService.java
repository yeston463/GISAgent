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
import java.util.HashMap;
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
    /** 落盘单会话上限：超过此大小（约 1MB JSON）的上下文只驻留内存，不写磁盘，
     *  避免多会话大上下文累积后 persist() 全量序列化触发堆 OutOfMemoryError。 */
    private static final int MAX_PERSIST_BYTES = 1_000_000;
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

    public record LockEntry(String userId, long expiresAt) {}

    private final Map<String, LockEntry> editLocks = new ConcurrentHashMap<>();

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

    /** Remove only the bundled demo payload from a session; never clear user data. */
    public synchronized boolean clearDemoContext(String sessionId) {
        String key = normalizeSession(sessionId);
        JSONObject context = JSON.parseObject(contexts.getOrDefault(key, "{}"));
        if (context == null || !context.containsKey("demoId")) {
            return false;
        }
        context.clear();
        context.put("contextVersion", 0);
        contexts.put(key, context.toJSONString());
        persist();
        return true;
    }

    public boolean acquireEditLock(String memoryId, String userId, int ttlSeconds) {
        String key = normalizeSession(memoryId);
        long now = System.currentTimeMillis();
        long expiresAt = now + (long) ttlSeconds * 1000;
        LockEntry newEntry = new LockEntry(userId, expiresAt);
        return editLocks.compute(key, (k, existing) -> {
            if (existing != null && existing.expiresAt() > now) {
                return existing;
            }
            return newEntry;
        }) == newEntry;
    }

    public boolean releaseEditLock(String memoryId, String userId) {
        String key = normalizeSession(memoryId);
        LockEntry current = editLocks.get(key);
        if (current == null) {
            return false;
        }
        if (!current.userId().equals(userId)) {
            return false;
        }
        return editLocks.remove(key, current);
    }

    public Map<String, Object> getEditStatus(String memoryId) {
        String key = normalizeSession(memoryId);
        long now = System.currentTimeMillis();
        LockEntry entry = editLocks.get(key);
        Map<String, Object> status = new HashMap<>();
        if (entry == null || entry.expiresAt() <= now) {
            status.put("locked", false);
            status.put("userId", null);
            status.put("remainingTtl", 0L);
            if (entry != null) {
                editLocks.remove(key, entry);
            }
        } else {
            status.put("locked", true);
            status.put("userId", entry.userId());
            status.put("remainingTtl", entry.expiresAt() - now);
        }
        return status;
    }

    public SaveResult updateWithVersion(String memoryId, String data, long expectedVersion) {
        String key = normalizeSession(memoryId);
        LockEntry lock = editLocks.get(key);
        if (lock != null && lock.expiresAt() > System.currentTimeMillis()) {
            // Lock is active — only the lock holder may update (caller must pass userId via overload)
        }
        return saveGeoJson(key, data, expectedVersion);
    }

    public SaveResult updateWithVersion(String memoryId, String userId, String data, long expectedVersion) {
        String key = normalizeSession(memoryId);
        LockEntry lock = editLocks.get(key);
        if (lock != null && lock.expiresAt() > System.currentTimeMillis()) {
            if (!lock.userId().equals(userId)) {
                long currentVersion = getContextVersion(key);
                return new SaveResult(currentVersion, true);
            }
        }
        return saveGeoJson(key, data, expectedVersion);
    }

    private void persist() {
        try {
            Files.createDirectories(contextFile.getParent());
            Path temp = contextFile.resolveSibling(contextFile.getFileName() + ".tmp");
            // 只落盘小上下文：城市级建筑数据（单会话可达数 MB）若全量序列化，
            // 在多会话累积后会让 persist() 的 JSON.toJSONString 触发堆 OOM。
            // 内存中的 contexts 仍保留完整数据供分析使用；超大会话不落盘，
            // 重启后该会话需重新上传数据（与 DataStore 缓存语义一致）。
            Map<String, String> persistable = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, String> entry : contexts.entrySet()) {
                if (entry.getValue() != null && entry.getValue().length() <= MAX_PERSIST_BYTES) {
                    persistable.put(entry.getKey(), entry.getValue());
                }
            }
            Files.writeString(temp, JSON.toJSONString(persistable), StandardCharsets.UTF_8);
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
