package org.example.tools;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

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

    @Autowired
    public DynamicToolRegistry(GisMapTools gisMapTools, pyGisTools pyGisTools, ChatLanguageModel chatLanguageModel) {
        // 注册静态工具
        registerStaticTools(gisMapTools);
        registerStaticTools(pyGisTools);
        
        System.out.println("✅ 工具注册完成，共 " + staticTools.size() + " 个静态工具:");
        staticTools.forEach((name, invoker) -> System.out.println("  - " + name));

        // 注册 synthesis 工具（LLM 综合分析）
        dynamicTools.put("synthesis", params -> {
            String prompt = String.format("""
                你是一个专业的 GIS 分析师。根据以下数据生成简洁的分析报告：

                分析数据：
                %s

                要求：
                1. 如果有坐标信息，说明位置
                2. 如果有容积率等指标，给出具体数值
                3. 结合知识库规范做对比
                4. 输出自然语言，简洁明了
            """, params.toJSONString());
            return chatLanguageModel.generate(prompt);
        });
        System.out.println("注册动态工具: synthesis - LLM 综合分析");
    }

    private void registerStaticTools(Object toolBean) {
        for (Method method : toolBean.getClass().getDeclaredMethods()) {
            Tool annotation = method.getAnnotation(Tool.class);
            if (annotation != null) {
                String name = annotation.value().length == 0 ? method.getName() : annotation.value()[0];
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
        dynamicTools.put(name, invoker);
        System.out.println("注册动态工具: " + name + " - " + description);
    }

    /**
     * 调用工具
     */
    public Object invoke(String toolName, JSONObject params) throws Exception {
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
            descriptions.add(Map.of("name", name, "type", "static"));
        });
        dynamicTools.forEach((name, invoker) -> {
            descriptions.add(Map.of("name", name, "type", "dynamic"));
        });
        return descriptions;
    }

    /**
     * 从 JSONObject 中提取方法参数
     */
    private Object[] extractParams(Method method, JSONObject params) {
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
