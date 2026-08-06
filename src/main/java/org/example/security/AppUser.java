package org.example.security;

/** 应用用户（数据库 app_users 表或引导管理员）。 */
public record AppUser(
        Long id,
        String username,
        String passwordHash,
        String displayName,
        String role,
        boolean enabled,
        String source) {

    public static final String ROLE_USER = "USER";
    public static final String ROLE_ADMIN = "ADMIN";
    public static final String SOURCE_DB = "db";
    public static final String SOURCE_BOOTSTRAP = "bootstrap";

    public boolean isAdmin() {
        return ROLE_ADMIN.equalsIgnoreCase(role);
    }

    public static AppUser bootstrap(String username, String passwordHash) {
        return new AppUser(null, username, passwordHash, username, ROLE_ADMIN, true, SOURCE_BOOTSTRAP);
    }
}
