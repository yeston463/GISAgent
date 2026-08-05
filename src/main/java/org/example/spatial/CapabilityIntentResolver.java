package org.example.spatial;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;

/** Resolves user wording to an approved capability declared in the catalog. */
@Component
public class CapabilityIntentResolver {
    @Autowired
    private SpatialCapabilityCatalog catalog;

    public Optional<String> resolve(String message) {
        if (message == null || message.isBlank()) return Optional.empty();
        String normalized = message.toLowerCase(Locale.ROOT);
        return catalog.capabilities().stream()
                .flatMap(capability -> capability.aliases().stream()
                        .map(alias -> new Candidate(capability.id(), alias)))
                .filter(candidate -> normalized.contains(candidate.alias().toLowerCase(Locale.ROOT)))
                .max(Comparator.comparingInt(candidate -> candidate.alias().length()))
                .map(Candidate::capabilityId);
    }

    private record Candidate(String capabilityId, String alias) {
    }
}
