package org.example.controller;

import org.example.service.ConsultantService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api")
public class ChatController {

	@Autowired
	private ConsultantService consultantService;

	/**
	 * 地理空间智能体对话接口
	 *
	 * produces = "text/event-stream;charset=UTF-8" 强制指定 UTF-8，解决 å¥½çš„ 乱码问题
	 * @CrossOrigin 允许 Vue 前端（通常是 5173 端口）跨域访问
	 */
	@CrossOrigin(origins = "http://localhost:5173")
	@GetMapping(value = "/chat", produces = "text/event-stream;charset=UTF-8")
	public Flux<String> chat(@RequestParam String memoryId, @RequestParam String message) {

		System.out.println("==== 收到智能体请求 ====");
		System.out.println("会话ID: " + memoryId);
		System.out.println("用户消息: " + message);

		try {
			// 返回从 Service 层获取的 Flux 流
			// Flux 会在大模型输出结束时自动发送完成信号，触发前端的 onerror/onclose
			return consultantService.chat(memoryId, message)
					.doOnComplete(() -> System.out.println("会话 " + memoryId + " 响应完成。"))
					.doOnError(throwable -> System.err.println("会话异常: " + throwable.getMessage()));
		} catch (Exception e) {
			System.err.println("控制器层捕获异常: " + e.getMessage());
			return Flux.just("Error: 无法连接到 AI 服务。");
		}
	}
}