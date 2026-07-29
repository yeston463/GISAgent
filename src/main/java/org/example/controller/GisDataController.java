package org.example.controller;

import com.alibaba.fastjson.JSON;
import org.example.service.GisContextService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

@RestController
@RequestMapping("/api/gis")
public class GisDataController {

    @Autowired
    private GisContextService contextService;
    @Autowired
    private RestTemplate restTemplate;

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
            return ResponseEntity.ok(Map.of(
                    "status", "ContextSaved",
                    "contextSaved", true,
                    "contextVersion", saved.contextVersion(),
                    "hasAoi", body.containsKey("aoi"),
                    "message", "AOI context saved. Building acquisition will run through analyzeCurrentView."
            ));
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
}
