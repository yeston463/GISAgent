package org.example.agent;

import org.springframework.stereotype.Component;

@Component
public class ClarificationEngine {

    public boolean needsClarification(String message) {
        return !detectLocation(message) || !detectAnalysisType(message);
    }

    public String ask(String message) {
        boolean hasLocation = detectLocation(message);
        boolean hasRadius = detectRadius(message);
        boolean hasAnalysisType = detectAnalysisType(message);
        String facilityType = detectFacility(message);

        // 最近设施类请求（找最近的商场/医院/学校等）：
        // 有位置即可直接执行（半径有默认值），无位置则针对性追问，
        // 而不是套用建筑指标的通用澄清文案。
        if (facilityType != null) {
            return hasLocation ? null : "请问在哪个位置附近寻找" + facilityType + "？\n" +
                   "例如：「以清华大学为中心寻找最近的" + facilityType + "」";
        }

        if (!hasLocation && !hasRadius && !hasAnalysisType) {
            return "请问您想分析哪个位置的建筑指标？可以指定位置和分析半径。\n" +
                   "例如：「分析清华大学周边 1km 的建筑指标」";
        }
        if (!hasLocation) {
            return "请问要分析哪个位置？";
        }
        if (!hasRadius && !hasAnalysisType) {
            return "请问分析半径设为多少米？想分析哪些指标（容积率/建筑类型/高度分布）？";
        }
        if (!hasAnalysisType) {
            return "请问想分析哪些指标？例如容积率、建筑密度、高度分布等";
        }
        return null;
    }

    private boolean detectLocation(String msg) {
        if (msg == null || msg.isBlank()) return false;
        String text = msg.toLowerCase();
        String[] indicators = {"大学", "学院", "医院", "公园", "路", "街", "区",
            "清华", "北大", "北京", "上海", "广州", "深圳", "杭州",
            "附近", "周边", "位置", "地方", "区域", "地块", "地段",
            "当前", "这里", "红线", "视图", "aoi"};
        for (String ind : indicators) {
            if (text.contains(ind)) return true;
        }
        return false;
    }

    /** 最近设施请求的设施类型（无则返回 null）。 */
    private String detectFacility(String msg) {
        if (msg == null) return null;
        String[] facilities = {"商场", "超市", "便利店", "医院", "药店", "学校",
            "银行", "加油站", "菜市场", "公园", "地铁站", "公交站", "餐馆", "饭店"};
        for (String facility : facilities) {
            if (msg.contains(facility)) return facility;
        }
        return null;
    }

    private boolean detectRadius(String msg) {
        if (msg == null) return false;
        return msg.matches(".*[0-9]+[米km公里].*")
            || msg.contains("周围") || msg.contains("附近") || msg.contains("周边");
    }

    private boolean detectAnalysisType(String msg) {
        if (msg == null) return false;
        String text = msg.toLowerCase();
        String[] indicators = {"容积率", "建筑", "分析", "指标", "面积", "高度",
            "密度", "类型", "far", "统计", "评估", "规划", "查看", "展示",
            "天际线", "日照", "阴影", "sunlight", "skyline", "shadow", "高级"};
        for (String ind : indicators) {
            if (text.contains(ind)) return true;
        }
        return false;
    }
}
