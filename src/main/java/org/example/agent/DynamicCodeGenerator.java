package org.example.agent;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.example.service.PromptResourceService;
import org.example.tools.DynamicToolRegistry;
import org.graalvm.polyglot.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.script.Bindings;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;

@Service
public class DynamicCodeGenerator {

    private static final int MAX_CODE_LENGTH = 12_000;
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

    public CodeExecutionResult generateAndExecute(String requirement, JSONObject context) {
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
        ScriptEngine engine = new ScriptEngineManager().getEngineByName("graal.js");
        if (engine == null) {
            throw new IllegalStateException("GraalJS 引擎未初始化");
        }

        JSONObject safeContext = context == null ? new JSONObject() : context;
        JSONObject safeParams = params == null ? new JSONObject() : params;

        Bindings bindings = engine.createBindings();
        bindings.put("polyglot.js.allowHostAccess", false);
        bindings.put("polyglot.js.allowHostClassLookup", (Predicate<String>) className -> false);
        bindings.put("polyglot.js.allowIO", false);
        bindings.put("polyglot.js.allowCreateThread", false);
        bindings.put("contextJson", safeContext.toJSONString());
        bindings.put("paramsJson", safeParams.toJSONString());

        String script = "\"use strict\";\n"
                + "var context = Object.freeze(JSON.parse(contextJson));\n"
                + "var params = Object.freeze(JSON.parse(paramsJson));\n"
                + "var result = null;\n"
                + code
                + "\nJSON.stringify(result === undefined ? null : result);";
        Object serialized = engine.eval(script, bindings);
        if (serialized == null) {
            return null;
        }
        return normalizeResult(JSON.parse(String.valueOf(serialized)));
    }

    private Object normalizeResult(Object value) {
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Value polyglot) {
            if (polyglot.isNull()) return null;
            if (polyglot.isBoolean()) return polyglot.asBoolean();
            if (polyglot.isNumber()) return polyglot.asDouble();
            if (polyglot.isString()) return polyglot.asString();
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
        if (code == null) return "";
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
