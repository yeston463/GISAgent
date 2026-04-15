package org.example.controller;

import org.example.service.ConsultantService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono; // 需要引入 Mono

@RestController
@RequestMapping("/api")
public class ChatController {

	@Autowired
	private ConsultantService consultantService;

	@CrossOrigin(origins = "http://localhost:5173")
	@GetMapping(value = "/chat")
	public String chat(@RequestParam String memoryId, @RequestParam String message) {




			System.out.println("==== 收到同步智能体请求: " + message + " ====");
			// 调用非流式接口
			String response = consultantService.chat(memoryId, message);
			System.out.println("🤖 AI 最终响应: " + response);
			return response;


	}
}