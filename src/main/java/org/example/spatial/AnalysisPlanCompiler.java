package org.example.spatial;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Converts recognized natural-language intents into catalog-owned plans. */
@Component
public class AnalysisPlanCompiler {
    private static final Pattern ISO_DATE = Pattern.compile("\\b(20\\d{2}-\\d{2}-\\d{2})\\b");
    private static final Pattern BIN_COUNT = Pattern.compile("\\b(\\d{2})\\s*(?:个?方向|bins?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern RETURN_PERIOD = Pattern.compile("(\\d{1,4})\\s*(?:年一遇|year)", Pattern.CASE_INSENSITIVE);

    @Autowired
    private CapabilityIntentResolver intentResolver;

    @Autowired
    private SpatialCapabilityCatalog catalog;

    public Optional<Compilation> compile(String message) {
        return intentResolver.resolve(message).map(capabilityId -> compile(capabilityId, message));
    }

    public Compilation compile(String capabilityId, String message) {
        Map<String, Object> params = extractParams(capabilityId, message);
        return new Compilation(catalog.createPlan(capabilityId, params), capabilityId, params);
    }

    private Map<String, Object> extractParams(String capabilityId, String message) {
        Map<String, Object> params = new LinkedHashMap<>();
        String text = message == null ? "" : message;
        if ("sunlight_analysis".equals(capabilityId)) {
            Matcher date = ISO_DATE.matcher(text);
            if (date.find()) params.put("date", date.group(1));
        }
        if ("skyline_analysis".equals(capabilityId)) {
            Matcher bins = BIN_COUNT.matcher(text);
            if (bins.find()) params.put("bin_count", Integer.parseInt(bins.group(1)));
        }
        if ("flood_analysis".equals(capabilityId)) {
            Matcher returnPeriod = RETURN_PERIOD.matcher(text);
            if (returnPeriod.find()) params.put("returnPeriodYears", Integer.parseInt(returnPeriod.group(1)));
        }
        return params;
    }

    public record Compilation(AnalysisPlan plan, String capabilityId, Map<String, Object> params) {
    }
}
