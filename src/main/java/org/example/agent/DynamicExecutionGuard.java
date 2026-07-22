package org.example.agent;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * 动态执行接口的鉴权与开关守卫。集中处理“谁能触发危险能力”这一层。
 *
 * 注意：CORS 只限制浏览器跨域，无法阻止 curl 或同机恶意进程，因此这里单独做鉴权。
 * 失败时一律 fail-closed（拒绝），且不泄露具体原因差异（避免给攻击者提示）。
 */
@Component
public class DynamicExecutionGuard {

    private final DynamicExecutionConfig config;

    public DynamicExecutionGuard(DynamicExecutionConfig config) {
        this.config = config;
    }

    /** 功能是否开启。关闭时任何触发路径都拒绝。 */
    public boolean isEnabled() {
        return config.isEnabled();
    }

    /**
     * 校验本次请求是否被允许触发动态执行。
     *
     * @param request 当前 HTTP 请求（用于取令牌头与远端地址）
     * @return true 表示允许
     */
    public boolean authorize(HttpServletRequest request) {
        if (!config.isEnabled()) {
            return false;
        }
        if ("token".equalsIgnoreCase(config.getAuthMode())) {
            return authorizeByToken(request);
        }
        // local 模式：仅允许本机回环地址（或显式配置的 SERVER_ADDRESS）。
        return isAllowedLocalAddress(request.getRemoteAddr());
    }

    private boolean authorizeByToken(HttpServletRequest request) {
        String expected = config.getToken();
        if (expected == null || expected.isBlank()) {
            // 未配置令牌时 fail-closed：拒绝所有请求，避免“空令牌即放行”。
            return false;
        }
        String presented = resolveToken(request);
        if (presented == null) {
            return false;
        }
        // 定长安全比较，避免计时侧信道。
        return constantTimeEquals(presented, expected);
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("X-GIS-Agent-Token");
        if (header != null && !header.isBlank()) {
            return header;
        }
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.startsWith("Bearer ")) {
            return auth.substring("Bearer ".length()).trim();
        }
        return null;
    }

    private boolean isAllowedLocalAddress(String remoteAddr) {
        if (remoteAddr == null) {
            return false;
        }
        if ("127.0.0.1".equals(remoteAddr)
                || "::1".equals(remoteAddr)
                || "0:0:0:0:0:0:0:1".equals(remoteAddr)) {
            return true;
        }
        return remoteAddr.equals(config.getServerAddress());
    }

    private static boolean constantTimeEquals(String a, String b) {
        byte[] ba = a.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (ba.length != bb.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < ba.length; i++) {
            result |= ba[i] ^ bb[i];
        }
        return result == 0;
    }
}
