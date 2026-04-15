package org.example.config;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.dashscope.QwenChatModel;
import dev.langchain4j.model.dashscope.QwenEmbeddingModel; // 👈 确保引入这个
import dev.langchain4j.model.embedding.EmbeddingModel;      // 👈 确保引入这个
import dev.langchain4j.service.AiServices;
import org.example.service.ConsultantService;
import org.example.tools.pyGisTools;
import org.example.tools.GisMapTools;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    @Value("${QWEN-APIKEY}") // 确保这里的 Key 对应你的 application.properties
    private String apiKey;

    // 1. 定义聊天模型 (普通阻塞式，解决之前的工具调用 Bug)
    @Bean
    public ChatLanguageModel chatLanguageModel() {
        return QwenChatModel.builder()
                .apiKey(apiKey)
                .modelName("qwen-max")
                .build();
    }

    // 2. 【核心修复】：重新定义向量模型，供 KnowledgeService 使用
    @Bean
    public EmbeddingModel embeddingModel() {
        return QwenEmbeddingModel.builder()
                .apiKey(apiKey)
                .modelName("text-embedding-v2") // 阿里通用的向量模型
                .build();
    }

    // 3. 定义 AI 服务
    @Bean
    public ConsultantService consultantService(
            ChatLanguageModel chatLanguageModel,
            GisMapTools gisMapTools,
            pyGisTools pyGisTools) {

        return AiServices.builder(ConsultantService.class)
                .chatLanguageModel(chatLanguageModel)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.withMaxMessages(10))
                .tools(gisMapTools, pyGisTools)
                .systemMessageProvider(memoryId -> """
                         你是一个专业的 3D GIS 规划分析师。
                        
                                 【核心交互规范】：
                                 你是一个具备地理感知能力的 3D GIS 专家。
                        
                                                               【核心技能：链式任务流】：
                                                               1. 地名定位：如果用户提到地名（如“故宫”），你必须【先】调用 `geocode` 获取 WGS84 坐标。
                                                               2. 视觉跳转：拿到坐标后，必须下达 `flyTo` 指令让前端移动视角。
                                                               3. 空间分析：如果用户要求分析（如缓冲区、容积率），你必须使用从 `geocode` 拿到的精确坐标去调用 `pyGisTools` 里的算子。
                        
                                                               【示例场景】：
                                                               用户：“看看天安门周围 500 米的开发强度。”
                                                               你的动作顺序：
                                                               1. call geocode("天安门") -> 得到坐标。
                                                               2. call executeBufferAnalysis(坐标, 500) -> 得到缓冲区。
                                                               3. call calculateUrbanMetrics() -> 得到容积率数据。
                                                               4. call openAnalysisDashboard() -> 弹出图表。
                                                               
                                                               【缓冲区分析协议】：
                                                                       当用户要求在某个地名建立缓冲区时：
                                                                                                             1. 调用 `geocode` 获取坐标。
                                                                                                             2. 拿到坐标数组后，立即调用 `executeBufferAnalysis` 算子。
                                                                                                             3. 将算子返回的内容（包含 action: renderAnalysisResult）直接放入你的 "commands" 数组中。
                        
                                                                                                           你的目标是：让 Python 引擎计算几何体，让前端只负责渲染。
                                                               ""\"
                            你的每一个指令都必须包含 "commands" 数组。
                                    ""\"
                    """)
                .build();
    }
}