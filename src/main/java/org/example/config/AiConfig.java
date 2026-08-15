package org.example.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.dashscope.QwenEmbeddingModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Qualifier;

@Configuration
public class AiConfig {

    @Value("${QWEN-APIKEY}")
    private String apiKey;

    @Value("${ai.qwen.model-name:qwen3.7-flash-2026-07-15}")
    private String chatModelName;

    @Value("${ai.qwen.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String chatBaseUrl;

    @Value("${DEEPSEEK_API_KEY:}")
    private String deepSeekApiKey;

    @Value("${ai.deepseek.router-model-name:deepseek-v4-flash}")
    private String routerModelName;

    @Value("${ai.deepseek.base-url:https://api.deepseek.com}")
    private String routerBaseUrl;

    @Value("${rag.min-score:0.6}")
    private double minScore;

    @Value("${rag.max-results:3}")
    private int maxResults;

    @Bean
    @Primary
    public ChatLanguageModel chatLanguageModel() {
        if (deepSeekApiKey != null && !deepSeekApiKey.isBlank()) {
            return OpenAiChatModel.builder()
                    .apiKey(deepSeekApiKey)
                    .baseUrl(routerBaseUrl)
                    .modelName(routerModelName)
                    .temperature(0.2)
                    .build();
        }
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .modelName(chatModelName)
                .baseUrl(chatBaseUrl)
                .temperature(0.0)
                .build();
    }

    @Bean("spatialRouterModel")
    public ChatLanguageModel spatialRouterModel() {
        // Same DeepSeek-first policy as the primary chat model, so intent
        // routing prefers the configured DeepSeek key and only falls back to
        // the DashScope key when no DeepSeek key is provided.
        if (deepSeekApiKey != null && !deepSeekApiKey.isBlank()) {
            return OpenAiChatModel.builder()
                    .apiKey(deepSeekApiKey)
                    .baseUrl(routerBaseUrl)
                    .modelName(routerModelName)
                    .temperature(0.0)
                    .build();
        }
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(chatBaseUrl)
                .modelName(chatModelName)
                .temperature(0.0)
                .build();
    }

    /**
     * Keep RAG usable without requiring PostgreSQL/pgvector at startup. The
     * store is intentionally process-local; a deployment can provide a
     * persistent EmbeddingStore bean and this fallback will back off.
     */
    @Bean
    @ConditionalOnMissingBean(EmbeddingStore.class)
    public EmbeddingStore<TextSegment> embeddingStore() {
        return new InMemoryEmbeddingStore<>();
    }

    @Bean
    public EmbeddingModel embeddingModel() {
        return QwenEmbeddingModel.builder()
                .apiKey(apiKey)
                .modelName("text-embedding-v2")
                .build();
    }

    @Bean
    public ContentRetriever contentRetriever(
            EmbeddingStore<TextSegment> embeddingStore,
            EmbeddingModel embeddingModel) {
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(maxResults)
                .minScore(minScore)
                .build();
    }
}
