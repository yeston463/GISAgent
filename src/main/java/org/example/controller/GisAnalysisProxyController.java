package org.example.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.Enumeration;
import java.util.Set;

/** Same-origin bridge for browser calls to the local Python GIS service. */
@RestController
public class GisAnalysisProxyController {
    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection", "content-length", "host", "keep-alive", "transfer-encoding", "upgrade");

    @Autowired
    private RestTemplate restTemplate;

    @Value("${gis.python-service-url:http://127.0.0.1:8000/analysis}")
    private String pythonServiceUrl;

    @RequestMapping(value = {"/analysis", "/analysis/**"}, method = {
            RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.PATCH})
    public ResponseEntity<byte[]> proxy(HttpServletRequest request) throws IOException {
        String target = targetUrl(request);
        HttpHeaders requestHeaders = copyRequestHeaders(request);
        byte[] body = request.getInputStream().readAllBytes();
        try {
            ResponseEntity<byte[]> upstream = restTemplate.exchange(
                    target,
                    HttpMethod.valueOf(request.getMethod()),
                    new HttpEntity<>(body, requestHeaders),
                    byte[].class);
            HttpHeaders responseHeaders = copyResponseHeaders(upstream.getHeaders());
            return new ResponseEntity<>(upstream.getBody(), responseHeaders, upstream.getStatusCode());
        } catch (HttpStatusCodeException error) {
            return new ResponseEntity<>(error.getResponseBodyAsByteArray(),
                    copyResponseHeaders(error.getResponseHeaders()), error.getStatusCode());
        } catch (Exception error) {
            return ResponseEntity.status(502)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .body("{\"status\":\"Error\",\"message\":\"GIS analysis service is unavailable.\"}".getBytes());
        }
    }

    private String targetUrl(HttpServletRequest request) {
        String prefix = request.getContextPath() + "/analysis";
        String path = request.getRequestURI().substring(prefix.length());
        String target = pythonServiceUrl.replaceAll("/+$", "") + path;
        String query = request.getQueryString();
        return query == null || query.isBlank() ? target : target + "?" + query;
    }

    private HttpHeaders copyRequestHeaders(HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();
        Enumeration<String> names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (HOP_BY_HOP_HEADERS.contains(name.toLowerCase())) continue;
            Enumeration<String> values = request.getHeaders(name);
            while (values.hasMoreElements()) headers.add(name, values.nextElement());
        }
        return headers;
    }

    private HttpHeaders copyResponseHeaders(HttpHeaders upstream) {
        HttpHeaders headers = new HttpHeaders();
        if (upstream == null) return headers;
        upstream.forEach((name, values) -> {
            if (!HOP_BY_HOP_HEADERS.contains(name.toLowerCase())) headers.put(name, values);
        });
        return headers;
    }
}
