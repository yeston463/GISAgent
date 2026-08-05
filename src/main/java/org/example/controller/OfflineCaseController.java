package org.example.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/gis")
public class OfflineCaseController {

    private static final String CASE_RESOURCE = "demo-case/case.json";
    private static final String RULES_RESOURCE = "demo-case/rules.json";

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${spatial.demo.enabled:false}")
    private boolean spatialDemoEnabled;

    @GetMapping("/offline-case")
    public ResponseEntity<Map<String, Object>> offlineCase() {
        if (!spatialDemoEnabled) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "status", "Disabled", "code", "spatial_demo_disabled",
                    "message", "Spatial demo data is disabled for this environment."));
        }
        try {
            Map<String, Object> caseData = readResource(CASE_RESOURCE);
            Map<String, Object> rules = readResource(RULES_RESOURCE);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "Success");
            response.put("demo", true);
            response.put("case", caseData);
            response.put("rules", rules);
            response.put("sourceData", "bundle");
            return ResponseEntity.ok(response);
        } catch (IOException missing) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "status", "Error", "code", "demo_case_missing",
                    "message", "Offline demo resources are missing or unreadable."));
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readResource(String path) throws IOException {
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            throw new IOException("Missing classpath resource: " + path);
        }
        try (InputStream input = resource.getInputStream()) {
            String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            return objectMapper.readValue(json, Map.class);
        }
    }
}