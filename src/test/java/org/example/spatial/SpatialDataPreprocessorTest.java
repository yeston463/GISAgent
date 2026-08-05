package org.example.spatial;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpatialDataPreprocessorTest {
    private final SpatialDataPreprocessor preprocessor = new SpatialDataPreprocessor();

    @Test
    void webMercatorGeoJsonIsNormalizedToWgs84WithMetadata() {
        JSONObject source = JSON.parseObject("""
                {"type":"Feature","geometry":{"type":"Point","coordinates":[13522390,3640000]},"properties":{"name":"sample"}}
                """);

        SpatialDataPreprocessor.VectorData result = preprocessor.prepareVector(source, "EPSG:3857");
        List<?> coordinates = result.geoJson().getJSONObject("geometry").getJSONArray("coordinates");

        assertTrue(((Number) coordinates.get(0)).doubleValue() > 121.0);
        assertTrue(((Number) coordinates.get(0)).doubleValue() < 122.0);
        assertEquals("EPSG:4326", result.metadata().get("normalizedCrs"));
        assertEquals(1, result.metadata().get("featureCount"));
    }

    @Test
    void unsupportedCrsReturnsAValidationMessage() {
        JSONObject source = JSON.parseObject("""
                {"type":"Feature","geometry":{"type":"Point","coordinates":[121.47,31.23]},"properties":{}}
                """);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> preprocessor.prepareVector(source, "EPSG:32651"));

        assertTrue(error.getMessage().contains("Unsupported vector CRS"));
    }
}
