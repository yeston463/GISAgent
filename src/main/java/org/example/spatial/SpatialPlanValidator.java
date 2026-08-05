package org.example.spatial;

import org.example.tools.DynamicToolRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class SpatialPlanValidator {
    @Autowired
    private SpatialCapabilityCatalog catalog;

    @Autowired
    private DynamicToolRegistry toolRegistry;

    public Validation validate(AnalysisPlan plan, DataAvailabilityChecker.Availability availability) {
        if (plan == null || plan.capabilityId() == null) {
            return new Validation("InvalidPlan", "plan_missing_capability", List.of(), null);
        }
        SpatialCapabilityCatalog.Capability capability = catalog.find(plan.capabilityId()).orElse(null);
        if (capability == null) {
            return new Validation("InvalidPlan", "capability_not_registered", List.of(), null);
        }
        if (!capability.enabled()) {
            return new Validation("CapabilityPending", "capability_not_enabled", List.copyOf(availability.missing()), capability);
        }
        if (!capability.operations().equals(plan.operations()) || !capability.tool().equals(plan.tool())) {
            return new Validation("InvalidPlan", "plan_not_allowed_by_capability", List.of(), capability);
        }
        if (!availability.missing().isEmpty()) {
            return new Validation("NeedsClarification", "required_data_missing", List.copyOf(availability.missing()), capability);
        }
        boolean toolExists = toolRegistry.getToolDescriptors().stream()
                .anyMatch(tool -> plan.tool().equals(tool.get("name")));
        if (!toolExists) {
            return new Validation("CapabilityPending", "tool_not_registered", List.of(), capability);
        }
        return new Validation("Valid", "", List.of(), capability);
    }

    public record Validation(String status, String code, List<String> missingData,
                             SpatialCapabilityCatalog.Capability capability) {
        public boolean canExecute() {
            return "Valid".equals(status);
        }

        public Map<String, Object> asMap() {
            return Map.of("status", status, "code", code, "missingData", missingData,
                    "capabilityId", capability == null ? "" : capability.id());
        }
    }
}
