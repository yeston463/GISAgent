package org.example.agent;

import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 动态代码执行的安全分层测试：功能开关、鉴权、超时强制终止、沙箱黑名单拦截。
 */
class DynamicExecutionSecurityTest {

    // ① 功能关闭：直接返回 Disabled，且不触发任何代码生成/执行。
    @Test
    void disabledWhenFeatureSwitchOff() {
        DynamicCodeGenerator gen = new DynamicCodeGenerator(DynamicExecutionConfig.testConfig(false, 3000));
        DynamicCodeGenerator.CodeExecutionResult result = gen.generateAndExecute("do something", new JSONObject());
        assertEquals("Disabled", result.status());
        assertTrue(gen.isDisabled(), "功能关闭时 isDisabled() 应为 true（与安全控制一致）");
    }

    // ② 鉴权 - token 模式：缺令牌/错令牌拒绝，正确令牌放行。
    @Test
    void tokenAuthRejectsMissingOrWrongToken() throws Exception {
        DynamicExecutionConfig cfg = DynamicExecutionConfig.defaults();
        setField(cfg, "enabled", true);
        setField(cfg, "authMode", "token");
        setField(cfg, "token", "secret-token");

        DynamicExecutionGuard guard = new DynamicExecutionGuard(cfg);

        MockHttpServletRequest noToken = new MockHttpServletRequest();
        noToken.setRemoteAddr("127.0.0.1");
        assertFalse(guard.authorize(noToken), "缺令牌应拒绝");

        MockHttpServletRequest wrong = new MockHttpServletRequest();
        wrong.setRemoteAddr("127.0.0.1");
        wrong.addHeader("X-GIS-Agent-Token", "wrong");
        assertFalse(guard.authorize(wrong), "错误令牌应拒绝");

        MockHttpServletRequest ok = new MockHttpServletRequest();
        ok.setRemoteAddr("127.0.0.1");
        ok.addHeader("X-GIS-Agent-Token", "secret-token");
        assertTrue(guard.authorize(ok), "正确令牌应放行");

        // token 模式为空令牌时 fail-closed。
        setField(cfg, "token", "");
        assertFalse(guard.authorize(ok), "空令牌配置应拒绝所有请求");
    }

    // ② 鉴权 - local 模式：仅本机回环放行，非本机拒绝。
    @Test
    void localAuthAllowsLoopbackOnly() {
        DynamicExecutionConfig cfg = DynamicExecutionConfig.defaults();
        setField(cfg, "enabled", true);
        setField(cfg, "authMode", "local");
        DynamicExecutionGuard guard = new DynamicExecutionGuard(cfg);

        MockHttpServletRequest loopback = new MockHttpServletRequest();
        loopback.setRemoteAddr("127.0.0.1");
        assertTrue(guard.authorize(loopback), "本机回环应放行");

        MockHttpServletRequest remote = new MockHttpServletRequest();
        remote.setRemoteAddr("10.0.0.5");
        assertFalse(guard.authorize(remote), "非本机地址应拒绝");

        // 功能关闭时，即使本机也拒绝。
        setField(cfg, "enabled", false);
        assertFalse(guard.authorize(loopback), "功能关闭时应拒绝");
    }

    // ③ 超时：有限大循环在硬超时后被强制终止，抛出异常而非卡死。
    @Test
    void forcedTerminationOnTimeout() throws Exception {
        DynamicCodeGenerator gen = new DynamicCodeGenerator(DynamicExecutionConfig.testConfig(true, 400));
        Method execute = DynamicCodeGenerator.class.getDeclaredMethod(
                "executeCode", String.class, JSONObject.class, JSONObject.class);
        execute.setAccessible(true);

        JSONObject ctx = new JSONObject();
        JSONObject params = new JSONObject();
        Exception ex = assertThrows(Exception.class,
                () -> execute.invoke(gen, "for (let i = 0; i < 1e9; i++) { result = i; }", ctx, params));
        Throwable cause = ex.getCause();
        assertNotNull(cause);
        assertTrue(cause instanceof IllegalStateException, "应因超时抛出 IllegalStateException");
        assertTrue(cause.getMessage().contains("超时") || cause.getMessage().contains("强制终止"),
                "异常应说明超时强制终止: " + cause.getMessage());
    }

    // ③ 正常执行在超时内完成（不回归 happy path）。
    @Test
    void executesNormallyWithinTimeout() throws Exception {
        DynamicCodeGenerator gen = new DynamicCodeGenerator(DynamicExecutionConfig.testConfig(true, 3000));
        Method execute = DynamicCodeGenerator.class.getDeclaredMethod(
                "executeCode", String.class, JSONObject.class, JSONObject.class);
        execute.setAccessible(true);

        Object value = execute.invoke(gen, "result = 2 * 3;", new JSONObject(), new JSONObject());
        assertTrue(value instanceof Number);
        assertEquals(6, ((Number) value).intValue());
    }

    // ④ 沙箱黑名单：危险语法被拦截（不依赖沙箱运行时，纯静态校验）。
    @Test
    void sandboxBlacklistBlocksDangerousSyntax() throws Exception {
        DynamicCodeGenerator gen = new DynamicCodeGenerator(DynamicExecutionConfig.defaults());
        Method validate = DynamicCodeGenerator.class.getDeclaredMethod("validateCode", String.class);
        validate.setAccessible(true);

        assertNotNull(validate.invoke(gen, "result = Java.type('java.lang.Runtime');"),
                "Java.type 应被拦截");
        assertNotNull(validate.invoke(gen, "result = fetch('https://evil.example');"),
                "fetch 应被拦截");
        assertNotNull(validate.invoke(gen, "while (true) { result = 1; }"),
                "无限循环应被拦截");
        // 合法代码应放行。
        assertNull(validate.invoke(gen, "result = params.values.length;"));
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
