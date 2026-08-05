package org.example.spatial;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class SpatialPlanService {
    @Autowired
    private SpatialCapabilityCatalog catalog;

    @Autowired
    private DataAvailabilityChecker dataAvailabilityChecker;

    @Autowired
    private SpatialPlanValidator validator;

    @Autowired
    private AnalysisProvenanceService provenanceService;

    public PreparedPlan prepare(String capabilityId, Map<String, Object> params) {
        return prepare(catalog.createPlan(capabilityId, params));
    }

    public PreparedPlan prepare(AnalysisPlan plan) {
        SpatialCapabilityCatalog.Capability capability = catalog.find(plan.capabilityId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown spatial capability: " + plan.capabilityId()));
        DataAvailabilityChecker.Availability availability = dataAvailabilityChecker.checkCurrent(capability.requires());
        SpatialPlanValidator.Validation validation = validator.validate(plan, availability);
        return new PreparedPlan(plan, availability, validation);
    }

    public Map<String, Object> record(PreparedPlan prepared, Map<String, Object> result) {
        return provenanceService.record(prepared.plan(), prepared.availability(), prepared.validation(), result);
    }

    public record PreparedPlan(
            AnalysisPlan plan,
            DataAvailabilityChecker.Availability availability,
            SpatialPlanValidator.Validation validation
    ) {
    }
}
