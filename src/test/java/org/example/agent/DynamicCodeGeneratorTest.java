package org.example.agent;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamicCodeGeneratorTest {

    private final Class<?> generatorType = loadGeneratorType();
    private final Object generator = newGenerator();

    @Test
    void executesWithRuntimeParamsAndNullableContext() throws Exception {
        JSONObject context = new JSONObject();
        context.put("profile", null);
        JSONObject params = new JSONObject();
        params.put("values", new JSONArray().fluentAdd(2).fluentAdd(3).fluentAdd(5));

        Method execute = generatorType.getDeclaredMethod(
                "executeCode", String.class, JSONObject.class, JSONObject.class);
        execute.setAccessible(true);
        Object value = execute.invoke(generator,
                "result = { total: params.values.reduce((sum, value) => sum + value, 0), profile: context.profile };",
                context,
                params);

        Map<?, ?> result = (Map<?, ?>) value;
        assertEquals(10, ((Number) result.get("total")).intValue());
        assertNull(result.get("profile"));
    }

    @Test
    void securityCheckUsesApiPatternsInsteadOfSubstringMatches() throws Exception {
        Method validate = generatorType.getDeclaredMethod("validateCode", String.class);
        validate.setAccessible(true);

        assertNull(validate.invoke(generator, "const profile = params.profile; result = { profile };"));
        String violation = (String) validate.invoke(generator, "result = fetch('https://example.com');");
        assertTrue(violation.contains("安全检查"));
    }

    private static Class<?> loadGeneratorType() {
        try {
            return Class.forName("org.example.agent.DynamicCodeGenerator");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    private Object newGenerator() {
        try {
            return generatorType.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
