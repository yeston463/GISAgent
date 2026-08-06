package org.example.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 全局登录鉴权 + RBAC 集成测试。
 * 覆盖：登录成功/失败、无令牌 401、有效令牌放行、非管理员访问管理接口 403、管理员放行。
 */
@SpringBootTest(properties = {
        "rag.auto-load=false",
        "spatial.knowledge-graph.url=",
        "app.auth.admin-username=admin",
        "app.auth.admin-password=test-admin-pass-123",
        "app.auth.jwt-secret=0123456789abcdef0123456789abcdef0123456789abcdef"
})
@AutoConfigureMockMvc
class AuthIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void login_withValidAdminCredentials_returnsTokenAndAdminRole() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"test-admin-pass-123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    void login_withBadCredentials_returns401() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("bad_credentials"));
    }

    @Test
    void protectedEndpoint_withoutToken_returns401() throws Exception {
        mockMvc.perform(get("/api/gis/context"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthorized"));
    }

    @Test
    void protectedEndpoint_withToken_returns200() throws Exception {
        String token = obtainToken("admin", "test-admin-pass-123");
        mockMvc.perform(get("/api/gis/context").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasAoi").exists());
    }

    @Test
    @WithMockUser(roles = "USER")
    void adminEndpoint_withUserRole_returns403() throws Exception {
        mockMvc.perform(post("/api/agent/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requirement\":\"demo\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminEndpoint_withAdminRole_passesAuthorization() throws Exception {
        // 动态执行默认关闭：到达控制器后返回 404 Disabled（证明鉴权已通过）。
        mockMvc.perform(post("/api/agent/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requirement\":\"demo\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("Disabled"));
    }

    @Test
    @WithMockUser(roles = "USER")
    void userManagement_withUserRole_returns403() throws Exception {
        mockMvc.perform(get("/api/auth/users"))
                .andExpect(status().isForbidden());
    }

    private String obtainToken(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }
}
