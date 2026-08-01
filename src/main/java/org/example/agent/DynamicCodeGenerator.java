package org.example.agent;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.example.service.PromptResourceService;
import org.example.tools.DynamicToolRegistry;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * 动态代码生成与执行。LLM 生成 JavaScript，经“黑名单 + 沙箱 + 超时/配额 + 开关/鉴权”四层防护后执行。
 *
 * 执行实现说明：
 * - 使用 GraalVM Polyglot {@link Context} 直接 API（而非 javax.script），以便超时后调用
 *   {@code Context.close(true)} 从任意线程强制终止仍在运行的 JS。
 * - 每次执行提交到独立有界线程池，配合信号量限制并发；超过 {@code timeoutMs} 即强制终止。
 */
@Service
public class DynamicCodeGenerator {

    private static final int MAX_CODE_LENGTH = 12_000;
    private static final long DEFAULT_TIMEOUT_MS = 3000;

    private static final Pattern RESULT_ASSIGNMENT = Pattern.compile(
            "(?:^|[;{}\\s])result\\s*=(?!=)", Pattern.MULTILINE);
    private static final Pattern INFINITE_LOOP = Pattern.compile(
            "\\bwhile\\s*\\(\\s*(?:true|1)\\s*\\)|\\bfor\\s*\\(\\s*;\\s*;\\s*\\)",
            Pattern.CASE_INSENSITIVE);
    private static final List<ForbiddenPattern> FORBIDDEN_PATTERNS = List.of(
            rule("Java.type", "\\bjava\\s*\\.\\s*type\\s*\\("),
            rule("Java/Polyglot 包访问", "\\b(?:packages|polyglot|processbuilder|classloader)\\b"),
            rule("文件或网络 API", "\\b(?:xmlhttprequest|websocket|socket)\\b"),
            rule("模块、网络或动态求值", "\\b(?:require|fetch|eval|load|loadwithnewglobal)\\s*\\("),
            rule("动态导入", "\\bimport\\s*(?:\\(|[\\\"'])"),
            rule("原型或全局对象访问", "\\b(?:constructor|__proto__|globalthis)\\b"),
            rule("进程对象访问", "\\bprocess\\s*\\."),
            new ForbiddenPattern("Function 构造器", Pattern.compile("\\bFunction\\s*\\("))
    );

    @Autowired
    private ChatLanguageModel chatLanguageModel;

    @Autowired
    private DynamicToolRegistry toolRegistry;

    @Autowired
    private PromptResourceService promptResources;

    @Autowired
    private DynamicExecutionConfig config;

    @Autowired
    private DynamicToolStore toolStore;

    // 执行线程池与并发信号灯，首次使用时惰性初始化（兼容无参构造的反射测试）。
    private volatile ExecutorService executor;
    private volatile Semaphore concurrency;
    private final ReentrantLock initLock = new ReentrantLock();

    /** Spring / 反射测试构造：使用安全默认值，随后由 @Autowired 覆盖为真实配置。 */
    public DynamicCodeGenerator() {
        this.config = DynamicExecutionConfig.defaults();
    }

    /** 测试专用：注入指定配置（如缩短超时做快速超时测试）。 */
    DynamicCodeGenerator(DynamicExecutionConfig config) {
        this.config = config;
    }

    @PostConstruct
    void restorePersistedTools() {
        if (toolStore == null || toolRegistry == null || isDisabled()) return;
        for (Map<String, Object> row : toolStore.load()) {
            String name = String.valueOf(row.getOrDefault("name", ""));
            String code = String.valueOf(row.getOrDefault("code", ""));
            if (!name.matches("[a-z][a-zA-Z0-9_]{2,63}") || validateCode(code) != null) continue;
            JSONObject context = row.get("context") instanceof JSONObject json ? json : new JSONObject();
            String description = String.valueOf(row.getOrDefault("description", "恢复的动态工具"));
            try {
                toolRegistry.registerDynamicTool(name, description,
                        runtimeParams -> executeCode(code, context, runtimeParams));
            } catch (Exception ignored) {
                // Invalid/stale records must not prevent application startup.
            }
        }
    }

    public Map<String, Object> rollbackDynamicTool(String name, long version) {
        if (toolStore == null || toolRegistry == null) {
            return Map.of("status", "Error", "message", "动态工具存储未初始化");
        }
        Map<String, Object> row = toolStore.rollback(name, version);
        if (row == null) {
            return Map.of("status", "NotFound", "tool", name, "version", version);
        }
        String code = String.valueOf(row.getOrDefault("code", ""));
        String violation = validateCode(code);
        if (violation != null) {
            return Map.of("status", "Error", "tool", name, "message", violation);
        }
        JSONObject context = row.get("context") instanceof JSONObject json ? json : new JSONObject();
        String description = String.valueOf(row.getOrDefault("description", "恢复的动态工具"));
        toolRegistry.registerDynamicTool(name, description,
                runtimeParams -> executeCode(code, context, runtimeParams));
        return Map.of("status", "Success", "tool", name, "version", version);
    }

