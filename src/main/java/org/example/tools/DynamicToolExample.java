package org.example.tools;

import com.alibaba.fastjson.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Map;

/**
 * 动态工具示例：展示如何在运行时注册新工具
 */
@Component
public class DynamicToolExample {

    @Autowired
    private DynamicToolRegistry toolRegistry;

    @PostConstruct
    public void registerExampleTools() {
        // 示例1：注册一个简单的计算工具
        toolRegistry.registerDynamicTool(
                "calculateDensity",
                "计算建筑密度 = 建筑面积 / 用地面积",
                params -> {
                    double buildingArea = params.getDoubleValue("buildingArea");
                    double landArea = params.getDoubleValue("landArea");
                    double density = buildingArea / landArea;
                    return Map.of(
                            "density", density,
                            "percentage", String.format("%.2f%%", density * 100),
                            "status", "success"
                    );
                }
        );

        // 示例2：注册一个数据转换工具
        toolRegistry.registerDynamicTool(
                "convertCoordinate",
                "坐标系转换工具",
                params -> {
                    String from = params.getString("from");
                    String to = params.getString("to");
                    double lng = params.getDoubleValue("longitude");
                    double lat = params.getDoubleValue("latitude");
                    
                    // 这里可以调用实际的坐标转换逻辑
                    return Map.of(
                            "longitude", lng,
                            "latitude", lat,
                            "from", from,
                            "to", to,
                            "status", "converted"
                    );
                }
        );

        // 示例3：注册一个复合分析工具
        toolRegistry.registerDynamicTool(
                "feasibilityAnalysis",
                "农田建设可行性分析",
                params -> {
                    double slope = params.getDoubleValue("slope");
                    double area = params.getDoubleValue("area");
                    boolean hasWater = params.getBooleanValue("hasWater");
                    
                    String level;
                    String reason;
                    
                    if (slope > 25) {
                        level = "不适宜";
                        reason = "坡度过大，超过25度";
                    } else if (area < 5) {
                        level = "较困难";
                        reason = "地块面积小于5亩，规模化经营困难";
                    } else if (!hasWater) {
                        level = "需要改善";
                        reason = "缺乏灌溉水源";
                    } else {
                        level = "适宜";
                        reason = "条件良好，适合建设高标准农田";
                    }
                    
                    return Map.of(
                            "feasibilityLevel", level,
                            "reason", reason,
                            "slope", slope,
                            "area", area,
                            "hasWater", hasWater
                    );
                }
        );

        System.out.println("动态工具注册完成，当前工具数量: " + toolRegistry.getToolDescriptions().size());
    }
}
