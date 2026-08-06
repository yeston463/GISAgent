package org.example.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 登录、会话、密码与用户管理（管理端）。 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final AppUserDetailsService userDetailsService;
    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptService loginAttemptService;

    public AuthController(AuthenticationManager authenticationManager, JwtService jwtService,
                          AppUserDetailsService userDetailsService, AppUserRepository userRepository,
                          PasswordEncoder passwordEncoder, LoginAttemptService loginAttemptService) {
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.loginAttemptService = loginAttemptService;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@Valid @RequestBody LoginRequest request,
                                                     HttpServletRequest httpRequest) {
        String ip = httpRequest.getRemoteAddr();
        String username = request.username() == null ? "" : request.username().trim();
        if (loginAttemptService.isLocked(username, ip)) {
            log.warn("[auth] 登录被临时锁定: user={} ip={}", username, ip);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of(
                    "status", "Locked", "code", "too_many_attempts",
                    "message", "尝试次数过多，请 15 分钟后再试。"));
        }
        try {
            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(username, request.password()));
            loginAttemptService.reset(username, ip);
            AppUser user = resolveFromAuthentication(authentication);
            String token = jwtService.issue(user.username(), user.displayName(), user.role());
            log.info("[auth] 登录成功: user={} role={} ip={}", user.username(), user.role(), ip);
            return ResponseEntity.ok(Map.of(
                    "status", "Success",
                    "tokenType", "Bearer",
                    "token", token,
                    "expiresInSeconds", jwtService.ttlSeconds(),
                    "username", user.username(),
                    "displayName", user.displayName(),
                    "role", user.role()));
        } catch (BadCredentialsException | DisabledException error) {
            loginAttemptService.recordFailure(username, ip);
            log.warn("[auth] 登录失败: user={} ip={} reason={}", username, ip,
                    error.getClass().getSimpleName());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "status", "Unauthorized", "code", "bad_credentials",
                    "message", "用户名或密码错误。"));
        }
    }

    @GetMapping("/me")
    public ResponseEntity<Map<String, Object>> me(@AuthenticationPrincipal String username) {
        // JwtAuthenticationFilter 将令牌主体（用户名）设为 Authentication principal。
        if (username == null || username.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "status", "Unauthorized", "code", "unauthorized"));
        }
        Optional<AppUser> user = userDetailsService.resolve(username);
        if (user.isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "status", "Unauthorized", "code", "unauthorized"));
        }
        AppUser current = user.get();
        return ResponseEntity.ok(Map.of(
                "username", current.username(),
                "displayName", current.displayName(),
                "role", current.role(),
                "source", current.source()));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout() {
        // 无状态 JWT：服务端无需会话，由客户端丢弃令牌。
        return ResponseEntity.noContent().build();
    }

    /** 修改当前用户密码（引导管理员不可改，需通过环境变量/数据库直接维护）。 */
    @PostMapping("/password")
    public ResponseEntity<Map<String, Object>> changePassword(
            @Valid @RequestBody ChangePasswordRequest request,
            @AuthenticationPrincipal String username) {
        if (username == null || username.isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "status", "Unauthorized", "code", "unauthorized"));
        }
        if (userDetailsService.isBootstrapAdmin(username)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "code", "bootstrap_admin_password_immutable",
                    "message", "引导管理员密码通过 AUTH_ADMIN_PASSWORD 管理，请勿在此修改。"));
        }
        Optional<AppUser> user = userRepository.findByUsername(username);
        if (user.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "code", "user_not_found", "message", "用户不存在。"));
        }
        if (!passwordEncoder.matches(request.currentPassword(), user.get().passwordHash())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "code", "wrong_current_password", "message", "当前密码不正确。"));
        }
        userRepository.updatePassword(username, passwordEncoder.encode(request.newPassword()));
        log.info("[auth] 密码已修改: user={}", username);
        return ResponseEntity.ok(Map.of("status", "Success"));
    }

    // ===== 管理员：用户管理 =====

    @GetMapping("/users")
    public ResponseEntity<Map<String, Object>> listUsers() {
        List<Map<String, Object>> users;
        try {
            users = userRepository.findAll().stream()
                    .map(user -> {
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("id", user.id());
                        entry.put("username", user.username());
                        entry.put("displayName", user.displayName());
                        entry.put("role", user.role());
                        entry.put("enabled", user.enabled());
                        entry.put("source", user.source());
                        return entry;
                    })
                    .collect(java.util.stream.Collectors.toList());
        } catch (RuntimeException error) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "status", "Unavailable", "code", "user_store_unavailable",
                    "message", "用户存储不可用（数据库离线）。"));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("users", users);
        if (userDetailsService.isBootstrapAdminConfigured()) {
            response.put("bootstrapAdmin", userDetailsService.bootstrapUsername());
        }
        return ResponseEntity.ok(response);
    }

    @PostMapping("/users")
    public ResponseEntity<Map<String, Object>> createUser(@Valid @RequestBody CreateUserRequest request) {
        String username = request.username().trim();
        if (userRepository.isAvailable() && userRepository.exists(username)) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "code", "user_exists", "message", "用户名已存在。"));
        }
        String role = AppUser.ROLE_ADMIN.equalsIgnoreCase(request.role()) ? AppUser.ROLE_ADMIN : AppUser.ROLE_USER;
        try {
            userRepository.insert(username, passwordEncoder.encode(request.password()),
                    request.displayName(), role, AppUser.SOURCE_DB);
        } catch (RuntimeException error) {
            log.error("[auth] 创建用户失败: user={}", username, error);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "code", "user_store_unavailable", "message", "用户存储不可用（数据库离线）。"));
        }
        log.info("[auth] 创建用户: user={} role={}", username, role);
        return ResponseEntity.ok(Map.of("status", "Success", "username", username, "role", role));
    }

    @DeleteMapping("/users/{username}")
    public ResponseEntity<Map<String, Object>> deleteUser(@PathVariable String username) {
        if (userDetailsService.isBootstrapAdmin(username)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "code", "cannot_delete_bootstrap_admin",
                    "message", "引导管理员不可通过接口删除。"));
        }
        try {
            boolean deleted = userRepository.deleteByUsername(username);
            if (!deleted) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                        "code", "user_not_found", "message", "用户不存在。"));
            }
        } catch (RuntimeException error) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "code", "user_store_unavailable", "message", "用户存储不可用（数据库离线）。"));
        }
        log.info("[auth] 删除用户: user={}", username);
        return ResponseEntity.ok(Map.of("status", "Success", "deleted", username));
    }

    private AppUser resolveFromAuthentication(Authentication authentication) {
        String username = authentication.getName();
        Optional<AppUser> user = userDetailsService.resolve(username);
        if (user.isEmpty()) {
            throw new BadCredentialsException("Unknown user");
        }
        return user.get();
    }

    public record LoginRequest(
            @NotBlank(message = "用户名不能为空") @Size(max = 64) String username,
            @NotBlank(message = "密码不能为空") @Size(max = 128) String password) {
    }

    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 8, max = 128)
            @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "密码需同时包含字母和数字")
            String newPassword) {
    }

    public record CreateUserRequest(
            @NotBlank @Size(min = 2, max = 64) String username,
            @NotBlank @Size(min = 8, max = 128)
            @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "密码需同时包含字母和数字")
            String password,
            @Size(max = 120) String displayName,
            @Pattern(regexp = "(?i)^(ADMIN|USER)$", message = "角色只能是 ADMIN 或 USER") String role) {
    }
}
