package org.example.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;
import java.util.Map;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig {

    private final AuthProperties properties;
    private final ObjectMapper objectMapper;
    private final JwtService jwtService;
    private final AppUserDetailsService userDetailsService;

    public SecurityConfig(AuthProperties properties, ObjectMapper objectMapper,
                          JwtService jwtService, AppUserDetailsService userDetailsService) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        JwtAuthenticationFilter jwtFilter = new JwtAuthenticationFilter(jwtService, userDetailsService);

        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 登录、退出、健康检查、静态重定向公开
                        .requestMatchers("/api/auth/login", "/api/auth/logout", "/error").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/", "/index.html").permitAll()
                        // 管理员专属：知识库写操作、GIS 数据导入/上传、动态执行、能力图谱发布
                        .requestMatchers(HttpMethod.POST, "/api/knowledge/upload", "/api/knowledge/reload").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/gis/data-file", "/api/gis/data-discovery/import").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/agent/execute").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/agent/tools/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/agent/tools/*/rollback").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST,
                                "/api/agent/capabilities/publish",
                                "/api/agent/capabilities/candidates/preview",
                                "/api/agent/capabilities/test-intents").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/agent/capabilities/rollback/*").hasRole("ADMIN")
                        // 用户管理仅管理员
                        .requestMatchers("/api/auth/users/**").hasRole("ADMIN")
                        // 其余 /api 与 /analysis 需登录（任意角色）
                        .requestMatchers("/api/**", "/analysis/**").authenticated()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, e) -> {
                            response.setStatus(401);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.setCharacterEncoding("UTF-8");
                            objectMapper.writeValue(response.getWriter(), Map.of(
                                    "status", "Unauthorized",
                                    "code", "unauthorized",
                                    "message", "身份认证失败或已失效，请重新登录。"));
                        })
                        .accessDeniedHandler((request, response, e) -> {
                            response.setStatus(403);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            response.setCharacterEncoding("UTF-8");
                            objectMapper.writeValue(response.getWriter(), Map.of(
                                    "status", "Forbidden",
                                    "code", "forbidden",
                                    "message", "权限不足：该操作需要管理员角色。"));
                        }))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        List<String> origins = properties.getCorsAllowedOrigins();
        if (origins == null || origins.isEmpty()) {
            configuration.setAllowedOriginPatterns(List.of("*"));
        } else {
            configuration.setAllowedOrigins(origins);
        }
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}