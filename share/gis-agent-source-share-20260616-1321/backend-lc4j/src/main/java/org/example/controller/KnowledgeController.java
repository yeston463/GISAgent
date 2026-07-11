package org.example.controller;

import org.example.service.KnowledgeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.Map;

@RestController
@RequestMapping("/api/knowledge")
@CrossOrigin(origins = "http://localhost:5173") // 允许前端跨域
public class KnowledgeController {

    @Autowired
    private KnowledgeService knowledgeService;

    @PostMapping("/upload")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) {
        try {
            // 调用服务层进行 RAG 入库
            knowledgeService.ingestDocument(file);

            // 返回给前端展示在“已学习列表”中
            return ResponseEntity.ok(Map.of(
                    "name", file.getOriginalFilename(),
                    "id", System.currentTimeMillis(),
                    "status", "success"
            ));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body("入库失败: " + e.getMessage());
        }
    }
}