package org.example.spatial;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/** Protects graph publication independently from dynamic-code execution. */
@Component
public class KnowledgeGraphAdminGuard {
    @Value("${spatial.knowledge-graph.admin-mode:local}") private String mode;
    @Value("${spatial.knowledge-graph.admin-token:}") private String token;
    @Value("${SERVER_ADDRESS:127.0.0.1}") private String serverAddress;

    public boolean authorize(HttpServletRequest request) {
        if ("token".equalsIgnoreCase(mode)) return constantTimeEquals(request.getHeader("X-Spatial-Graph-Token"), token);
        String address = request.getRemoteAddr();
        return "127.0.0.1".equals(address) || "::1".equals(address)
                || "0:0:0:0:0:0:0:1".equals(address) || serverAddress.equals(address);
    }

    private boolean constantTimeEquals(String supplied, String expected) {
        if (supplied == null || expected == null || expected.isBlank()) return false;
        byte[] left = supplied.getBytes(StandardCharsets.UTF_8);
        byte[] right = expected.getBytes(StandardCharsets.UTF_8);
        if (left.length != right.length) return false;
        int difference = 0;
        for (int index = 0; index < left.length; index++) difference |= left[index] ^ right[index];
        return difference == 0;
    }
}