    public void forgetDynamicTool(String name) {
        if (toolStore != null) toolStore.remove(name);
    }

    public CodeExecutionResult generateAndExecute(String requirement, JSONObject context) {
        if (isDisabled()) {
            return disabled();
        }
        JSONObject safeContext = context == null ? new JSONObject() : context;
        String code = generateCode(requirement, safeContext);
        String violation = validateCode(code);
        if (violation != null) {
            return new CodeExecutionResult(null, "Error", violation, code, null);
        }

        try {
            Object result = executeCode(code, safeContext, safeContext);
            return new CodeExecutionResult(result, "Success", "动态代码执行成功", code, null);
        } catch (Exception e) {
            return new CodeExecutionResult(null, "Error", "动态代码执行失败: " + e.getMessage(), code, null);
        }
    }

    public CodeExecutionResult generateAndRegister(
            String name,
            String description,
            String requirement,
            JSONObject context) {
        if (isDisabled()) {
            return disabled();
        }
        JSONObject registrationContext = context == null ? new JSONObject() : context;
        String code = generateCode(requirement, registrationContext);
        String violation = validateCode(code);
        if (violation != null) {
            return new CodeExecutionResult(null, "Error", violation, code, null);
        }

        try {
            Object sampleResult = executeCode(code, registrationContext, registrationContext);
            toolRegistry.registerDynamicTool(name, description, runtimeParams ->
                    executeCode(code, registrationContext, runtimeParams));
            if (toolStore != null) {
                toolStore.upsert(name, description, code, registrationContext);
            }
            return new CodeExecutionResult(
                    sampleResult,
                    "Success",
                    "动态工具已注册，可在当前进程内复用",
                    code,
                    name
            );
        } catch (Exception e) {
            return new CodeExecutionResult(null, "Error", "动态工具注册失败: " + e.getMessage(), code, null);
        }
    }

    /** 供 AgentLoopService 内部路径复用同一开关判断，避免绕过 /execute。 */
    public boolean isDisabled() {
        return config == null || !config.isEnabled();
    }

    private CodeExecutionResult disabled() {
        return new CodeExecutionResult(
                null, "Disabled", "动态代码执行功能已关闭（DYNAMIC_EXECUTION_ENABLED=false）", null, null);
    }

    private String generateCode(String requirement, JSONObject context) {
        String prompt = promptResources.render("prompts/dynamic-code.txt", Map.of(
                "REQUIREMENT", requirement == null ? "" : requirement,
                "CONTEXT_JSON", context.toJSONString()
        ));
        return stripCodeFence(chatLanguageModel.generate(prompt));
    }

    private String validateCode(String code) {
        if (code == null || code.isBlank()) {
            return "模型未生成可执行代码";
        }
        if (code.length() > MAX_CODE_LENGTH) {
            return "动态代码超过长度限制";
        }
        for (ForbiddenPattern forbidden : FORBIDDEN_PATTERNS) {
            if (forbidden.pattern().matcher(code).find()) {
                return "代码安全检查未通过: " + forbidden.label();
            }
        }
        if (INFINITE_LOOP.matcher(code).find()) {
            return "代码安全检查未通过: 禁止无限循环";
        }
        if (!RESULT_ASSIGNMENT.matcher(code).find()) {
            return "动态代码必须给 result 赋值";
        }
        return null;
    }

    private Object executeCode(String code, JSONObject context, JSONObject params) throws Exception {
        if (config != null) {
            int inputBytes = estimateInputBytes(context, params);
            if (inputBytes > config.getMaxInputBytes()) {
                throw new IllegalArgumentException(
                        "输入数据超过大小限制（" + inputBytes + " > " + config.getMaxInputBytes() + " 字节）");
            }
        }

        ExecutorService pool = executor();
        Semaphore sem = concurrency();
        if (!sem.tryAcquire()) {
            throw new IllegalStateException("动态执行并发数已达上限");
        }

        AtomicReference<Context> ctxRef = new AtomicReference<>();
        try {
            Future<Object> future = pool.submit(() -> {
                Context js = buildContext();
                ctxRef.set(js);
                try {
                    bindInputs(js, context, params);
                    String script = wrapScript(code);
                    Value result = js.eval("js", script);
                    if (result.isNull()) {
                        return null;
                    }
                    String serialized = result.asString();
                    if (config != null && serialized.length() > config.getMaxOutputBytes()) {
                        throw new IllegalStateException("执行结果超过输出大小限制");
                    }
                    return normalizeResult(JSON.parse(serialized));
                } finally {
                    try {
                        js.close();
                    } catch (Exception ignored) {
                        // 超时分支已强制关闭（Context.close(true)），这里忽略重复关闭异常。
                    }
                }
            });

            long timeout = config != null ? config.getTimeoutMs() : DEFAULT_TIMEOUT_MS;
            try {
                return future.get(timeout, TimeUnit.MILLISECONDS);
            } catch (TimeoutException te) {
                Context ctx = ctxRef.get();
                if (ctx != null) {
                    try {
                        // 跨线程强制终止仍在运行的 GraalJS 执行。
                        ctx.close(true);
                    } catch (Exception ignored) {
                    }
                }
                future.cancel(true);
                throw new IllegalStateException("动态代码执行超时（>" + timeout + "ms），已强制终止");
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("动态代码执行被中断", ie);
            } catch (Exception e) {
                throw new IllegalStateException("动态代码执行失败: " + e.getMessage(), e);
            }
        } finally {
            sem.release();
        }
    }

