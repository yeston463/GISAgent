package org.example.controller;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.example.service.GisContextService;
import org.example.spatial.SpatialDemoContext;
import org.example.spatial.SpatialDataPreprocessor;
import org.example.spatial.GeoDataDiscoveryAgent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/gis")
public class GisDataController {
    private static final Set<String> VECTOR_EXTENSIONS = Set.of("geojson", "json");
    private static final Set<String> RASTER_EXTENSIONS = Set.of("asc", "tif", "tiff");
    private static final Set<String> CONTEXT_DATASETS = Set.of(
            "aoi", "buildings", "dem", "drainage_network", "river_network");
    private static final long MAX_VECTOR_BYTES = 20L * 1024 * 1024;
    private static final long MAX_RASTER_BYTES = 100L * 1024 * 1024;

    @Autowired
    private GisContextService contextService;
    @Autowired
    private RestTemplate restTemplate;
    @Autowired
    private SpatialDemoContext spatialDemoContext;
    @Autowired
    private SpatialDataPreprocessor spatialDataPreprocessor;
    @Autowired
    private GeoDataDiscoveryAgent geoDataDiscoveryAgent;

    @Value("${spatial.demo.enabled:false}")
    private boolean spatialDemoEnabled;

    @GetMapping("/context")
    public Map<String, Object> contextStatus(@RequestParam(defaultValue = "default") String memoryId) {
        return describeContext(memoryId, Map.of());
    }

    @PostMapping("/data-discovery")
    public Map<String, Object> discoverData(@RequestBody(required = false) DataDiscoveryRequest request) {
        String memoryId = request == null || request.memoryId() == null ? "default" : request.memoryId();
        contextService.activateSession(memoryId);
        Map<String, Object> result = new LinkedHashMap<>(geoDataDiscoveryAgent.discover(request == null ? List.of() : request.datasets()));
        result.put("memoryId", memoryId);
        return result;
    }

    @PostMapping("/data-discovery/import")
    public ResponseEntity<Map<String, Object>> importDiscoveredData(@RequestBody DataImportRequest request) {
        if (!"osm_overpass".equals(request.source())) return ResponseEntity.badRequest().body(Map.of("code", "import_source_not_supported"));
        if (!Set.of("buildings", "roads", "waterways").contains(request.dataset())) return ResponseEntity.badRequest().body(Map.of("code", "osm_dataset_not_supported"));
        contextService.activateSession(request.memoryId());
        try {
            JSONObject data = geoDataDiscoveryAgent.importOsm(request.dataset());
            String target = "roads".equals(request.dataset()) ? "road_network" : "waterways".equals(request.dataset()) ? "river_network" : "buildings";
            Map<String, Object> payload = new LinkedHashMap<>(); payload.put(target, data);
            contextService.saveGeoJson(JSON.toJSONString(payload));
            return ResponseEntity.ok(Map.of("status", "Success", "source", "osm_overpass", "dataset", target, "featureCount", data.getJSONArray("features").size(), "requiresAnalysisRequest", true));
        } catch (IllegalArgumentException error) { return ResponseEntity.badRequest().body(Map.of("code", error.getMessage())); }
    }

