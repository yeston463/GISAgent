package org.example.spatial;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.example.service.GisContextService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
public class DataAvailabilityChecker {
    @Autowired
    private GisContextService contextService;

    public Availability checkCurrent(List<String> requirements) {
        JSONObject context;
        try {
            context = JSON.parseObject(contextService.getGeoJson());
        } catch (Exception ignored) {
            context = new JSONObject();
        }
        Set<String> available = new LinkedHashSet<>();
        if (context != null && context.containsKey("aoi")) available.add("aoi");
        if (hasFeatures(context == null ? null : context.get("buildings"))) available.add("buildings");
        addDeclaredDatasets(context, available);

        Set<String> missing = new LinkedHashSet<>(requirements == null ? List.of() : requirements);
        missing.removeAll(available);
        long version = context == null ? 0L : context.getLongValue("contextVersion");
        return new Availability(available, missing, version);
    }

    private boolean hasFeatures(Object raw) {
        if (raw instanceof JSONObject object) {
            JSONArray features = object.getJSONArray("features");
            return features != null && !features.isEmpty();
        }
        try {
            JSONObject object = JSON.parseObject(JSON.toJSONString(raw));
            JSONArray features = object == null ? null : object.getJSONArray("features");
            return features != null && !features.isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    private void addDeclaredDatasets(JSONObject context, Set<String> available) {
        if (context == null) return;
        Object raw = context.get("datasets");
        if (raw instanceof JSONArray rows) {
            for (Object row : rows) available.add(String.valueOf(row));
        }
        for (String key : List.of("dem", "rainfall_scenario", "drainage_network", "river_network")) {
            if (context.containsKey(key) && context.get(key) != null) available.add(key);
        }
        for (String key : List.of("candidates", "facilities")) {
            if (hasFeatures(context.get(key))) available.add(key);
        }
    }

    public record Availability(Set<String> available, Set<String> missing, long contextVersion) {
    }
}
