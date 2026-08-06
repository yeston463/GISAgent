package org.example.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 应用用户存储（PostgreSQL app_users 表）。数据库离线时自动降级（开发环境兜底），
 * 此时登录仅依赖引导管理员。生产环境必须保证数据库可达并预置管理员。
 */
@Component
public class AppUserRepository implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AppUserRepository.class);

    private static final String TABLE = "app_users";
    private static final String COLUMNS = "id, username, password_hash, display_name, role, enabled, source";

    private final JdbcTemplate jdbcTemplate;

    public AppUserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            createTableIfNeeded();
            log.info("[auth] app_users 表就绪（PostgreSQL 用户存储可用）。");
        } catch (RuntimeException error) {
            log.warn("[auth] 无法初始化 app_users 表（数据库离线？），登录仅依赖引导管理员。原因：{}",
                    safeMessage(error));
        }
    }

    private void createTableIfNeeded() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    id            BIGSERIAL PRIMARY KEY,
                    username      VARCHAR(64)  NOT NULL UNIQUE,
                    password_hash VARCHAR(100) NOT NULL,
                    display_name  VARCHAR(120),
                    role          VARCHAR(16)  NOT NULL DEFAULT 'USER',
                    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
                    source        VARCHAR(16)  NOT NULL DEFAULT 'db',
                    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
                    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
                )
                """.formatted(TABLE));
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_app_users_username ON %s (username)".formatted(TABLE));
    }

    public boolean isAvailable() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return true;
        } catch (DataAccessException error) {
            return false;
        }
    }

    public Optional<AppUser> findByUsername(String username) {
        try {
            List<AppUser> rows = jdbcTemplate.query(
                    "SELECT " + COLUMNS + " FROM " + TABLE + " WHERE username = ?",
                    (rs, rowNum) -> new AppUser(
                            rs.getLong("id"),
                            rs.getString("username"),
                            rs.getString("password_hash"),
                            rs.getString("display_name"),
                            rs.getString("role"),
                            rs.getBoolean("enabled"),
                            rs.getString("source")),
                    username);
            return rows.stream().findFirst();
        } catch (DataAccessException error) {
            log.warn("[auth] 查询用户 {} 失败（数据库离线？），按未找到处理。", username);
            return Optional.empty();
        }
    }

    public List<AppUser> findAll() {
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM " + TABLE + " ORDER BY username",
                (rs, rowNum) -> new AppUser(
                        rs.getLong("id"),
                        rs.getString("username"),
                        rs.getString("password_hash"),
                        rs.getString("display_name"),
                        rs.getString("role"),
                        rs.getBoolean("enabled"),
                        rs.getString("source")));
    }

    public boolean exists(String username) {
        try {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + TABLE + " WHERE username = ?", Integer.class, username);
            return count != null && count > 0;
        } catch (DataAccessException error) {
            return false;
        }
    }

    public void insert(String username, String passwordHash, String displayName, String role, String source) {
        jdbcTemplate.update("""
                        INSERT INTO %s (username, password_hash, display_name, role, enabled, source)
                        VALUES (?, ?, ?, ?, TRUE, ?)
                        """.formatted(TABLE),
                username, passwordHash, displayName == null || displayName.isBlank() ? username : displayName,
                role, source);
    }

    public void updatePassword(String username, String passwordHash) {
        jdbcTemplate.update(
                "UPDATE " + TABLE + " SET password_hash = ?, updated_at = now() WHERE username = ?",
                passwordHash, username);
    }

    public void updateRole(String username, String role) {
        jdbcTemplate.update(
                "UPDATE " + TABLE + " SET role = ?, updated_at = now() WHERE username = ?",
                role, username);
    }

    public boolean deleteByUsername(String username) {
        return jdbcTemplate.update("DELETE FROM " + TABLE + " WHERE username = ?", username) > 0;
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