    @PostMapping("/demo-context")
    public ResponseEntity<Map<String, Object>> loadDemoContext(@RequestBody(required = false) DemoContextRequest request) {
        if (!spatialDemoEnabled) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "status", "Disabled", "code", "spatial_demo_disabled",
                    "message", "Spatial demo data is disabled for this environment."));
        }
        String memoryId = request == null ? "default" : request.memoryId();
        Map<String, Object> payload = spatialDemoContext.payload();
        long currentVersion = contextService.getContextVersion(memoryId);
        GisContextService.SaveResult saved = contextService.saveGeoJson(
                memoryId, JSON.toJSONString(payload), currentVersion);
        if (saved.conflict()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "status", "ContextConflict", "contextSaved", false,
                    "contextVersion", saved.contextVersion()));
        }
        Map<String, Object> response = describeContext(memoryId, payload);
        response.put("status", "Success");
        response.put("contextSaved", true);
        response.put("demo", true);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/upload-context")
    public ResponseEntity<Map<String, Object>> upload(@RequestBody Map<String, Object> body) {

        System.out.println("📥 收到前端上下文: " + body.keySet());

        String memoryId = String.valueOf(body.getOrDefault("memoryId", "default"));
        long expectedContextVersion = parseVersion(body.get("contextVersion"));
        body.remove("memoryId");
        body.remove("contextVersion");
        GisContextService.SaveResult saved = contextService.saveGeoJson(
                memoryId, JSON.toJSONString(body), expectedContextVersion);
        if (saved.conflict()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "status", "ContextConflict",
                    "contextSaved", false,
                    "contextVersion", saved.contextVersion(),
                    "message", "GIS context changed in another request; refresh the current context before retrying."
            ));
        }

        // 🔥 直接调用 Python
        if (!body.containsKey("buildings")) {
            Map<String, Object> response = describeContext(memoryId, Map.of());
            response.put("status", "ContextSaved");
            response.put("contextSaved", true);
            response.put("contextVersion", saved.contextVersion());
            response.put("message", "Context saved. Building acquisition will run through analyzeCurrentView.");
            return ResponseEntity.ok(response);
        }

        String result = restTemplate.postForObject(
                "http://127.0.0.1:8000/analysis/urban_metrics",
                body,
                String.class
        );
        Map<String, Object> response = JSON.parseObject(result);
        response.put("contextSaved", true);
        response.put("contextVersion", saved.contextVersion());
        return ResponseEntity.ok(response);
    }

    @PostMapping(value = "/data-file", consumes = "multipart/form-data")
    public ResponseEntity<Map<String, Object>> uploadSpatialData(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "dem") String dataset,
            @RequestParam(defaultValue = "default") String memoryId,
            @RequestParam(defaultValue = "-1") long contextVersion,
            @RequestParam(defaultValue = "") String sourceCrs) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("status", "InvalidData", "code", "file_required"));
        }
        String normalizedDataset = dataset == null ? "" : dataset.trim().toLowerCase(Locale.ROOT);
        if (!CONTEXT_DATASETS.contains(normalizedDataset)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "InvalidData", "code", "unsupported_dataset", "dataset", dataset));
        }
        String fileName = file.getOriginalFilename() == null ? "spatial-data" : file.getOriginalFilename();
        String extension = extension(fileName);
        if (VECTOR_EXTENSIONS.contains(extension)) {
            return storeVectorFile(file, normalizedDataset, memoryId, contextVersion, fileName, sourceCrs);
        }
        if (RASTER_EXTENSIONS.contains(extension)) {
            return storeRasterFile(file, normalizedDataset, memoryId, contextVersion, fileName, extension, sourceCrs);
        }
        return ResponseEntity.badRequest().body(Map.of(
                "status", "InvalidData", "code", "unsupported_spatial_format",
                "supported", List.of(".geojson", ".json", ".asc", ".tif", ".tiff")));
    }

    @PostMapping("/ground-dem")
    public ResponseEntity<Map<String, Object>> saveGroundDem(@RequestBody Map<String, Object> body) {
        String memoryId = String.valueOf(body.getOrDefault("memoryId", "default"));
        long contextVersion = parseVersion(body.get("contextVersion"));
        try {
            JSONObject dem = JSON.parseObject(JSON.toJSONString(body.get("dem")));
            SpatialDataPreprocessor.VectorData prepared = spatialDataPreprocessor.prepareVector(
                    dem, String.valueOf(body.getOrDefault("sourceCrs", "EPSG:4326")));
            if (featureCount(prepared.geoJson()) < 3 || !hasElevationSamples(prepared.geoJson())) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "InvalidData", "code", "ground_dem_samples_required",
                        "message", "At least three elevation samples are required."));
            }
            Map<String, Object> metadata = new LinkedHashMap<>(prepared.metadata());
            metadata.put("source", "arcgis_ground_queryElevation");
            Map<String, Object> elevationQuality = elevationQuality(prepared.geoJson());
            metadata.put("elevationQuality", elevationQuality);
            Map<String, Object> payload = contextPayload(memoryId, "dem", prepared.geoJson(), metadata);
            GisContextService.SaveResult saved = contextService.saveGeoJson(
                    memoryId, JSON.toJSONString(payload), contextVersion);
            if (saved.conflict()) return contextConflict(saved.contextVersion());
            Map<String, Object> response = describeContext(memoryId, Map.of());
            response.put("status", "Success");
            response.put("dataset", "dem");
            response.put("dataType", "ground_samples");
            response.put("sampleCount", featureCount(prepared.geoJson()));
            response.put("metadata", metadata);
            return ResponseEntity.ok(response);
        } catch (Exception error) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "InvalidData", "code", "ground_dem_invalid", "message", safeMessage(error)));
        }
    }

    private ResponseEntity<Map<String, Object>> storeVectorFile(
            MultipartFile file, String dataset, String memoryId, long contextVersion, String fileName, String sourceCrs) {
        if (file.getSize() > MAX_VECTOR_BYTES) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of(
                    "status", "InvalidData", "code", "vector_file_too_large", "maxBytes", MAX_VECTOR_BYTES));
        }
        try {
            JSONObject geoJson = JSON.parseObject(new String(file.getBytes(), StandardCharsets.UTF_8));
            if (geoJson == null || !isGeoJson(geoJson)) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "InvalidData", "code", "invalid_geojson", "message", "The vector file must contain a GeoJSON object."));
            }
            SpatialDataPreprocessor.VectorData prepared = spatialDataPreprocessor.prepareVector(geoJson, sourceCrs);
            Map<String, Object> payload = contextPayload(memoryId, dataset, prepared.geoJson(), prepared.metadata());
            GisContextService.SaveResult saved = contextService.saveGeoJson(
                    memoryId, JSON.toJSONString(payload), contextVersion);
            if (saved.conflict()) return contextConflict(saved.contextVersion());
            Map<String, Object> response = describeContext(memoryId, Map.of());
            response.put("status", "Success");
            response.put("dataset", dataset);
            response.put("dataType", "vector");
            response.put("fileName", fileName);
            response.put("vectorData", prepared.geoJson());
            response.put("metadata", prepared.metadata());
            return ResponseEntity.ok(response);
        } catch (Exception error) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "InvalidData", "code", "vector_read_failed", "message", safeMessage(error)));
        }
    }

    private ResponseEntity<Map<String, Object>> storeRasterFile(
            MultipartFile file, String dataset, String memoryId, long contextVersion, String fileName, String extension,
            String sourceCrs) {
        if (!"dem".equals(dataset)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "InvalidData", "code", "raster_dataset_must_be_dem"));
        }
        if (file.getSize() > MAX_RASTER_BYTES) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of(
                    "status", "InvalidData", "code", "raster_file_too_large", "maxBytes", MAX_RASTER_BYTES));
        }
        Path target = rasterRoot().resolve(safeSession(memoryId))
                .resolve(UUID.randomUUID() + "." + extension).normalize();
        try {
            Files.createDirectories(target.getParent());
            try (var input = file.getInputStream()) {
                Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
            }
            Map<String, Object> raster = new LinkedHashMap<>();
            raster.put("kind", "raster");
            raster.put("format", extension);
            raster.put("path", target.toAbsolutePath().toString());
            raster.put("name", fileName);
            raster.put("sizeBytes", file.getSize());
            Map<String, Object> metadata = spatialDataPreprocessor.inspectRaster(target, extension, sourceCrs, file.getSize());
            raster.put("metadata", metadata);
            GisContextService.SaveResult saved = contextService.saveGeoJson(
                    memoryId, JSON.toJSONString(contextPayload(memoryId, dataset, raster, metadata)), contextVersion);
            if (saved.conflict()) {
                Files.deleteIfExists(target);
                return contextConflict(saved.contextVersion());
            }
            Map<String, Object> response = describeContext(memoryId, Map.of());
            response.put("status", "Success");
            response.put("dataset", dataset);
            response.put("dataType", "raster");
            response.put("fileName", fileName);
            response.put("format", extension);
            response.put("sizeBytes", file.getSize());
            response.put("metadata", metadata);
            return ResponseEntity.ok(response);
        } catch (IOException error) {
            try { Files.deleteIfExists(target); } catch (IOException ignored) { }
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "status", "Error", "code", "raster_store_failed", "message", safeMessage(error)));
        }
    }

    private ResponseEntity<Map<String, Object>> contextConflict(long version) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "status", "ContextConflict", "contextSaved", false, "contextVersion", version));
    }

    private Map<String, Object> contextPayload(String memoryId, String dataset, Object data, Map<String, Object> metadata) {
        JSONObject current;
        try { current = JSON.parseObject(contextService.getGeoJson(memoryId)); }
        catch (Exception ignored) { current = new JSONObject(); }
        JSONObject assets = current == null || current.getJSONObject("dataAssets") == null
                ? new JSONObject() : JSON.parseObject(current.getJSONObject("dataAssets").toJSONString());
        assets.put(dataset, metadata);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(dataset, data);
        payload.put("dataAssets", assets);
        return payload;
    }

    private boolean isGeoJson(JSONObject data) {
        String type = data.getString("type");
        return "FeatureCollection".equals(type) || "Feature".equals(type)
                || (data.containsKey("coordinates") && type != null);
    }

    private String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private Path rasterRoot() {
        return Path.of(System.getProperty("user.dir"), "cityengine-workspace", "gis-inputs")
                .toAbsolutePath().normalize();
    }

    private String safeSession(String sessionId) {
        String value = sessionId == null ? "default" : sessionId.replaceAll("[^A-Za-z0-9_-]", "_");
        return value.isBlank() ? "default" : value.substring(0, Math.min(value.length(), 120));
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private long parseVersion(Object rawVersion) {
        if (rawVersion == null) {
            return -1;
        }
        try {
            return Math.max(0, Long.parseLong(String.valueOf(rawVersion)));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private Map<String, Object> describeContext(String memoryId, Map<String, Object> extra) {
        JSONObject context;
        try {
            context = JSON.parseObject(contextService.getGeoJson(memoryId));
        } catch (Exception ignored) {
            context = new JSONObject();
        }
        boolean hasAoi = context != null && context.containsKey("aoi");
        Object buildings = context == null ? null : context.get("buildings");
        int buildingCount = featureCount(buildings);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("memoryId", memoryId == null || memoryId.isBlank() ? "default" : memoryId);
        response.put("contextVersion", contextService.getContextVersion(memoryId));
        response.put("hasAoi", hasAoi);
        response.put("hasBuildings", buildingCount > 0);
        response.put("buildingCount", buildingCount);
        if (hasAoi) response.put("aoi", context.get("aoi"));
        if (context != null && context.get("rainfall_scenario") != null) {
            response.put("rainfallScenario", context.get("rainfall_scenario"));
        }
        List<String> availableData = new ArrayList<>();
        if (hasAoi) availableData.add("aoi");
        if (buildingCount > 0) availableData.add("buildings");
        for (String dataset : List.of("dem", "rainfall_scenario", "drainage_network", "river_network")) {
            if (context != null && context.get(dataset) != null) availableData.add(dataset);
        }
        response.put("availableData", availableData);
        response.put("demoEnabled", spatialDemoEnabled);
        response.putAll(extra);
        return response;
    }

    private int featureCount(Object raw) {
        try {
            JSONObject collection = JSON.parseObject(JSON.toJSONString(raw));
            return collection == null || collection.getJSONArray("features") == null
                    ? 0 : collection.getJSONArray("features").size();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private boolean hasElevationSamples(JSONObject collection) {
        JSONArray features = collection.getJSONArray("features");
        if (features == null) return false;
        int valid = 0;
        for (Object value : features) {
            if (!(value instanceof JSONObject feature)) continue;
            JSONObject properties = feature.getJSONObject("properties");
            if (properties == null) continue;
            Object elevation = properties.get("elevation_m");
            if (elevation instanceof Number || elevation instanceof String text && !text.isBlank()) valid++;
        }
        return valid >= 3;
    }

    private Map<String, Object> elevationQuality(JSONObject collection) {
        JSONArray features = collection.getJSONArray("features");
        double minimum = Double.POSITIVE_INFINITY;
        double maximum = Double.NEGATIVE_INFINITY;
        int count = 0;
        if (features != null) for (Object value : features) {
            if (!(value instanceof JSONObject feature) || feature.getJSONObject("properties") == null) continue;
            try {
                double elevation = Double.parseDouble(String.valueOf(feature.getJSONObject("properties").get("elevation_m")));
                if (Double.isFinite(elevation)) { minimum = Math.min(minimum, elevation); maximum = Math.max(maximum, elevation); count++; }
            } catch (NumberFormatException ignored) { }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sampleCount", count);
        result.put("minimumElevationM", count == 0 ? null : Math.round(minimum * 1000d) / 1000d);
        result.put("maximumElevationM", count == 0 ? null : Math.round(maximum * 1000d) / 1000d);
        double span = count == 0 ? 0 : maximum - minimum;
        result.put("elevationSpanM", Math.round(span * 1000d) / 1000d);
        result.put("quality", span < 0.5 ? "low_relief" : span < 2 ? "limited_relief" : "usable");
        if (span < 0.5) result.put("warning", "Terrain relief is below 0.5 m; flood screening is sensitive to elevation noise.");
        return result;
    }

    public record DemoContextRequest(String memoryId) {}
    public record DataDiscoveryRequest(String memoryId, List<String> datasets) {}
    public record DataImportRequest(String memoryId, String source, String dataset) {}
}
