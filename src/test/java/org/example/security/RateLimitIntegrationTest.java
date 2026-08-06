package org.example.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全局限流测试：窗口内超过阈值的请求返回 429。
 * 使用独立配置（低阈值 + 排除健康检查），避免干扰其它测试。
 */
@SpringBootTest(properties = {
        "rag.auto-load=false",
        "spatial.knowledge-graph.url=",
        "app.auth.admin-username=admin",
        "app.auth.admin-password=test-admin-pass-123",
        "app.auth.jwt-secret=0123456789abcdef0123456789abcdef0123456789abcdef",
        "app.auth.rate-limit-enabled=true",
        "app.auth.rate-limit-max=3",
        "app.auth.rate-limit-window-seconds=60",
        "app.auth.rate-limit-exclude-health-check=true"
})
@AutoConfigureMockMvc
class RateLimitIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void exceedingLimit_returns429WithRetryAfter() throws Exception {
        // 受保护端点（无令牌返回 401），前 3 次限流放行，由鉴权返回 401
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/gis/context"))
                    .andExpect(status().isUnauthorized());
        }
        // 第 4 次触发限流 429
        mockMvc.perform(get("/api/gis/context"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("rate_limited"))
                .andExpect(header().string("Retry-After", "60"));
    }

    @Test
    void healthCheck_isExcludedFromRateLimit() throws Exception {
        // 健康检查即使超过阈值也不应被限流（DB/Redis 离线时返回 503 而非 429 即可证明未限流）
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(get("/actuator/health"))
                    .andExpect(result -> {
                        int status = result.getResponse().getStatus();
                        if (status == 429) {
                            throw new AssertionError("健康检查不应被限流，但返回了 429");
                        }
                    });
        }
    }
}
