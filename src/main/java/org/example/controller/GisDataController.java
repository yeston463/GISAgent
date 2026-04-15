package org.example.controller;

import org.example.service.GisContextService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/gis")
public class GisDataController {
    @Autowired
    private GisContextService contextService;

    @PostMapping("/upload-context")
    public ResponseEntity<String> upload(@RequestBody String geojson) {
        contextService.saveGeoJson(geojson);
        return ResponseEntity.ok("数据已同步至 AI 上下文");
    }
}
