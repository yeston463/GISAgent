package org.example.spatial;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stable sample data used only to verify the spatial-analysis workflow. */
@Component
public class SpatialDemoContext {

    public Map<String, Object> payload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("demoId", "shanghai-block-v1");
        payload.put("aoi", feature("demo-aoi", polygon(List.of(
                point(121.4700, 31.2300), point(121.4712, 31.2300),
                point(121.4712, 31.2310), point(121.4700, 31.2310),
                point(121.4700, 31.2300))), Map.of("name", "Spatial analysis demo AOI")));
        payload.put("buildings", Map.of(
                "type", "FeatureCollection",
                "features", List.of(
                        building("demo-building-1", 121.47012, 31.23012, 121.47038, 31.23037, 48, 14),
                        building("demo-building-2", 121.47052, 31.23018, 121.47082, 31.23046, 72, 21),
                        building("demo-building-3", 121.47028, 31.23062, 121.47070, 31.23088, 30, 9)
                )
        ));
        payload.put("dem", Map.of(
                "type", "FeatureCollection",
                "features", List.of(
                        elevationSample("dem-1", 121.47015, 31.23015, 6.8),
                        elevationSample("dem-2", 121.47055, 31.23015, 6.2),
                        elevationSample("dem-3", 121.47095, 31.23015, 5.7),
                        elevationSample("dem-4", 121.47015, 31.23055, 6.4),
                        elevationSample("dem-5", 121.47055, 31.23055, 4.9),
                        elevationSample("dem-6", 121.47095, 31.23055, 5.5),
                        elevationSample("dem-7", 121.47015, 31.23095, 6.1),
                        elevationSample("dem-8", 121.47055, 31.23095, 5.3),
                        elevationSample("dem-9", 121.47095, 31.23095, 5.8)
                )
        ));
        payload.put("rainfall_scenario", Map.of(
                "name", "demo-20-year-24-hour",
                "rainfallMm", 120,
                "durationHours", 24,
                "returnPeriodYears", 20,
                "source", "demo_fixture"
        ));
        return payload;
    }

    private Map<String, Object> elevationSample(String id, double longitude, double latitude, double elevation) {
        return feature(id, Map.of("type", "Point", "coordinates", List.of(longitude, latitude)), Map.of(
                "id", id,
                "elevation_m", elevation,
                "source", "demo_fixture"
        ));
    }

    private Map<String, Object> building(
            String id, double west, double south, double east, double north, int height, int floors) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("id", id);
        properties.put("name", id);
        properties.put("height", height);
        properties.put("building:levels", floors);
        properties.put("geometrySource", "demo_fixture");
        return feature(id, polygon(List.of(
                point(west, south), point(east, south), point(east, north),
                point(west, north), point(west, south))), properties);
    }

    private Map<String, Object> feature(String id, Map<String, Object> geometry, Map<String, Object> properties) {
        Map<String, Object> feature = new LinkedHashMap<>();
        feature.put("type", "Feature");
        feature.put("id", id);
        feature.put("geometry", geometry);
        feature.put("properties", properties);
        return feature;
    }

    private Map<String, Object> polygon(List<List<Double>> ring) {
        return Map.of("type", "Polygon", "coordinates", List.of(ring));
    }

    private List<Double> point(double longitude, double latitude) {
        return List.of(longitude, latitude);
    }
}
