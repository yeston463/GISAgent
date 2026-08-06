package org.example.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * 全局限流过滤器：位于 JWT 过滤器之前，按客户端标识（认证用户名优先，否则来源 IP）
 * 计数窗口请求，超限返回 429。公开端点同样受限流保护（登录/健康检查除外）。
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitService rateLimitService;
    private final AuthProperties properties;
    private final ObjectMapper objectMapper;

    public RateLimitFilter(RateLimitService rateLimitService, AuthProperties properties, ObjectMapper objectMapper) {
        this.rateLimitService = rateLimitService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.isRateLimitEnabled()) {
            return true;
        }
        if (properties.isRateLimitExcludeHealthCheck() && isHealthCheck(request)) {
            return true;
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String clientKey = resolveClientKey(request);
        if (!rateLimitService.tryAcquire(clientKey)) {
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.setHeader("Retry-After", String.valueOf(properties.getRateLimitWindowSeconds()));
            objectMapper.writeValue(response.getWriter(), Map.of(
                    "status", "Too Many Requests",
                    "code", "rate_limited",
                    "message", "请求过于频繁，请稍后再试。"));
            return;
        }
        chain.doFilter(request, response);
    }

    private String resolveClientKey(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)) {
            String name = authentication.getName();
            if (name != null && !name.isBlank()) {
                return "user:" + name;
            }
        }
        return "ip:" + clientIp(request);
    }

    private boolean isHealthCheck(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri.startsWith("/actuator/health");
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            String first = comma >= 0 ? forwarded.substring(0, comma) : forwarded;
            return first.trim();
        }
        String remote = request.getRemoteAddr();
        return remote == null ? "unknown" : remote;
    }
}
