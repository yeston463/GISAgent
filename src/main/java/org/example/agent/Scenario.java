package org.example.agent;

import java.util.List;
import java.util.Map;

public record Scenario(
        String id,
        String name,
        String description,
        Map<String, Double> parameters,
        List<Map<String, Object>> buildings
) {
    public Scenario {
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        buildings = buildings == null ? List.of() : List.copyOf(buildings);
    }
}
