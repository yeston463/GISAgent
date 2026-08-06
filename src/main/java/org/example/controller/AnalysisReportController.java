package org.example.controller;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.example.service.GisContextService;
import org.example.spatial.AnalysisProvenanceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/reports")
public class AnalysisReportController {
    @Autowired private AnalysisProvenanceService provenance;
    @Autowired private GisContextService contextService;

    @GetMapping(value = "/latest", produces = "text/html;charset=UTF-8")
    public ResponseEntity<byte[]> latest(@RequestParam(defaultValue = "default") String memoryId) {
        Map<String, Object> run = provenance.latestSuccessful(memoryId);
        if (run == null) return ResponseEntity.notFound().build();
        JSONObject context;
        try { context = JSON.parseObject(contextService.getGeoJson(memoryId)); }
        catch (Exception ignored) { context = new JSONObject(); }
        byte[] content = render(run, context).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=analysis-report-" + safe(memoryId) + ".html")
                .contentType(MediaType.parseMediaType("text/html;charset=UTF-8")).body(content);
    }

    private String render(Map<String, Object> run, JSONObject context) {
        JSONObject result = JSON.parseObject(JSON.toJSONString(run.get("result")));
        String title = "flood_analysis".equals(run.get("capabilityId")) ? "洪水分析报告" : "空间分析报告";
        return """
                <!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>%s</title>
                <style>body{font:14px 'Microsoft YaHei',Arial;color:#1f2937;max-width:900px;margin:36px auto;line-height:1.7}h1{color:#075985;border-bottom:2px solid #0ea5e9;padding-bottom:10px}h2{color:#0f766e;margin-top:28px}table{border-collapse:collapse;width:100%%}th,td{border:1px solid #cbd5e1;padding:8px;text-align:left}th{width:32%%;background:#f1f5f9}.note{padding:12px;background:#fff7ed;border-left:4px solid #f59e0b}.muted{color:#64748b;font-size:12px}</style>
                <h1>%s</h1><p class="muted">生成时间：%s　执行 ID：%s</p>
                <h2>分析结论</h2><table><tr><th>能力</th><td>%s</td></tr><tr><th>执行状态</th><td>%s</td></tr><tr><th>高风险格网</th><td>%s</td></tr><tr><th>中风险格网</th><td>%s</td></tr><tr><th>最大筛查水深</th><td>%s m</td></tr><tr><th>受影响建筑</th><td>%s</td></tr></table>
                <h2>输入与数据来源</h2><table><tr><th>降雨情景</th><td>%s</td></tr><tr><th>可用数据</th><td>%s</td></tr><tr><th>上下文版本</th><td>%s</td></tr><tr><th>数据来源</th><td>%s</td></tr><tr><th>GIS 计算后端</th><td>%s</td></tr></table>
                <h2>方法与可追溯性</h2><p>处理链：%s</p><p>工具：%s；计划版本：%s；记录时间：%s。</p>
                <h2>局限与使用说明</h2><p class="note">%s</p><p class="muted">本报告为系统自动生成的分析记录。空间指标优先由专业 ArcPy 后端计算，开源 GeoPandas/Shapely 兜底；GeoScene 用于三维展示/发布；可在浏览器中打开后选择“打印”为 PDF。</p></html>
                """.formatted(title, title, escape(OffsetDateTime.now().toString()), escape(String.valueOf(run.get("runId"))),
                escape(String.valueOf(run.get("capabilityId"))), escape(String.valueOf(result.get("status"))), metric(result,"high_risk_cell_count"), metric(result,"medium_risk_cell_count"), metric(result,"max_estimated_depth_m"), metric(result,"affected_building_count"),
                escape(JSON.toJSONString(context.get("rainfall_scenario"))), escape(String.valueOf(run.get("availableData"))), escape(String.valueOf(run.get("contextVersion"))), escape(String.valueOf(run.get("data_source"))), escape(String.valueOf(run.get("gis_backend"))),
                escape(String.valueOf(run.get("operations"))), escape(String.valueOf(run.get("tool"))), escape(String.valueOf(run.get("planVersion"))), escape(String.valueOf(run.get("recordedAt"))), escape(String.valueOf(run.get("limitations"))));
    }
    private String metric(JSONObject object, String key) { return escape(String.valueOf(object.getOrDefault(key, "—"))); }
    private String safe(String value) { return value.replaceAll("[^A-Za-z0-9_-]", "_"); }
    private String escape(String value) { return value == null ? "—" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
}
