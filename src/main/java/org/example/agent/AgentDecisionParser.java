package org.example.agent;

import com.alibaba.fastjson.JSONObject;

/** Parses and validates the planner's wire-level decision envelope. */
public final class AgentDecisionParser {
    public JSONObject parse(String raw) {
        String json = extractObject(raw);
        try {
            JSONObject decision = JSONObject.parseObject(json);
            if (decision == null) return null;
            String action = decision.getString("action");
            if (action == null || !action.matches("[A-Za-z][A-Za-z0-9_]{1,63}")) return null;
            if (!decision.containsKey("params") && !"respond".equals(action) && !"ask".equals(action)) {
                decision.put("params", new JSONObject());
            }
            decision.putIfAbsent("protocolVersion", CommandProtocol.VERSION);
            return decision;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String extractObject(String raw) {
        if (raw == null) return "{}";
        String text = raw.trim();
        if (text.startsWith("```")) {
            int line = text.indexOf('\n');
            text = line >= 0 ? text.substring(line + 1).trim() : "{}";
        }
        if (text.endsWith("```")) text = text.substring(0, text.length() - 3).trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return start >= 0 && end > start ? text.substring(start, end + 1) : text;
    }
}
