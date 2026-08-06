package org.example.controller;

import org.example.service.KnowledgeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeController.class);
    private static final long MAX_UPLOAD_BYTES = 20L * 1024 * 1024;
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of("md", "txt", "pdf");

    @Autowired
    private KnowledgeService knowledgeService;

    @PostMapping("/upload")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("code", "file_required"));
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            return ResponseEntity.status(413).body(Map.of("code", "file_too_large", "maxBytes", MAX_UPLOAD_BYTES));
        }
        String fileName = safeFileName(file.getOriginalFilename());
        if (!SUPPORTED_EXTENSIONS.contains(extension(fileName))) {
            return ResponseEntity.badRequest().body(Map.of("code", "unsupported_file_type", "supported", SUPPORTED_EXTENSIONS));
        }
        try {
            // 调用服务层进行 RAG 入库
            knowledgeService.ingestDocument(file);

            // 返回给前端展示在“已学习列表”中
            return ResponseEntity.ok(Map.of(
                    "name", fileName,
                    "id", UUID.randomUUID().toString(),
                    "status", "success"
            ));
        } catch (Exception e) {
            log.warn("Knowledge upload failed for {}", fileName, e);
            return ResponseEntity.status(500).body(Map.of("code", "knowledge_ingest_failed"));
        }
    }

    private String safeFileName(String originalName) {
        String value = originalName == null ? "uploaded-document" : originalName.replace('\\', '/');
        int separator = value.lastIndexOf('/');
        return (separator >= 0 ? value.substring(separator + 1) : value).trim();
    }

    private String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return knowledgeService.getLoadStatus();
    }

    @PostMapping("/reload")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Object> reload() {
        return knowledgeService.loadBundledContent();
    }
}
