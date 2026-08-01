package org.example.tools;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.example.service.PromptResourceService;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动态工具注册表：支持运行时注册新工具
 */
@Component
public class DynamicToolRegistry {

    // 静态工具：Spring Bean 中的 @Tool 方法
    private final Map<String, ToolInvoker> staticTools = new ConcurrentHashMap<>();
    
    // 动态工具：运行时注册的脚本/逻辑
    private final Map<String, ToolInvoker> dynamicTools = new ConcurrentHashMap<>();
    private final Map<String, String> toolDescriptions = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> toolSchemas = new ConcurrentHashMap<>();

    @Autowired
    public DynamicToolRegistry(
            GisMapTools gisMapTools,
            pyGisTools pyGisTools,
            ChatLanguageModel chatLanguageModel,
            PromptResourceService promptResources) {
        // 注册静态工具
        registerStaticTools(gisMapTools);
        registerStaticTools(pyGisTools);
        
        System.out.println("✅ 工具注册完成，共 " + staticTools.size() + " 个静态工具:");
        staticTools.forEach((name, invoker) -> System.out.println("  - " + name));

        // 注册 synthesis 工具（LLM 综合分析）
        dynamicTools.put("synthesis", params -> {
            String prompt = promptResources.render("prompts/tool-synthesis.txt",
                    Map.of("PARAMS_JSON", params.toJSONString()));
            return chatLanguageModel.generate(prompt);
        });
        toolDescriptions.put("synthesis", "根据工具数据生成受控 GIS 摘要");
        System.out.println("注册动态工具: synthesis - LLM 综合分析");
    }

    private void registerStaticTools(Object toolBean) {
        for (Method method : toolBean.getClass().getDeclaredMethods()) {
            Tool annotation = method.getAnnotation(Tool.class);
            if (annotation != null) {
                String name = annotation.value().length == 0 ? method.getName() : annotation.value()[0];
                toolDescriptions.putIfAbsent(name, "后端 GIS 工具");
                toolSchemas.putIfAbsent(name, schemaFor(method));
                staticTools.put(name, params -> {
                    try {
                        Object[] args = extractParams(method, params);
                        return method.invoke(toolBean, args);
                    } catch (Exception e) {
                        e.printStackTrace();
                        return "工具执行失败: " + e.getMessage();
                    }
                });
            }
        }
    }

    /**
     * 注册动态工具（来自脚本或用户定义）
     */
    public void registerDynamicTool(String name, String description, ToolInvoker invoker) {
        if (name == null || !name.matches("[a-z][a-zA-Z0-9_]{2,63}")) {
            throw new IllegalArgumentException("动态工具名必须是 3-64 位字母、数字或下划线，并以小写字母开头");
        }
        if ("synthesis".equals(name) || "generateDynamicTool".equals(name)) {
            throw new IllegalArgumentException("保留工具名不能被动态工具覆盖: " + name);
        }
        if (staticTools.containsKey(name)) {
            throw new IllegalArgumentException("不能覆盖静态工具: " + name);
        }
        if (invoker == null) {
            throw new IllegalArgumentException("动态工具执行器不能为空");
        }
        dynamicTools.put(name, invoker);
        toolSchemas.putIfAbsent(name, defaultSchema());
        toolDescriptions.put(name, description == null || description.isBlank() ? "运行时动态工具" : description);
        System.out.println("注册动态工具: " + name + " - " + description);
    }

    public void registerDynamicTool(String name, String description,
                                    Map<String, Object> schema, ToolInvoker invoker) {
        registerDynamicTool(name, description, invoker);
        if (schema != null && !schema.isEmpty()) {
            toolSchemas.put(name, new LinkedHashMap<>(schema));
        }
    }

    public boolean removeDynamicTool(String name) {
        if (name == null || "synthesis".equals(name)) {
            return false;
        }
        boolean removed = dynamicTools.remove(name) != null;
        if (removed) {
            toolDescriptions.remove(name);
            toolSchemas.remove(name);
        }
        return removed;
    }

