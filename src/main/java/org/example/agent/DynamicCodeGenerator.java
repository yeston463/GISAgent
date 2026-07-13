package org.example.agent;

import com.alibaba.fastjson.JSONObject;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.example.tools.DynamicToolRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.Bindings;
import java.util.Map;

/**
 * 动态代码生成器：让 AI 临时编写并执行逻辑
 */
@Service
public class DynamicCodeGenerator {

    @Autowired
    private ChatLanguageModel chatLanguageModel;

    @Autowired
    private DynamicToolRegistry toolRegistry;

    private final ScriptEngine jsEngine = new ScriptEngineManager().getEngineByName("graal.js");

    /**
     * 根据需求生成并执行代码
     */
    public CodeExecutionResult generateAndExecute(String requirement, JSONObject context) {
        // 1. 让 AI 生成代码
        String code = generateCode(requirement, context);
        
        // 2. 安全检查
        if (!isSafeCode(code)) {
            return new CodeExecutionResult(null, "代码安全检查未通过", code);
        }

        // 3. 执行代码
        try {
            Object result = executeCode(code, context);
            return new CodeExecutionResult(result, "执行成功", code);
        } catch (Exception e) {
            return new CodeExecutionResult(null, "执行失败: " + e.getMessage(), code);
        }
    }

    private String generateCode(String requirement, JSONObject context) {
        String prompt = String.format("""
            根据以下需求生成 JavaScript 代码片段：
            
            需求：%s
            
            可用上下文数据：
            %s
            
            可用工具函数：
            - geocode(name) -> {longitude, latitude}
            - bufferAnalysis(center, radius) -> GeoJSON
            - calculateArea(geoJson) -> number
            
            要求：
            1. 只返回纯 JavaScript 代码，不要包含任何解释
            2. 将最终结果赋值给变量 result
            3. 代码必须是安全的，不能包含文件操作、网络请求等
            
            示例：
            var coords = geocode("北京大学");
            var buffer = bufferAnalysis([coords.longitude, coords.latitude], 1000);
            var area = calculateArea(buffer);
            result = {location: "北京大学", bufferArea: area};
        """, requirement, context.toJSONString());

        return chatLanguageModel.generate(prompt);
    }

    private boolean isSafeCode(String code) {
        // 简单的安全检查
        String[] forbidden = {"Runtime", "ProcessBuilder", "File", "Socket", "exec", "eval"};
        for (String keyword : forbidden) {
            if (code.contains(keyword)) {
                return false;
            }
        }
        return true;
    }

    private Object executeCode(String code, JSONObject context) throws Exception {
        if (jsEngine == null) {
            throw new IllegalStateException("JavaScript 引擎未初始化，请添加 GraalJS 依赖");
        }

        Bindings bindings = jsEngine.createBindings();
        
        // 注入工具函数
        bindings.put("geocode", (java.util.function.Function<String, Object>) name -> {
            try {
                return toolRegistry.invoke("geocode", new JSONObject().fluentPut("locationName", name));
            } catch (Exception e) {
                return Map.of("error", e.getMessage());
            }
        });

        // 注入上下文数据
        context.forEach(bindings::put);

        // 执行代码
        Object result = jsEngine.eval(code, bindings);
        return result;
    }

    public record CodeExecutionResult(
            Object result,
            String status,
            String generatedCode
    ) {}
}
