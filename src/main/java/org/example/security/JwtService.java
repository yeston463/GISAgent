package org.example.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

/** 签发与解析 JWT。密钥取自配置；未配置时生成随机密钥并告警（生产必须注入）。 */
@Component
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private static final int MIN_SECRET_BYTES = 32;
    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_NAME = "name";

    private final SecretKey key;
    private final String issuer;
    private final Duration ttl;
    private final boolean ephemeralSecret;

    public JwtService(AuthProperties properties) {
        this.issuer = properties.getJwtIssuer();
        this.ttl = Duration.ofMinutes(Math.max(5, properties.getJwtTtlMinutes()));
        byte[] provided = properties.getJwtSecret().getBytes(StandardCharsets.UTF_8);
        if (provided.length < MIN_SECRET_BYTES) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            this.key = Keys.hmacShaKeyFor(random);
            this.ephemeralSecret = true;
            log.warn("[auth] AUTH_JWT_SECRET 未配置或过短（<{} 字节），已生成随机密钥。"
                    + " 重启后所有已签发令牌将失效；生产必须设置强随机密钥。", MIN_SECRET_BYTES);
        } else {
            this.key = Keys.hmacShaKeyFor(provided);
            this.ephemeralSecret = false;
        }
    }

    /** 是否使用运行期随机密钥（非持久化）。 */
    public boolean isEphemeralSecret() {
        return ephemeralSecret;
    }

    /** 令牌有效期（秒）。 */
    public long ttlSeconds() {
        return ttl.getSeconds();
    }

    public String issue(String username, String displayName, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(username)
                .claim(CLAIM_NAME, displayName == null ? username : displayName)
                .claim(CLAIM_ROLE, role)
                .issuer(issuer)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    /** 校验并解析令牌。非法/过期/签发方不符一律抛出 JwtException。 */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .requireIssuer(issuer)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String username(Claims claims) {
        return claims.getSubject();
    }

    public String role(Claims claims) {
        Object value = claims.get(CLAIM_ROLE);
        return value == null ? "USER" : String.valueOf(value);
    }

    public String displayName(Claims claims) {
        Object value = claims.get(CLAIM_NAME);
        return value == null ? username(claims) : String.valueOf(value);
    }

    public long expiresAtMillis(Claims claims) {
        Date expiration = claims.getExpiration();
        return expiration == null ? System.currentTimeMillis() : expiration.getTime();
    }

    /** 校验失败返回 null（调用方应视为未认证）。 */
    public Claims parseOrNull(String token) {
        try {
            return parse(token);
        } catch (JwtException | IllegalArgumentException ignored) {
            return null;
        }
    }

    /** 生成一个可在 .env / 云变量中使用的高强度随机密钥。 */
    public static String generateSecret() {
        byte[] random = new byte[48];
        new SecureRandom().nextBytes(random);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }
}