    /**
     * 调用工具
     */
    public Object invoke(String toolName, JSONObject params) throws Exception {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("工具名不能为空");
        }
        if (params == null) {
            params = new JSONObject();
        }
        ToolInvoker invoker = dynamicTools.getOrDefault(toolName, staticTools.get(toolName));
        if (invoker == null) {
            throw new IllegalArgumentException("未知工具: " + toolName);
        }
        return invoker.invoke(params);
    }

    /**
     * 获取所有可用工具描述（供 Planner 使用）
     */
    public List<Map<String, String>> getToolDescriptions() {
        List<Map<String, String>> descriptions = new ArrayList<>();
        staticTools.forEach((name, invoker) -> {
            descriptions.add(Map.of("name", name, "type", "static",
                    "description", toolDescriptions.getOrDefault(name, "后端 GIS 工具")));
        });
        dynamicTools.forEach((name, invoker) -> {
            descriptions.add(Map.of("name", name, "type", "dynamic",
                    "description", toolDescriptions.getOrDefault(name, "运行时动态工具")));
        });
        return descriptions;
    }

    /**
     * 从 JSONObject 中提取方法参数
     */
    public List<Map<String, Object>> getToolDescriptors() {
        List<Map<String, Object>> descriptors = new ArrayList<>();
        staticTools.forEach((name, invoker) -> descriptors.add(descriptor(name, "static")));
        dynamicTools.forEach((name, invoker) -> descriptors.add(descriptor(name, "dynamic")));
        return descriptors;
    }

    private Map<String, Object> descriptor(String name, String type) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        result.put("type", type);
        result.put("description", toolDescriptions.getOrDefault(name, "GIS tool"));
        result.put("schema", toolSchemas.getOrDefault(name, defaultSchema()));
        return result;
    }

    private Map<String, Object> schemaFor(Method method) {
        List<Map<String, Object>> parameters = new ArrayList<>();
        for (Parameter parameter : method.getParameters()) {
            P annotation = parameter.getAnnotation(P.class);
            String name = annotation != null && !annotation.value().isBlank()
                    ? annotation.value() : parameter.getName();
            parameters.add(Map.of("name", name,
                    "type", parameter.getType().getSimpleName(),
                    "required", !parameter.getType().isPrimitive()));
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("input", Map.of("type", "object", "parameters", parameters));
        schema.put("output", Map.of("type", "object"));
        schema.put("display", Map.of("kinds", List.of("metric", "vector", "table", "chart")));
        return schema;
    }

    private Map<String, Object> defaultSchema() {
        return Map.of("input", Map.of("type", "object"),
                "output", Map.of("type", "object"),
                "display", Map.of("kinds", List.of("metric", "vector", "table", "chart")));
    }

    private Object[] extractParams(Method method, JSONObject params) {
        if (params == null) {
            params = new JSONObject();
        }
        Parameter[] parameters = method.getParameters();
        Object[] args = new Object[parameters.length];
        
        for (int i = 0; i < parameters.length; i++) {
            Parameter param = parameters[i];
            P annotation = param.getAnnotation(P.class);
            
            // 尝试从注解值或参数名获取键名
            String key = null;
            if (annotation != null && !annotation.value().isEmpty()) {
                key = annotation.value();
            }
            
            // 如果没有注解值，使用参数名
            if (key == null || key.isEmpty()) {
                key = param.getName();
            }
            
            // 从 params 中获取值
            Object value = params.get(key);
            
            // 如果找不到，尝试使用索引
            if (value == null && i < params.size()) {
                value = params.values().toArray()[i];
            }
            
            // 类型转换
            if (value != null) {
                args[i] = convertValue(value, param.getType());
            } else {
                args[i] = getDefaultValue(param.getType());
            }
        }
        
        return args;
    }

    /**
     * 类型转换
     */
    private Object convertValue(Object value, Class<?> type) {
        if (value == null) {
            return getDefaultValue(type);
        }
        
        // 如果值是 JSONObject，先尝试提取
        if (value instanceof JSONObject json) {
            // 对于数组类型，尝试从 JSON 中提取
            if (type == double[].class) {
                return extractDoubleArray(json);
            }
            // 对于其他类型，返回 JSON 字符串
            return json.toJSONString();
        }
        
        // 如果值是 JSONArray
        if (value instanceof JSONArray jsonArray) {
            if (type == double[].class) {
                double[] result = new double[jsonArray.size()];
                for (int i = 0; i < jsonArray.size(); i++) {
                    result[i] = jsonArray.getDoubleValue(i);
                }
                return result;
            }
        }
        
        String strValue = value.toString();
        
        // 检查是否是 JSON 字符串
        if (strValue.startsWith("{") || strValue.startsWith("[")) {
            try {
                if (type == String.class) {
                    return strValue;
                }
                // 尝试解析为 JSON
                JSONObject json = JSON.parseObject(strValue);
                if (type == double[].class) {
                    return extractDoubleArray(json);
                }
                return json;
            } catch (Exception e) {
                // 解析失败，继续使用原值
            }
        }
        
        if (type == String.class) {
            return strValue;
        } else if (type == int.class || type == Integer.class) {
            try {
                return Integer.parseInt(strValue);
            } catch (NumberFormatException e) {
                return 0;
            }
        } else if (type == double.class || type == Double.class) {
            try {
                return Double.parseDouble(strValue);
            } catch (NumberFormatException e) {
                return 0.0;
            }
        } else if (type == boolean.class || type == Boolean.class) {
            return Boolean.parseBoolean(strValue);
        } else if (type == long.class || type == Long.class) {
            try {
                return Long.parseLong(strValue);
            } catch (NumberFormatException e) {
                return 0L;
            }
        } else if (type == double[].class) {
            // 处理数组类型
            if (value instanceof List) {
                List<?> list = (List<?>) value;
                return list.stream().mapToDouble(v -> {
                    try {
                        return Double.parseDouble(v.toString());
                    } catch (NumberFormatException e) {
                        return 0.0;
                    }
                }).toArray();
            }
            try {
                return new double[]{Double.parseDouble(strValue)};
            } catch (NumberFormatException e) {
                return new double[]{0.0};
            }
        }
        
        return value;
    }

    /**
     * 从 JSONObject 中提取 double 数组
     * 支持多种格式：[lon, lat] 或 {longitude: x, latitude: y}
     */
    private double[] extractDoubleArray(JSONObject json) {
        // 如果有 longitude 和 latitude 字段
        if (json.containsKey("longitude") && json.containsKey("latitude")) {
            return new double[]{
                json.getDoubleValue("longitude"),
                json.getDoubleValue("latitude")
            };
        }
        // 如果有 lon 和 lat 字段
        if (json.containsKey("lon") && json.containsKey("lat")) {
            return new double[]{
                json.getDoubleValue("lon"),
                json.getDoubleValue("lat")
            };
        }
        // 如果是数组格式
        if (json.containsKey("0") || json.isEmpty()) {
            return new double[]{0.0, 0.0};
        }
        return new double[]{0.0, 0.0};
    }

    /**
     * 获取默认值
     */
    private Object getDefaultValue(Class<?> type) {
        if (type == int.class || type == double.class || type == long.class) {
            return 0;
        } else if (type == boolean.class) {
            return false;
        }
        return null;
    }

    @FunctionalInterface
    public interface ToolInvoker {
        Object invoke(JSONObject params) throws Exception;
    }
}
