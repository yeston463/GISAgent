package org.example.spatial;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** In-process, bounded observability for model-based spatial route selection. */
@Component
public class SpatialRoutingTelemetry {
    private static final int MAX_RECENT = 100;
    private final AtomicLong deepSeekCalls = new AtomicLong();
    private final AtomicLong deepSeekFailures = new AtomicLong();
    private final AtomicLong fallbackCalls = new AtomicLong();
    private final AtomicLong unavailableCalls = new AtomicLong();
    private final ArrayDeque<Map<String, Object>> recent = new ArrayDeque<>();

    public void record(String provider, String status, long elapsedMs, String diagnostic) {
        switch (provider) {
            case "deepseek" -> deepSeekCalls.incrementAndGet();
            case "qwen_fallback" -> fallbackCalls.incrementAndGet();
            default -> unavailableCalls.incrementAndGet();
        }
        if (!"success".equals(status) && "deepseek".equals(provider)) deepSeekFailures.incrementAndGet();
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("at", Instant.now().toString());
        event.put("provider", provider);
        event.put("status", status);
        event.put("elapsedMs", elapsedMs);
        if (diagnostic != null && !diagnostic.isBlank()) event.put("diagnostic", diagnostic);
        synchronized (recent) {
            recent.addLast(event);
            while (recent.size() > MAX_RECENT) recent.removeFirst();
        }
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deepseekCalls", deepSeekCalls.get());
        result.put("deepseekFailures", deepSeekFailures.get());
        result.put("fallbackCalls", fallbackCalls.get());
        result.put("unavailableCalls", unavailableCalls.get());
        synchronized (recent) { result.put("recent", new ArrayList<>(recent)); }
        return result;
    }
}
