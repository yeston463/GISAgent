package org.example.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/** 全局登录鉴权配置（前缀 app.auth，均由环境变量注入）。 */
@ConfigurationProperties(prefix = "app.auth")
public class AuthProperties {

    /** JWT 签名密钥。生产必须通过 AUTH_JWT_SECRET 注入，长度至少 32 字节。 */
    private String jwtSecret = "";

    /** 令牌有效期（分钟）。 */
    private long jwtTtlMinutes = 720;

    /** 令牌签发方标识。 */
    private String jwtIssuer = "lc4j-backend";

    /** 引导管理员用户名（数据库可用前兜底，也用于首次登录）。 */
    private String adminUsername = "admin";

    /** 引导管理员密码。为空时启动生成随机密码并告警。 */
    private String adminPassword = "";

    /** 允许的跨域来源。生产只允许正式域名。 */
    private List<String> corsAllowedOrigins = new ArrayList<>();

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret == null ? "" : jwtSecret.trim();
    }

    public long getJwtTtlMinutes() {
        return jwtTtlMinutes;
    }

    public void setJwtTtlMinutes(long jwtTtlMinutes) {
        this.jwtTtlMinutes = jwtTtlMinutes;
    }

    public String getJwtIssuer() {
        return jwtIssuer;
    }

    public void setJwtIssuer(String jwtIssuer) {
        this.jwtIssuer = jwtIssuer;
    }

    public String getAdminUsername() {
        return adminUsername;
    }

    public void setAdminUsername(String adminUsername) {
        this.adminUsername = adminUsername == null ? "" : adminUsername.trim();
    }

    public String getAdminPassword() {
        return adminPassword;
    }

    public void setAdminPassword(String adminPassword) {
        this.adminPassword = adminPassword == null ? "" : adminPassword;
    }

    public List<String> getCorsAllowedOrigins() {
        return corsAllowedOrigins;
    }

    public void setCorsAllowedOrigins(List<String> corsAllowedOrigins) {
        this.corsAllowedOrigins = corsAllowedOrigins == null ? new ArrayList<>() : corsAllowedOrigins;
    }

    public boolean isBootstrapAdminConfigured() {
        return adminPassword != null && !adminPassword.isBlank();
    }
}
