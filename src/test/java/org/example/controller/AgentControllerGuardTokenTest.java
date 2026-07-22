package org.example.controller;

import com.alibaba.fastjson.JSONObject;
import org.example.agent.AgentLoopService;
import org.example.agent.DynamicCodeGenerator;
import org.example.agent.DynamicExecutionConfig;
import org.example.agent.DynamicExecutionGuard;
import org.example.memory.PgVectorMemoryStore;
import org.example.tools.DynamicToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 控制器层测试：覆盖动态执行鉴权中间件（token 模式）。
 *
 * 与 local 测试同理（绕过不稳定的 @WebMvcTest 映射注册），直接实例化控制器 +
 * 真实 DynamicExecutionGuard（config: authMode=token, token=test-token）。
 *
 * 验证：
 *  - 正确令牌（X-GIS-Agent-Token / Authorization: Bearer）放行并触发危险操作；
 *  - 错误令牌 / 缺失令牌拒绝（status=Forbidden）。
 * token 模式下裁决与来源 IP 无关（remote 设为非本机 10.x 仍可凭令牌通过），证明
 * “CORS 只挡浏览器、挡不住 curl” 的缺口由独立的令牌鉴权补齐。
 */
class AgentControllerGuardTokenTest {

    static final String TOKEN = "test-token";

    private AgentController controller;
    private DynamicCodeGenerator codeGenerator;
    private DynamicToolRegistry toolRegistry;
    private DynamicExecutionGuard guard;

    @BeforeEach
    void setUp() {
        codeGenerator = mock(DynamicCodeGenerator.class);
        AgentLoopService agentLoopService = mock(AgentLoopService.class);
        PgVectorMemoryStore memoryStore = mock(PgVectorMemoryStore.class);
        toolRegistry = mock(DynamicToolRegistry.class);

        DynamicExecutionConfig config = DynamicExecutionConfig.defaults(); // enabled, local(被覆盖), 127.0.0.1
        setField(config, "authMode", "token");
        setField(config, "token", TOKEN);
        guard = new DynamicExecutionGuard(config);

        controller = new AgentController();
        setField(controller, "codeGenerator", codeGenerator);
        setField(controller, "agentLoopService", agentLoopService);
        setField(controller, "memoryStore", memoryStore);
        setField(controller, "toolRegistry", toolRegistry);
        setField(controller, "executionGuard", guard);
    }

    @Test
    void execute_correctToken_invokesGenerator() {
        when(codeGenerator.generateAndExecute(anyString(), any()))
                .thenReturn(new DynamicCodeGenerator.CodeExecutionResult("ok", "Success", "done", "code", null));

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("10.0.0.1");
        req.addHeader("X-GIS-Agent-Token", TOKEN);
        Map<String, Object> result = controller.executeDynamic(
                new AgentController.ExecuteRequest("demo", new JSONObject(), null, null), req);

        assertEquals("Success", result.get("status"));
        verify(codeGenerator).generateAndExecute(anyString(), any());
    }

    @Test
    void execute_wrongToken_returnsForbidden() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("10.0.0.1");
        req.addHeader("X-GIS-Agent-Token", "wrong");
        Map<String, Object> result = controller.executeDynamic(
                new AgentController.ExecuteRequest("demo", new JSONObject(), null, null), req);

        assertEquals("Forbidden", result.get("status"));
    }

    @Test
    void execute_missingToken_returnsForbidden() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("10.0.0.1");
        Map<String, Object> result = controller.executeDynamic(
                new AgentController.ExecuteRequest("demo", new JSONObject(), null, null), req);

        assertEquals("Forbidden", result.get("status"));
    }

    @Test
    void execute_bearerToken_invokesGenerator() {
        when(codeGenerator.generateAndExecute(anyString(), any()))
                .thenReturn(new DynamicCodeGenerator.CodeExecutionResult("ok", "Success", "done", "code", null));

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("10.0.0.2");
        req.addHeader("Authorization", "Bearer " + TOKEN);
        Map<String, Object> result = controller.executeDynamic(
                new AgentController.ExecuteRequest("demo", new JSONObject(), null, null), req);

        assertEquals("Success", result.get("status"));
        verify(codeGenerator).generateAndExecute(anyString(), any());
    }

    private static void setField(Object target, String name, Object value) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法设置字段 " + name, e);
        }
    }
}
