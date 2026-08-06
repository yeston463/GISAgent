package org.example.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 上传安全加固配置（前缀 app.upload，均由环境变量注入）。 */
@ConfigurationProperties(prefix = "app.upload")
public class UploadProperties {

    /** 是否启用 AV 病毒扫描（需配置 ClamAV 网关）。默认关闭，深格式校验仍生效。 */
    private boolean antivirusEnabled = false;

    /** ClamAV 或其它 AV 扫描服务地址（例如 http://127.0.0.1:9444）。 */
    private String antivirusUrl = "";

    /** AV 扫描超时（毫秒）。 */
    private long antivirusTimeoutMs = 4000;

    /** 全局限额：已存储上传文件总字节上限（-1 表示不限制）。 */
    private long globalQuotaBytes = -1;

    /** 单用户在窗口内上传字节上限（-1 表示不限制）。 */
    private long perUserQuotaBytes = -1L;

    /** 配额窗口（秒）。 */
    private long quotaWindowSeconds = 3600;

    /** 托管上传文件（geojson/zip/tif 等落盘文件）过期保留时长（秒），到期由定时任务清理。 */
    private long fileRetentionSeconds = 86400;

    /** 是否启用过期文件定时清理。 */
    private boolean cleanupEnabled = true;

    /** 清理执行间隔（秒）。 */
    private long cleanupIntervalSeconds = 3600;

    public boolean isAntivirusEnabled() {
        return antivirusEnabled;
    }

    public void setAntivirusEnabled(boolean antivirusEnabled) {
        this.antivirusEnabled = antivirusEnabled;
    }

    public String getAntivirusUrl() {
        return antivirusUrl;
    }

    public void setAntivirusUrl(String antivirusUrl) {
        this.antivirusUrl = antivirusUrl == null ? "" : antivirusUrl.trim();
    }

    public long getAntivirusTimeoutMs() {
        return antivirusTimeoutMs;
    }

    public void setAntivirusTimeoutMs(long antivirusTimeoutMs) {
        this.antivirusTimeoutMs = Math.max(500, antivirusTimeoutMs);
    }

    public long getGlobalQuotaBytes() {
        return globalQuotaBytes;
    }

    public void setGlobalQuotaBytes(long globalQuotaBytes) {
        this.globalQuotaBytes = globalQuotaBytes;
    }

    public long getPerUserQuotaBytes() {
        return perUserQuotaBytes;
    }

    public void setPerUserQuotaBytes(long perUserQuotaBytes) {
        this.perUserQuotaBytes = perUserQuotaBytes;
    }

    public long getQuotaWindowSeconds() {
        return quotaWindowSeconds;
    }

    public void setQuotaWindowSeconds(long quotaWindowSeconds) {
        this.quotaWindowSeconds = Math.max(1, quotaWindowSeconds);
    }

    public long getFileRetentionSeconds() {
        return fileRetentionSeconds;
    }

    public void setFileRetentionSeconds(long fileRetentionSeconds) {
        this.fileRetentionSeconds = Math.max(1, fileRetentionSeconds);
    }

    public boolean isCleanupEnabled() {
        return cleanupEnabled;
    }

    public void setCleanupEnabled(boolean cleanupEnabled) {
        this.cleanupEnabled = cleanupEnabled;
    }

    public long getCleanupIntervalSeconds() {
        return cleanupIntervalSeconds;
    }

    public void setCleanupIntervalSeconds(long cleanupIntervalSeconds) {
        this.cleanupIntervalSeconds = Math.max(10, cleanupIntervalSeconds);
    }
}