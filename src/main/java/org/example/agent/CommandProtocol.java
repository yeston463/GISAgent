package org.example.agent;

import com.alibaba.fastjson.JSON;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stable boundary between agent results and frontend actions.
 * Keeps the wire format extensible without allowing malformed commands to
 * reach the browser.
 */
public final class CommandProtocol {
    public static final String VERSION = "1.0";

    private CommandProtocol() {
    }

    public static List<Map<String, Object>> normalize(List<Map<String, Object>> commands) {
        List<Map<String, Object>> normalized = new ArrayList<>();
        if (commands == null) {
            return normalized;
        }
        int index = 0;
        for (Map<String, Object> command : commands) {
            if (command == null) {
                continue;
            }
            Object actionValue = command.get("action");
            if (actionValue == null || !String.valueOf(actionValue).matches("[A-Za-z][A-Za-z0-9_]{1,63}")) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("protocolVersion", VERSION);
            item.put("action", String.valueOf(actionValue));
            item.put("commandId", command.get("commandId") == null
                    ? "cmd-" + index + "-" + actionValue : String.valueOf(command.get("commandId")));
            Object params = command.get("params");
            if (params instanceof Map<?, ?> map) {
                item.put("params", new LinkedHashMap<>(stringifyKeys(map)));
            } else if (params != null) {
                // Preserve legacy commands while making the malformed shape explicit.
                item.put("params", Map.of("value", params));
            }
            if (command.get("dependsOn") instanceof List<?> dependsOn) {
                item.put("dependsOn", dependsOn);
            }
            normalized.add(item);
            index++;
        }
        return normalized;
    }

    public static String dedupeKey(Map<String, Object> command) {
        if (command == null) {
            return "null";
        }
        Map<String, Object> stable = new LinkedHashMap<>();
        stable.put("action", command.get("action"));
        Object params = command.get("params");
        if (params instanceof Map<?, ?> map) {
            // Target identity prevents dropping two layers that use the same action.
            Object target = first(map, "commandId", "layerId", "id", "jobId", "title");
            stable.put("target", target != null ? target : new LinkedHashMap<>(stringifyKeys(map)));
        } else {
            stable.put("params", params);
        }
        return JSON.toJSONString(stable);
    }

    private static Object first(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            if (map.containsKey(key) && map.get(key) != null) {
                return map.get(key);
            }
        }
        return null;
    }

    private static Map<String, Object> stringifyKeys(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }
}
