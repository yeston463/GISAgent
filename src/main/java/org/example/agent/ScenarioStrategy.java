package org.example.agent;

import java.util.List;
import java.util.Map;

public interface ScenarioStrategy {
    String id();
    String name();
    String description();
    List<Map<String, Object>> apply(List<Map<String, Object>> originalBuildings, Map<String, Double> context);
}
