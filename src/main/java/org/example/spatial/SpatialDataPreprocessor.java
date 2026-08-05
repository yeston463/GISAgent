package org.example.spatial;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Validates uploaded spatial inputs and normalizes supported vector coordinates to WGS84. */
@Component
public class SpatialDataPreprocessor {
    private static final String WGS84 = "EPSG:4326";
    private static final String WEB_MERCATOR = "EPSG:3857";

    public VectorData prepareVector(JSONObject input, String declaredCrs) {
        if (input == null) throw new IllegalArgumentException("GeoJSON is required");
        JSONObject data = JSON.parseObject(JSON.toJSONString(input));
        String sourceCrs = canonicalCrs(declaredCrs, data);
        if (!WGS84.equals(sourceCrs) && !WEB_MERCATOR.equals(sourceCrs)) {
            throw new IllegalArgumentException("Unsupported vector CRS: " + sourceCrs
                    + ". Upload EPSG:4326 or EPSG:3857 data.");
        }
        if (WEB_MERCATOR.equals(sourceCrs)) transformCoordinateNodes(data);
        Bounds bounds = new Bounds();
        Set<String> geometryTypes = new LinkedHashSet<>();
        inspect(data, bounds, geometryTypes);
        if (bounds.count == 0 || !bounds.valid()) {
            throw new IllegalArgumentException("Vector coordinates are missing or outside WGS84 bounds");
        }
        data.remove("crs");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("dataType", "vector");
        metadata.put("sourceCrs", sourceCrs);
        metadata.put("normalizedCrs", WGS84);
        metadata.put("bbox", List.of(round(bounds.minX), round(bounds.minY), round(bounds.maxX), round(bounds.maxY)));
        metadata.put("featureCount", featureCount(data));
        metadata.put("geometryTypes", new ArrayList<>(geometryTypes));
        metadata.put("normalizedAt", Instant.now().toString());
        return new VectorData(data, metadata);
    }

