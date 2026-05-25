package org.example.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.dashscope.QwenChatModel;
import dev.langchain4j.model.dashscope.QwenEmbeddingModel; // 👈 确保引入这个
import dev.langchain4j.model.embedding.EmbeddingModel;      // 👈 确保引入这个
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.store.embedding.EmbeddingStore;
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
                .modelName("qwen-turbo")
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
    @Bean
    public ContentRetriever contentRetriever(EmbeddingStore<TextSegment> embeddingStore, EmbeddingModel embeddingModel) {
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(3) // 每次找最相关的 3 条知识
                .minScore(0.0) // 相似度太低的不要
                .build();
    }

    // 3. 定义 AI 服务
    @Bean
    public ConsultantService consultantService(
            ChatLanguageModel chatLanguageModel,
            GisMapTools gisMapTools,
            pyGisTools pyGisTools,
                    ContentRetriever contentRetriever) {

        return AiServices.builder(ConsultantService.class)
                .chatLanguageModel(chatLanguageModel)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.withMaxMessages(10))
                .tools(gisMapTools, pyGisTools).contentRetriever(contentRetriever)
                .systemMessageProvider(memoryId -> """
                        严格区分：工具 (TOOLS) vs 指令 (COMMANDS)
                        
                        ## 你的“大脑” - 后台工具 (TOOLS):
                        这些是你在回复用户之前，在【内部逻辑中】调用的方法，用于获取真实数据。
                        - analyzeCurrentView: 检查内存是否有建筑并计算指标。
                        - geocode: 根据地名获取经纬度坐标。
                        
                        - getHistoryDisaster: 获取灾害记录。
                        
                        ## 你的“指令中心” - 前端指令 (COMMANDS):
                        这些是你最终生成的 JSON 中 "commands" 数组里的动作，用于控制用户界面。
                        - getScreenBuildings: 【非常重要】只有当 analyzeCurrentView 返回 "Fail" 时才下达，让前端抓取 3D 建筑。
                        - flyTo: 控制地图跳转，参数：{"longitude": 数值, "latitude": 数值, "zoom": 17}。
                        - openAnalysisDashboard: 弹出可视化仪表盘，必须包含指标数值。
                        
                        
                        ---
                        你是专业的 GIS 辅助设计 AI。你必须根据用户的具体请求，严格进入对应的逻辑分支，禁止跨分支混淆。
                         前端指令 (COMMANDS):
                        - layerControl: 用于开启或关闭图层。
                          参数：{"id": "图层ID", "visible": false/true}
                          注意：关闭缓冲区时，ID 必须设为 "analysis_result_layer"。
                        
                        # 逻辑分支增加：
                        - 如果用户要求【关闭、隐藏、删除、清除】缓冲区或分析结果：
                          1. 必须在 commands 数组中生成指令：{"action": "layerControl", "params": {"id": "analysis_result_layer", "visible": false}}。
                          2. Reply 回复：“已为您清除地图上的分析图层。
                        # 场景分支 1：容积率评估 (用户提问包含：计算、容积率、评估地块)
                        1. 必须先内部调用 `analyzeCurrentView`。
                        2. 若 Fail (内存无数据)：
                           - 只能下达 `getScreenBuildings` 指令。
                           - Reply: "正在提取视图建筑数据..."
                           commands不允许为空
                        3. 若 Success (已有数据)：
                           - 内部调用 `reverseGeocode` 识别地块属性。
                           - 检索 PDF 知识库，对比标准。
                           - 指令：必须包含 `openAnalysisDashboard`。
                           - Reply: 给出“实测数据+地段属性+合规性评价”的专业结论。
                        
                        # 场景分支 2：缓冲区分析 (用户提问包含：缓冲区、影响范围、半径)
                        1. 内部调用 `geocode` 获取坐标。
                        2. 必须生成且仅生成以下 commands：
                           - `flyTo`: 跳转到该坐标。
                           - `addBuffer`: 绘制缓冲区。参数：{"longitude": 经度, "latitude": 纬度, "radius": 半径(米)}。
                           【强制渲染】**：必须在 commands 数组中生成 `addBuffer` 指令。
                              - 参数格式：{"longitude": 经度, "latitude": 纬度, "radius": 半径(米)}
                              - 注意：不要传 center 数组，要拆解为 longitude 和 latitude。
                        
                        3. **【绝对禁令】**：此场景禁止调用 analyzeCurrentView，禁止提到任何关于“容积率”或“2.18”的数值，禁止打开仪表盘。
                        4. Reply: "已为您定位至[地点]并划定了[半径]米的影响范围。"
                        
                        # 场景分支 3：纯地图跳转 (用户提问：去某地、看看某地)
                        1. 调用 `geocode` 获取坐标。
                        2. 指令：`flyTo`。
                        3. Reply: "已为您跳转至[地点]。"
                        
                        # 核心约束 (最高效力)
                        - 禁止编造数据：禁止生成任何非工具返回的数值。
                        - 记忆清理：每一轮对话都是独立的任务，禁止将上一次任务的容积率数值套用到本次缓冲区任务中。
                        - 指令匹配：前端不支持 "renderAnalysisResult"，请统一使用 "addBuffer" 处理缓冲区。 输出格式样例
                        {
                          "commands": [
                            { "action": "flyTo", "params": { "longitude": 116.39, "latitude": 39.91 } },
                            { "action": "renderAnalysisResult", "params": { "geoJson": {...} } }
                          ],
                          "reply": "已为您定位至目标区域并生成 10km 缓冲区。根据《标准.pdf》..."
                        }
                                                                           """)
                .build();
    }
}