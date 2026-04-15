package org.example.tools;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.example.service.GisContextService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import java.util.HashMap;
import java.util.Map;

@Component
public class pyGisTools {

    @Autowired
    private RestTemplate restTemplate;
    @Autowired
    private GisContextService contextService;

    // 请确保你的 FastAPI 运行在此端口
    private final String PYTHON_SERVICE_URL = "http://localhost:8000/analysis";

    @Tool("executeBufferAnalysis")
    public String executeBufferAnalysis(
            @P("中心点经纬度数组 [longitude, latitude]") double[] center, // 👈 改为数组接收
            @P("半径(米)") double radius) {

        System.out.println("🛰️ [Java] 算子激活！收到坐标: " + center[0] + ", " + center[1]);
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("lon", center[0]); // 传给 Python 的还是拆开的
            payload.put("lat", center[1]);
            payload.put("radius", radius);

            String geoJson = restTemplate.postForObject("http://127.0.0.1:8000/analysis/buffer", payload, String.class);

            // 关键：这里返回的 JSON 必须是前端 renderAnalysisResult 能识别的
            return "{\"action\": \"renderAnalysisResult\", \"params\": {\"geoJson\": " + geoJson + "}}";
        } catch (Exception e) {
            return "计算失败: " + e.getMessage();
        }
    }

    @Tool("calculateUrbanMetrics")
    public String calculateUrbanMetrics(@P("AOI数据的GeoJSON") String aoiGeoJson) {
        System.out.println("\n🔥 [核心动作] 启动 Python 空间引擎...");
        try {
            String rawContext = contextService.getGeoJson();
            if (rawContext == null || rawContext.isEmpty()) {
                return "{\"status\":\"error\", \"message\":\"请先执行 getScreenBuildings 抓取数据\"}";
            }

            JSONObject contextObj = JSON.parseObject(rawContext);
            if (!contextObj.containsKey("buildings") || contextObj.getJSONObject("buildings").getJSONArray("features").isEmpty()) {
                System.err.println("⚠️ 警告：AI 试图跳过数据抓取直接计算");
                return "{\"status\":\"error\", \"message\":\"当前内存中没有建筑要素。请先执行 getScreenBuildings 工具。\"}";
            }
            Map<String, Object> payload = new HashMap<>();
            payload.put("buildings", contextObj.getJSONObject("buildings"));
            // 自动补全红线
            Object aoi = (contextObj.get("aoi") != null) ? contextObj.get("aoi") : JSON.parse(aoiGeoJson);
            payload.put("aoi", aoi);

            String result = restTemplate.postForObject(PYTHON_SERVICE_URL + "/urban_metrics", payload, String.class);
            System.out.println("✅ 计算结果已回传: " + result);

            // 👈 核心改进：直接返回 JSON。不要在这里指挥 AI 调下一个工具。
            // AI 会在 SystemMessage 的约束下自动去调用 openAnalysisDashboard。
            return result + "\n[请据此数据立即调用 openAnalysisDashboard 展示可视化面板]";

        } catch (Exception e) {
            return "{\"status\":\"error\", \"message\":\"连接引擎失败\"}";
        }
    }
    @Tool("getScreenBuildings")
    public String getScreenBuildings() {
        // 告知 AI 发起前端动作
        // 注意：这里我们返回一个结构化字符串，引导 AI 使用 commands
        return "{\"action\": \"getScreenBuildings\", \"status\": \"ready\", \"message\": \"请下达 getScreenBuildings 指令给前端以同步数据。\"}";
    }
    @Tool("openAnalysisDashboard")
    public String openAnalysisDashboard(@P("结果数据") String resultData) {
        System.out.println("📊 [指令] 触发图表弹出");
        return "{\"action\": \"openAnalysisDashboard\", \"params\": " + resultData + "}";
    }
}