package org.example.memory;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import jakarta.annotation.PostConstruct;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 长期记忆存储：pgvector + Redis 双层架构
 *
 * Redis: 短期会话记忆（当前对话上下文）
 * pgvector: 长期记忆（用户偏好、分析历史、知识沉淀）
 *
 * pgvector 不可用时自动降级，不影响主体功能。
 */
@Repository
public class PgVectorMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(PgVectorMemoryStore.class);

    @Autowired(required = false)
    private JdbcTemplate jdbc;

    @Autowired
    private EmbeddingModel embeddingModel;

    private volatile boolean available = false;

    @PostConstruct
    public void init() {
        try {
            if (jdbc == null) {
                log.warn("JdbcTemplate 未注入，pgvector 长期记忆功能不可用");
                return;
            }
            jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS agent_memory (
                    id BIGSERIAL PRIMARY KEY,
                    user_id TEXT NOT NULL DEFAULT 'default',
                    memory_type TEXT NOT NULL,
                    content TEXT NOT NULL,
                    metadata JSONB DEFAULT '{}',
                    embedding vector(1536),
                    created_at TIMESTAMP DEFAULT NOW(),
                    expires_at TIMESTAMP
                )
            """);
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_memory_user_type ON agent_memory (user_id, memory_type)");
            jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_memory_expires
                ON agent_memory (expires_at) WHERE expires_at IS NOT NULL
            """);
            jdbc.execute("""
                CREATE TABLE IF NOT EXISTS analysis_history (
                    id BIGSERIAL PRIMARY KEY,
                    user_id TEXT NOT NULL DEFAULT 'default',
                    location TEXT,
                    analysis_type TEXT,
                    input_summary TEXT,
                    result_json JSONB,
                    embedding vector(1536),
                    created_at TIMESTAMP DEFAULT NOW()
                )
            """);
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_history_user ON analysis_history (user_id, created_at DESC)");
            available = true;
            log.info("pgvector 长期记忆表初始化完成");
        } catch (Exception e) {
            available = false;
            log.warn("pgvector 初始化失败（数据库不可用），长期记忆功能降级为仅 Redis 会话记忆");
            log.warn("原因: {}", e.getMessage());
        }
    }

    private String toVectorStr(List<Float> vec) {
        return "[" + vec.stream().map(String::valueOf).collect(Collectors.joining(",")) + "]";
    }

    // ========== 用户偏好 ==========

    public void savePreference(String userId, String key, String value) {
        if (!available) return;
        try {
            String content = key + ": " + value;
            Embedding embedding = embeddingModel.embed(content).content();
            jdbc.update("DELETE FROM agent_memory WHERE user_id=? AND memory_type='preference' AND metadata->>'key'=?",
                userId, key);
            jdbc.update("""
                INSERT INTO agent_memory (user_id, memory_type, content, metadata, embedding)
                VALUES (?, 'preference', ?, jsonb_build_object('key', ?), ?::vector)
                """, userId, content, key, toVectorStr(embedding.vectorAsList()));
        } catch (Exception e) {
            log.warn("savePreference 失败: {}", e.getMessage());
        }
    }

    public String getPreference(String userId, String key) {
        if (!available) return null;
        try {
            List<String> rows = jdbc.query("""
                SELECT content FROM agent_memory
                WHERE user_id=? AND memory_type='preference'
                AND metadata->>'key'=?
                ORDER BY created_at DESC LIMIT 1
                """, (rs, rn) -> rs.getString("content"), userId, key);
            if (rows.isEmpty()) return null;
            return rows.get(0).split(": ", 2)[1];
        } catch (Exception e) {
            log.warn("getPreference 失败: {}", e.getMessage());
            return null;
        }
    }

    public Map<String, String> getAllPreferences(String userId) {
        if (!available) return Map.of();
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT content FROM agent_memory WHERE user_id=? AND memory_type='preference'", userId);
            Map<String, String> prefs = new HashMap<>();
            for (Map<String, Object> row : rows) {
                String[] parts = ((String) row.get("content")).split(": ", 2);
                if (parts.length == 2) prefs.put(parts[0], parts[1]);
            }
            return prefs;
        } catch (Exception e) {
            log.warn("getAllPreferences 失败: {}", e.getMessage());
            return Map.of();
        }
    }

    // ========== 分析历史 ==========

    public void saveAnalysis(String userId, String location, String analysisType,
                             String inputSummary, Map<String, Object> result) {
        if (!available) return;
        try {
            String content = String.format("位置: %s, 分析: %s, %s", location, analysisType, inputSummary);
            Embedding embedding = embeddingModel.embed(content).content();
            jdbc.update("""
                INSERT INTO analysis_history (user_id, location, analysis_type, input_summary, result_json, embedding)
                VALUES (?, ?, ?, ?, ?::jsonb, ?::vector)
                """,
                userId, location, analysisType, inputSummary,
                JSON.toJSONString(result), toVectorStr(embedding.vectorAsList()));
        } catch (Exception e) {
            log.warn("saveAnalysis 失败: {}", e.getMessage());
        }
    }

    public List<Map<String, Object>> getRecentAnalyses(String userId, int limit) {
        if (!available) return List.of();
        try {
            return jdbc.queryForList(
                "SELECT id, location, analysis_type, input_summary, result_json, created_at " +
                "FROM analysis_history WHERE user_id=? " +
                "ORDER BY created_at DESC LIMIT ?", userId, limit);
        } catch (Exception e) {
            log.warn("getRecentAnalyses 失败: {}", e.getMessage());
            return List.of();
        }
    }

    public List<Map<String, Object>> searchSimilarAnalyses(String userId, String query, int limit) {
        if (!available) return List.of();
        try {
            Embedding queryEmb = embeddingModel.embed(query).content();
            return jdbc.queryForList(
                "SELECT id, location, analysis_type, input_summary, result_json, created_at, " +
                "1 - (embedding <=> ?::vector) AS similarity " +
                "FROM analysis_history WHERE user_id=? " +
                "ORDER BY similarity DESC LIMIT ?", toVectorStr(queryEmb.vectorAsList()), userId, limit);
        } catch (Exception e) {
            log.warn("searchSimilarAnalyses 失败: {}", e.getMessage());
            return List.of();
        }
    }

    // ========== 清理过期记忆 ==========

    public void cleanupExpired() {
        if (!available) return;
        try {
            jdbc.update("DELETE FROM agent_memory WHERE expires_at IS NOT NULL AND expires_at < NOW()");
        } catch (Exception e) {
            log.warn("cleanupExpired 失败: {}", e.getMessage());
        }
    }
}
