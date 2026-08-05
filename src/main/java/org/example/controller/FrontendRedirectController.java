package org.example.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class FrontendRedirectController {

    @Value("${frontend.url:http://127.0.0.1:5173}")
    private String frontendUrl;

    @GetMapping({"/", "/index.html"})
    public String home() {
        return "redirect:" + frontendUrl;
    }
}
