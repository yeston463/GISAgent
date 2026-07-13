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
            "附近", "周边", "位置", "地方", "区域", "地块", "地段"};
        for (String ind : indicators) {
            if (text.contains(ind)) return true;
        }
        return false;
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
            "密度", "类型", "far", "统计", "评估", "规划", "查看", "展示"};
        for (String ind : indicators) {
            if (text.contains(ind)) return true;
        }
        return false;
    }
}
