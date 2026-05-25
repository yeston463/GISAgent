package org.example.controller;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import org.example.service.ConsultantService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api")
public class ChatController {

	@Autowired
	private ConsultantService consultantService;


	@CrossOrigin(origins = "http://localhost:5173")
	@GetMapping(value = "/chat")
	public String chat(@RequestParam String memoryId, @RequestParam String message) {

		System.out.println("📩 用户输入: " + message);

		try {
			String response = consultantService.chat(memoryId, message);

			System.out.println("🧠 AI返回: " + response);

			if (response == null || response.trim().isEmpty()) {
				return JSON.toJSONString(Map.of(
						"commands", new ArrayList<>(),
						"reply", "AI 未返回有效结果"
				));
			}

			// 👉 尝试解析 JSON（防止 AI 乱说）
			try {
				JSONObject json = JSON.parseObject(response);
				return json.toJSONString();
			} catch (Exception e) {
				// AI 不是 JSON，包装一下
				return JSON.toJSONString(Map.of(
						"commands", new ArrayList<>(),
						"reply", response
				));
			}

		} catch (Exception e) {
			e.printStackTrace(); // ❗关键：打印真实错误

			return """
        {
          "commands": [],
          "reply": "系统内部异常，请检查后端日志"
        }
        """;
		}
	}
}