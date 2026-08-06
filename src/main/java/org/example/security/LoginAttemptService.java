package org.example.security;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录暴力破解防护：按“用户名+来源IP”记录失败次数，超限后锁定一段时间。
 * 单实例内存实现（开发/单节点够用）；分布式部署应替换为 Redis 版本。
 */
@Component
public class LoginAttemptService {

    private static final int MAX_ATTEMPTS = 5;
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private static final Duration LOCKOUT = Duration.ofMinutes(15);

    private final Map<String, List<Instant>> failures = new ConcurrentHashMap<>();

    public boolean isLocked(String username, String ip) {
        List<Instant> entries = failures.get(key(username, ip));
        if (entries == null || entries.isEmpty()) {
            return false;
        }
        long recent = entries.stream()
                .filter(instant -> instant.isAfter(Instant.now().minus(WINDOW)))
                .count();
        return recent >= MAX_ATTEMPTS;
    }

    public void recordFailure(String username, String ip) {
        String key = key(username, ip);
        failures.compute(key, (k, existing) -> {
            List<Instant> current = existing == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(existing);
            current.add(Instant.now());
            current.removeIf(instant -> instant.isBefore(Instant.now().minus(WINDOW)));
            return current;
        });
    }

    public void reset(String username, String ip) {
        failures.remove(key(username, ip));
    }

    private String key(String username, String ip) {
        return (username == null ? "" : username.toLowerCase()) + "|" + (ip == null ? "" : ip);
    }
}