    private Context buildContext() {
        return Context.newBuilder("js")
                .allowHostAccess(false)
                .allowHostClassLookup((Predicate<String>) className -> false)
                .allowIO(false)
                .allowCreateThread(false)
                .build();
    }

    private void bindInputs(Context js, JSONObject context, JSONObject params) {
        JSONObject safeContext = context == null ? new JSONObject() : context;
        JSONObject safeParams = params == null ? new JSONObject() : params;
        js.getBindings("js").putMember("contextJson", safeContext.toJSONString());
        js.getBindings("js").putMember("paramsJson", safeParams.toJSONString());
    }

    private String wrapScript(String code) {
        return "\"use strict\";\n"
                + "var context = Object.freeze(JSON.parse(contextJson));\n"
                + "var params = Object.freeze(JSON.parse(paramsJson));\n"
                + "var result = null;\n"
                + code
                + "\nJSON.stringify(result === undefined ? null : result);";
    }

    private int estimateInputBytes(JSONObject context, JSONObject params) {
        int n = 0;
        if (context != null) {
            n += context.toJSONString().length();
        }
        if (params != null) {
            n += params.toJSONString().length();
        }
        return n;
    }

    private Object normalizeResult(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Value polyglot) {
            if (polyglot.isNull()) {
                return null;
            }
            if (polyglot.isBoolean()) {
                return polyglot.asBoolean();
            }
            if (polyglot.isNumber()) {
                return polyglot.asDouble();
            }
            if (polyglot.isString()) {
                return polyglot.asString();
            }
            if (polyglot.hasArrayElements()) {
                List<Object> values = new ArrayList<>();
                for (long i = 0; i < polyglot.getArraySize(); i++) {
                    values.add(normalizeResult(polyglot.getArrayElement(i)));
                }
                return values;
            }
            if (polyglot.hasMembers()) {
                Map<String, Object> values = new LinkedHashMap<>();
                for (String key : polyglot.getMemberKeys()) {
                    values.put(key, normalizeResult(polyglot.getMember(key)));
                }
                return values;
            }
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((key, item) -> normalized.put(String.valueOf(key), normalizeResult(item)));
            return normalized;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> normalized = new ArrayList<>();
            iterable.forEach(item -> normalized.add(normalizeResult(item)));
            return normalized;
        }
        return String.valueOf(value);
    }

    private String stripCodeFence(String code) {
        if (code == null) {
            return "";
        }
        String stripped = code.trim();
        if (stripped.startsWith("```")) {
            int newline = stripped.indexOf('\n');
            stripped = newline >= 0 ? stripped.substring(newline + 1) : "";
        }
        if (stripped.endsWith("```")) {
            stripped = stripped.substring(0, stripped.length() - 3);
        }
        return stripped.trim();
    }

    private ExecutorService executor() {
        if (executor == null) {
            initLock.lock();
            try {
                if (executor == null) {
                    int pool = config != null ? config.getPoolSize() : 4;
                    executor = Executors.newFixedThreadPool(Math.max(1, pool));
                    concurrency = new Semaphore(config != null ? Math.max(1, config.getMaxConcurrency()) : 4);
                }
            } finally {
                initLock.unlock();
            }
        }
        return executor;
    }

    private Semaphore concurrency() {
        // 确保与 executor 同一时刻初始化。
        executor();
        return concurrency;
    }

    private static ForbiddenPattern rule(String label, String regex) {
        return new ForbiddenPattern(label, Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
    }

    private record ForbiddenPattern(String label, Pattern pattern) {}

    public record CodeExecutionResult(
            Object result,
            String status,
            String message,
            String generatedCode,
            String registeredTool
    ) {}
}
