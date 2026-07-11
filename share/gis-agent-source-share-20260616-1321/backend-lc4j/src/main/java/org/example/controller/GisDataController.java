package org.example.controller;

import com.alibaba.fastjson.JSON;
import org.example.service.GisContextService;
import org.springframework.beans.factory.annotation.Autowired;
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
    public Map<String, Object> upload(@RequestBody Map<String, Object> body) {

        System.out.println("📥 收到前端上下文: " + body.keySet());

        contextService.saveGeoJson(JSON.toJSONString(body));

        // 🔥 直接调用 Python
        String result = restTemplate.postForObject(
                "http://127.0.0.1:8000/analysis/urban_metrics",
                body,
                String.class
        );

        return JSON.parseObject(result);
    }
}
