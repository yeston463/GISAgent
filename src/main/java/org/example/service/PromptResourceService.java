package org.example.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PromptResourceService {

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public String load(String resourcePath) {
        return cache.computeIfAbsent(resourcePath, this::readRequiredResource);
    }

    public String render(String resourcePath, Map<String, String> values) {
        String rendered = load(resourcePath);
        for (Map.Entry<String, String> entry : values.entrySet()) {
            rendered = rendered.replace("{{" + entry.getKey() + "}}", safe(entry.getValue()));
        }
        return rendered;
    }

    private String readRequiredResource(String resourcePath) {
        // SSE/CompletableFuture work may run with the system context loader;
        // use the application loader so resources inside the executable JAR
        // remain visible on asynchronous Agent requests.
        ClassPathResource resource = new ClassPathResource(
                resourcePath, PromptResourceService.class.getClassLoader());
        try (var input = resource.getInputStream()) {
            String content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            return content.replace("\uFEFF", "").trim();
        } catch (IOException e) {
            throw new IllegalStateException("Required prompt resource is unavailable: " + resourcePath, e);
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
