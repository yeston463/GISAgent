package org.example.repository;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;
@Repository
public class RedisChatMemoryStore implements ChatMemoryStore {
	@Autowired
	private StringRedisTemplate redisTemplate;

	// 定义一个统一的前缀
	private static final String PREFIX = "chat_memory:";

	@Override
	public List<ChatMessage> getMessages(Object memoryId) {
		// 关键：必须加上前缀才能读到数据
		String json = redisTemplate.opsForValue().get(PREFIX + memoryId.toString());
		if (json == null || json.trim().isEmpty()) {
			return new java.util.ArrayList<>();
		}
		try {
			return ChatMessageDeserializer.messagesFromJson(json);
		} catch (Exception e) {
			return new java.util.ArrayList<>();
		}
	}

	@Override
	public void updateMessages(Object memoryId, List<ChatMessage> list) {
		String json = ChatMessageSerializer.messagesToJson(list);
		// 关键：写的时候也带前缀
		redisTemplate.opsForValue().set(PREFIX + memoryId.toString(), json, Duration.ofDays(1));
	}

	@Override
	public void deleteMessages(Object memoryId) {
		redisTemplate.delete(PREFIX + memoryId.toString());
	}
}