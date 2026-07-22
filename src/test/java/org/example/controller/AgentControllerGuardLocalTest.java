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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 控制器层测试：覆盖动态执行鉴权中间件（local 模式）。
 *
 * 说明：本项目同时引入 spring-boot-starter-web 与 spring-boot-starter-webflux，
 * 造成 @WebMvcTest 的 DispatcherServlet 无法稳定注册该控制器映射（实测返回 404），
 * 因此这里改为【直接实例化 AgentController + 真实 DynamicExecutionGuard + 受控 config】，
 * 在“控制器方法内部调用守卫”的真实代码路径上验证鉴权中间件，等价覆盖且更稳健。
 *
 * 结论性断言：
 *  - 本机回环（127.0.0.1）放行，危险操作（代码生成 / 工具注销）被执行；
 *  - 非本机地址拒绝，返回 status=Forbidden，且危险操作从未被调用。
 */
class AgentControllerGuardLocalTest {

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

        // defaults(): enabled=true, authMode=local, serverAddress=127.0.0.1, token=""
        DynamicExecutionConfig config = DynamicExecutionConfig.defaults();
        guard = new DynamicExecutionGuard(config);

        controller = new AgentController();
        setField(controller, "codeGenerator", codeGenerator);
        setField(controller, "agentLoopService", agentLoopService);
        setField(controller, "memoryStore", memoryStore);
        setField(controller, "toolRegistry", toolRegistry);
        setField(controller, "executionGuard", guard);
    }

    @Test
    void execute_allowedFromLoopback_invokesGenerator() {
        when(codeGenerator.generateAndExecute(anyString(), any()))
                .thenReturn(new DynamicCodeGenerator.CodeExecutionResult("ok", "Success", "done", "code", null));

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("127.0.0.1");
        Map<String, Object> result = controller.executeDynamic(
                new AgentController.ExecuteRequest("demo", new JSONObject(), null, null), req);

        assertEquals("Success", result.get("status"));
        verify(codeGenerator).generateAndExecute(anyString(), any());
    }

    @Test
    void execute_rejectedFromRemote_returnsForbidden() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("10.0.0.5");
        Map<String, Object> result = controller.executeDynamic(
                new AgentController.ExecuteRequest("demo", new JSONObject(), null, null), req);

        assertEquals("Forbidden", result.get("status"));
        verify(codeGenerator, never()).generateAndExecute(anyString(), any());
    }

    @Test
    void deleteTool_allowedFromLoopback_invokesRegistry() {
        when(toolRegistry.removeDynamicTool("demo")).thenReturn(true);

        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("127.0.0.1");
        Map<String, Object> result = controller.removeDynamicTool("demo", req);

        assertEquals("Success", result.get("status"));
        verify(toolRegistry).removeDynamicTool("demo");
    }

    @Test
    void deleteTool_rejectedFromRemote_returnsForbidden() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("10.0.0.9");
        Map<String, Object> result = controller.removeDynamicTool("demo", req);

        assertEquals("Forbidden", result.get("status"));
        verify(toolRegistry, never()).removeDynamicTool(anyString());
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
