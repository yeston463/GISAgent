package org.example.config;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import org.example.repository.RedisChatMemoryStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ConsultantConfig {

	// 1. 删除这里的 @Autowired 变量
	// private RedisChatMemoryStore chatMemoryStore;

	// 2. 将存储对象作为参数直接传给方法，Spring 会自动去容器里找这个 Bean
	@Bean
	public ChatMemoryProvider chatMemoryProvider(RedisChatMemoryStore redisStore) {
		return memoryId -> MessageWindowChatMemory.builder()
			.id(memoryId)
			.maxMessages(10)
			.chatMemoryStore(redisStore) // 使用传进来的参数
			.build();
	}
}