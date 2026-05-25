// GisContextService.java 完整逻辑
package org.example.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.springframework.stereotype.Service;

@Service
public class GisContextService {
    private String latestGeoJson = "{}";

    public void saveGeoJson(String newJson) {
        JSONObject newObj = JSON.parseObject(newJson);
        JSONObject context = JSON.parseObject(this.latestGeoJson);

        // 如果新数据里包含 aoi (说明用户画了新红线)
        if (newObj.containsKey("aoi")) {
            System.out.println("🧹 检测到新红线，强制清空旧建筑缓存");
            context.remove("buildings"); // 必须删掉旧楼，否则数据不准
        }

        context.putAll(newObj);
        this.latestGeoJson = context.toJSONString();
        System.out.println("💾 缓存更新，当前字段: " + context.keySet());
    }

    public String getGeoJson() {
        return latestGeoJson;
    }
}