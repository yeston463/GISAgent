package org.example.security;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 全局限流：按客户端标识（优先认证用户，其次来源 IP）统计窗口内请求数。
 * 固定窗口计数 + 惰性过期清理。单实例内存实现，分布式部署应替换为 Redis 版本。
 */
@Component
public class RateLimitService {

    private final Map<String, WindowCounter> counters = new ConcurrentHashMap<>();
    private final AuthProperties properties;

    public RateLimitService(AuthProperties properties) {
        this.properties = properties;
    }

    /** 检查给定客户端是否放行。返回 true 表示允许请求，false 表示超限被拒。 */
    public boolean tryAcquire(String clientKey) {
        if (!properties.isRateLimitEnabled() || clientKey == null || clientKey.isBlank()) {
            return true;
        }
        int max = properties.getRateLimitMax();
        long windowSeconds = properties.getRateLimitWindowSeconds();
        long now = System.currentTimeMillis();

        WindowCounter counter = counters.compute(clientKey, (key, existing) -> {
            if (existing == null || existing.isExpired(now, windowSeconds)) {
                return new WindowCounter(now);
            }
            return existing;
        });
        long count = counter.count.incrementAndGet();

        // 惰性清理：间隔触发，避免 Map 无限增长
        if (count % 1000 == 0) {
            sweep(now, windowSeconds);
        }
        return count <= max;
    }

    public void reset(String clientKey) {
        if (clientKey != null) {
            counters.remove(clientKey);
        }
    }

    private void sweep(long now, long windowSeconds) {
        counters.entrySet().removeIf(entry -> entry.getValue().isExpired(now, windowSeconds));
    }

    private static final class WindowCounter {
        final long windowStart;
        final AtomicLong count;

        WindowCounter(long windowStart) {
            this.windowStart = windowStart;
            this.count = new AtomicLong(0);
        }

        boolean isExpired(long now, long windowSeconds) {
            return now - windowStart >= Duration.ofSeconds(windowSeconds).toMillis();
        }
    }
}
