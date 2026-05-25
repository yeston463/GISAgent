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
            return """
{
  "status": "success",
  "geoJson": %s
}
""".formatted(geoJson);
        } catch (Exception e) {
            return "计算失败: " + e.getMessage();
        }
    }
    @Tool("getScreenBuildings")
    public Map<String, Object> getScreenBuildings() {
        Map<String, Object> res = new HashMap<>();
        res.put("status", "need_frontend");
        res.put("action", "getScreenBuildings");
        res.put("message", "请等待前端完成几何抓取和上传");
        return res;
    }
    @Tool("analyzeCurrentView")
    public Map<String, Object> analyzeCurrentView() {
        // 1. 从 ContextService 拿到刚才前端上传的 GeoJSON
        String geoJson = contextService.getGeoJson();

        if (geoJson == null || geoJson.isEmpty()) {
            return Map.of("status", "Fail", "message", "内存中无建筑，请先同步数据", "far", 0);
        }

        // 2. 将数据发给 Python 算子（确保是 POST 且带了 body）
        try {
            String result = restTemplate.postForObject(
                    "http://127.0.0.1:8000/analysis/urban_metrics",
                    JSON.parseObject(geoJson), // 发送完整的上下文
                    String.class
            );
            return JSON.parseObject(result);
        } catch (Exception e) {
            return Map.of("status", "Error", "message", "Python引擎响应异常");
        }
    }
    @Tool("calculateUrbanMetrics")
    public Map<String, Object> calculateUrbanMetrics(@P("AOI数据的GeoJSON") String aoiGeoJson) {

        try {
            String rawContext = contextService.getGeoJson();
            if (rawContext == null) {
                return Map.of("status", "error", "message", "无数据");
            }

            JSONObject contextObj = JSON.parseObject(rawContext);

            Map<String, Object> payload = new HashMap<>();
            payload.put("buildings", contextObj.getJSONObject("buildings"));
            payload.put("aoi", contextObj.get("aoi"));

            String result = restTemplate.postForObject(
                    "http://127.0.0.1:8000/analysis/urban_metrics",
                    payload,
                    String.class
            );

            JSONObject json = JSON.parseObject(result);

            // 👇 关键：转成 Map 返回
            return json.getInnerMap();

        } catch (Exception e) {
            return Map.of("status", "error");
        }
    }


    @Tool("formatAnalysisResult")
    public String formatAnalysisResult(String resultData) {
        return resultData;
    }
}