    public Map<String, Object> inspectRaster(Path path, String extension, String declaredCrs, long sizeBytes) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("dataType", "raster");
        metadata.put("format", extension);
        metadata.put("sizeBytes", sizeBytes);
        metadata.put("sourceCrs", canonicalCrs(declaredCrs, null));
        metadata.put("normalizedCrs", WGS84);
        metadata.put("normalizedAt", Instant.now().toString());
        if ("asc".equals(extension)) {
            metadata.putAll(inspectAsciiGrid(path));
        } else {
            metadata.put("metadataStatus", "reader_required");
            metadata.put("reader", "rasterio");
        }
        return metadata;
    }

    private Map<String, Object> inspectAsciiGrid(Path path) {
        try (BufferedReader reader = Files.newBufferedReader(path)) {
            Map<String, Double> header = new LinkedHashMap<>();
            for (int index = 0; index < 6; index++) {
                String line = reader.readLine();
                if (line == null) throw new IOException("ASCII Grid header is incomplete");
                String[] parts = line.trim().split("\\s+", 2);
                if (parts.length != 2) throw new IOException("Invalid ASCII Grid header");
                header.put(parts[0].toLowerCase(Locale.ROOT), Double.parseDouble(parts[1]));
            }
            double columns = requiredHeader(header, "ncols");
            double rows = requiredHeader(header, "nrows");
            double x = requiredHeader(header, "xllcorner");
            double y = requiredHeader(header, "yllcorner");
            double cellSize = requiredHeader(header, "cellsize");
            if (columns <= 0 || rows <= 0 || cellSize <= 0) throw new IOException("Invalid ASCII Grid dimensions");
            if (x < -180 || x > 180 || y < -90 || y > 90 || x + columns * cellSize > 180 || y + rows * cellSize > 90) {
                throw new IOException("ASCII Grid must use WGS84 degree coordinates");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("metadataStatus", "ready");
            result.put("width", (int) columns);
            result.put("height", (int) rows);
            result.put("resolution", List.of(cellSize, cellSize));
            result.put("bbox", List.of(x, y, x + columns * cellSize, y + rows * cellSize));
            if (header.containsKey("nodata_value")) result.put("noData", header.get("nodata_value"));
            return result;
        } catch (IOException | NumberFormatException error) {
            throw new IllegalArgumentException("Unable to read ASCII Grid metadata: " + error.getMessage(), error);
        }
    }

    private double requiredHeader(Map<String, Double> header, String name) throws IOException {
        Double value = header.get(name);
        if (value == null) throw new IOException("Missing " + name + " in ASCII Grid header");
        return value;
    }

    private String canonicalCrs(String declaredCrs, JSONObject data) {
        String value = declaredCrs;
        if ((value == null || value.isBlank()) && data != null) {
            Object crs = data.get("crs");
            if (crs instanceof JSONObject object && object.getJSONObject("properties") != null) {
                value = object.getJSONObject("properties").getString("name");
            } else if (crs != null) value = String.valueOf(crs);
        }
        String normalized = value == null || value.isBlank() ? WGS84 : value.toUpperCase(Locale.ROOT);
        if (normalized.contains("3857") || normalized.contains("900913")) return WEB_MERCATOR;
        if (normalized.contains("4326") || normalized.contains("CRS84")) return WGS84;
        return normalized;
    }

    private void transformCoordinateNodes(Object node) {
        if (node instanceof JSONObject object) {
            object.forEach((key, value) -> {
                if ("coordinates".equals(key)) transformCoordinates(value);
                else transformCoordinateNodes(value);
            });
        } else if (node instanceof JSONArray array) {
            for (Object item : array) transformCoordinateNodes(item);
        }
    }

    private void transformCoordinates(Object node) {
        if (!(node instanceof JSONArray array)) return;
        if (array.size() >= 2 && array.get(0) instanceof Number && array.get(1) instanceof Number) {
            double x = ((Number) array.get(0)).doubleValue();
            double y = ((Number) array.get(1)).doubleValue();
            double longitude = Math.toDegrees(x / 6378137.0);
            double latitude = Math.toDegrees(2.0 * Math.atan(Math.exp(y / 6378137.0)) - Math.PI / 2.0);
            array.set(0, longitude);
            array.set(1, Math.max(-85.05112878, Math.min(85.05112878, latitude)));
            return;
        }
        for (Object item : array) transformCoordinates(item);
    }

    private void inspect(Object node, Bounds bounds, Set<String> geometryTypes) {
        if (node instanceof JSONObject object) {
            Object type = object.get("type");
            if (type instanceof String value && Set.of("Point", "LineString", "Polygon", "MultiPolygon", "MultiLineString").contains(value)) {
                geometryTypes.add(value);
            }
            Object coordinates = object.get("coordinates");
            if (coordinates != null) inspectCoordinates(coordinates, bounds);
            object.forEach((key, value) -> {
                if (!"coordinates".equals(key)) inspect(value, bounds, geometryTypes);
            });
        } else if (node instanceof JSONArray array) {
            for (Object item : array) inspect(item, bounds, geometryTypes);
        }
    }

    private void inspectCoordinates(Object node, Bounds bounds) {
        if (!(node instanceof JSONArray array)) return;
        if (array.size() >= 2 && array.get(0) instanceof Number && array.get(1) instanceof Number) {
            bounds.add(((Number) array.get(0)).doubleValue(), ((Number) array.get(1)).doubleValue());
            return;
        }
        for (Object item : array) inspectCoordinates(item, bounds);
    }

    private int featureCount(JSONObject data) {
        JSONArray features = data.getJSONArray("features");
        return features == null ? 1 : features.size();
    }

    private double round(double value) {
        return Math.round(value * 1_000_000d) / 1_000_000d;
    }

    public record VectorData(JSONObject geoJson, Map<String, Object> metadata) { }

    private static final class Bounds {
        private double minX = Double.POSITIVE_INFINITY;
        private double minY = Double.POSITIVE_INFINITY;
        private double maxX = Double.NEGATIVE_INFINITY;
        private double maxY = Double.NEGATIVE_INFINITY;
        private int count;

        void add(double x, double y) {
            minX = Math.min(minX, x); minY = Math.min(minY, y);
            maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); count++;
        }

        boolean valid() {
            return minX >= -180 && maxX <= 180 && minY >= -90 && maxY <= 90;
        }
    }
}
