package org.example.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Optional;

/** 用户源：优先 PostgreSQL，其次引导管理员（环境变量）。 */
@Service
public class AppUserDetailsService implements UserDetailsService {

    private static final Logger log = LoggerFactory.getLogger(AppUserDetailsService.class);

    private final AppUserRepository repository;
    private final AuthProperties properties;
    private final Optional<AppUser> bootstrapAdmin;

    public AppUserDetailsService(AppUserRepository repository, AuthProperties properties,
                                 PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.properties = properties;
        this.bootstrapAdmin = properties.isBootstrapAdminConfigured()
                ? Optional.of(AppUser.bootstrap(properties.getAdminUsername(),
                        passwordEncoder.encode(properties.getAdminPassword())))
                : Optional.empty();
        if (bootstrapAdmin.isEmpty()) {
            log.warn("[auth] AUTH_ADMIN_PASSWORD 未配置：无引导管理员，登录将不可用。"
                    + " 请设置 AUTH_ADMIN_PASSWORD 或在数据库中预置用户。");
        }
    }

    /** 供登录与 /me 使用：返回完整的 AppUser（含显示名、角色）。 */
    public Optional<AppUser> resolve(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        String normalized = username.trim();
        Optional<AppUser> fromDb = repository.findByUsername(normalized);
        if (fromDb.isPresent()) {
            return fromDb;
        }
        if (bootstrapAdmin.isPresent() && bootstrapAdmin.get().username().equalsIgnoreCase(normalized)) {
            return bootstrapAdmin;
        }
        return Optional.empty();
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        AppUser user = resolve(username)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user: " + username));
        return toUserDetails(user);
    }

    /** 是否引导管理员账号。 */
    public boolean isBootstrapAdmin(String username) {
        return bootstrapAdmin.isPresent() && bootstrapAdmin.get().username().equalsIgnoreCase(username);
    }

    /** 是否已配置引导管理员密码。 */
    public boolean isBootstrapAdminConfigured() {
        return bootstrapAdmin.isPresent();
    }

    /** 引导管理员用户名（未配置时返回空串）。 */
    public String bootstrapUsername() {
        return bootstrapAdmin.map(AppUser::username).orElse("");
    }

    /** 当前 token 携带的角色是否仍然有效（吊销/降权检测）。DB 离线时放行（签名仍受密钥保护）。 */
    public boolean isRoleStillValid(String username, String claimedRole) {
        if (isBootstrapAdmin(username)) {
            return true;
        }
        Optional<AppUser> user = repository.findByUsername(username);
        if (user.isEmpty()) {
            return false; // 用户已被删除
        }
        AppUser current = user.get();
        return current.enabled() && current.role().equalsIgnoreCase(claimedRole);
    }

    private UserDetails toUserDetails(AppUser user) {
        return User.withUsername(user.username())
                .password(user.passwordHash())
                .disabled(!user.enabled())
                .authorities("ROLE_" + user.role())
                .build();
    }
}
