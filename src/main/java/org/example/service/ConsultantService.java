package org.example.service;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V; // 注意引入这个
import dev.langchain4j.service.spring.AiService;
import reactor.core.publisher.Flux;


public interface ConsultantService {


	@UserMessage("用户发送了指令：{{message}}，请根据此指令生成 GIS 指令。")
	String chat(
			@MemoryId String memoryId,
			@V("message") String message // 2. 显式添加 @V 注解
	);
}