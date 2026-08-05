package org.example.agent;

import java.util.List;
import java.util.Map;

public class ConservativeStrategy implements ScenarioStrategy {
    @Override
    public String id() { return "conservative"; }
    @Override
    public String name() { return "保守方案"; }
    @Override
    public String description() { return "现状不变，只做合规检查"; }
    @Override
    public List<Map<String, Object>> apply(List<Map<String, Object>> originalBuildings, Map<String, Double> context) {
        return originalBuildings;
    }
}
