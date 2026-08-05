package org.example.service;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Short-lived analysis intent retained while the user supplies a requested AOI. */
@Service
public class PendingAnalysisIntentService {
    private final Map<String, String> intents = new ConcurrentHashMap<>();

    public void remember(String sessionId, String intent) {
        if (sessionId != null && !sessionId.isBlank() && intent != null && !intent.isBlank()) {
            intents.put(normalize(sessionId), intent);
        }
    }

    public String consume(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        return intents.remove(normalize(sessionId));
    }

    /** Inspect the outstanding request without clearing it while data is collected. */
    public String peek(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        return intents.get(normalize(sessionId));
    }

    public void clear(String sessionId) {
        if (sessionId != null && !sessionId.isBlank()) intents.remove(normalize(sessionId));
    }

    private String normalize(String sessionId) {
        return sessionId.replaceAll("[^A-Za-z0-9_-]", "_").substring(0, Math.min(120, sessionId.length()));
    }
}
