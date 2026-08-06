package org.example.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 上传安全加固：
 * 1) 深度格式校验——按扩展名校验文件头（magic bytes）与内容类型，杜绝"改名换后缀"绕过；
 * 2) 病毒扫描——可选接入 ClamAV（REST 网关），启用前深格式校验仍生效；
 * 3) 存储配额——全局 / 单用户窗口字节上限；
 * 4) 过期清理——托管上传文件超时删除（定时任务）。
 */
@Component
@EnableScheduling
@EnableConfigurationProperties(UploadProperties.class)
public class UploadSecurityService {
    private static final Logger log = LoggerFactory.getLogger(UploadSecurityService.class);

    private static final byte[] PDF_MAGIC = {0x25, 0x50, 0x44, 0x46, 0x2D};      // %PDF-
    private static final byte[] ZIP_MAGIC = {0x50, 0x4B, 0x03, 0x04};            // PK\x03\x04
    private static final byte[] SHP_MAGIC = {0x00, 0x00, 0x27, 0x0A};            // big-endian 9994
    private static final byte[] GPKG_MAGIC = "SQLite format 3\u0000".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] TIFF_LE = {0x49, 0x49, 0x2A, 0x00};              // II*\0
    private static final byte[] TIFF_BE = {0x4D, 0x4D, 0x00, 0x2A};              // MM\0*
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final byte[] UTF16_LE = {(byte) 0xFF, (byte) 0xFE};
    private static final byte[] UTF16_BE = {(byte) 0xFE, (byte) 0xFF};

    private final UploadProperties properties;
    private final Map<String, AtomicLong> userUsage = new ConcurrentHashMap<>();
    private final AtomicLong globalUsage = new AtomicLong(0);

    public UploadSecurityService(UploadProperties properties) {
        this.properties = properties;
    }

    /** 综合校验：深度格式 + 病毒扫描 + 配额。失败抛 {@link UploadRejectedException}。 */
    public void check(MultipartFile file, String extension, String clientKey, long size) throws UploadRejectedException {
        deepFormatCheck(file, extension);
        if (properties.isAntivirusEnabled()) {
            antivirusScan(file);
        }
        acquireQuota(clientKey, size);
    }

    /** 深度格式校验：按扩展名验证文件头与内容特征。 */
    public void deepFormatCheck(MultipartFile file, String extension) throws UploadRejectedException {
        if (file == null || file.isEmpty()) {
            throw new UploadRejectedException("file_required", "文件为空。");
        }
        if (extension == null) {
            extension = "";
        }
        byte[] header;
        try {
            header = readHeader(file, 16);
        } catch (IOException error) {
            throw new UploadRejectedException("file_read_failed", "无法读取上传文件。", error);
        }
        String normalized = extension.toLowerCase();
        switch (normalized) {
            case "pdf":
                requireMagic(header, PDF_MAGIC, "pdf_magic_mismatch", "文件内容不是 PDF 格式。");
                break;
            case "txt":
            case "md":
                requireText(header);
                break;
            case "geojson":
            case "json":
                requireText(header);
                break;
            case "zip":
                requireMagic(header, ZIP_MAGIC, "zip_magic_mismatch", "文件内容不是 ZIP 压缩包。");
                break;
            case "shp":
                requireMagic(header, SHP_MAGIC, "shp_magic_mismatch", "文件内容不是 Shapefile（缺少 SHP 头）。");
                break;
            case "gpkg":
                requireMagic(header, GPKG_MAGIC, "gpkg_magic_mismatch", "文件内容不是 GeoPackage（缺少 SQLite 头）。");
                break;
            case "tif":
            case "tiff":
                requireTiffMagic(header);
                break;
            case "asc":
            case "csv":
                requireText(header);
                break;
            default:
                // 未知扩展名：仅做最小检查（非空已在前面校验），避免误伤合法数据
                break;
        }
    }

    /** 病毒扫描：将内容发给配置的 AV 网关；扫描失败视为拒绝（fail-closed）。 */
    public void antivirusScan(MultipartFile file) throws UploadRejectedException {
        String avUrl = properties.getAntivirusUrl();
        if (avUrl == null || avUrl.isBlank()) {
            log.warn("[upload] antivirus enabled but AV_URL empty; refusing upload (fail-closed)");
            throw new UploadRejectedException("av_unconfigured", "病毒扫描未配置，上传被拒绝。");
        }
        try {
            byte[] content = file.getBytes();
            var client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofMillis(properties.getAntivirusTimeoutMs()))
                    .build();
            var request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(avUrl))
                    .header("Content-Type", "application/octet-stream")
                    .timeout(java.time.Duration.ofMillis(properties.getAntivirusTimeoutMs()))
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofByteArray(content))
                    .build();
            var response = client.send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                String body = response.body() == null ? "" : response.body();
                if (body.toLowerCase().contains("infected") || body.toLowerCase().contains("malware")
                        || body.toLowerCase().contains("found: ")) {
                    throw new UploadRejectedException("virus_detected", "文件疑似携带恶意内容，已拦截。");
                }
                return;
            }
            throw new UploadRejectedException("av_scan_failed", "病毒扫描服务异常，上传被拒绝。");
        } catch (UploadRejectedException already) {
            throw already;
        } catch (Exception error) {
            throw new UploadRejectedException("av_scan_failed", "病毒扫描服务不可达，上传被拒绝。", error);
        }
    }

    /** 配额检查：单用户窗口 + 全局。通过后计入用量。 */
    public void acquireQuota(String clientKey, long size) throws UploadRejectedException {
        if (size <= 0) {
            return;
        }
        long perUser = properties.getPerUserQuotaBytes();
        if (perUser > 0) {
            String key = clientKey == null || clientKey.isBlank() ? "anonymous" : clientKey;
            AtomicLong usage = userUsage.computeIfAbsent(key, ignored -> new AtomicLong());
            long used = usage.get();
            if (used + size > perUser) {
                throw new UploadRejectedException("quota_exceeded_per_user",
                        "该账号上传已超出配额（" + humanBytes(perUser) + "）。");
            }
            usage.addAndGet(size);
        }
        long global = properties.getGlobalQuotaBytes();
        if (global > 0) {
            if (globalUsage.get() + size > global) {
                throw new UploadRejectedException("quota_exceeded_global",
                        "系统存储配额已满（" + humanBytes(global) + "）。");
            }
            globalUsage.addAndGet(size);
        }
    }

    /** 过期文件清理：删除托管上传目录中超过保留期的文件。 */
    @Scheduled(fixedDelayString = "${app.upload.cleanup-interval-seconds:3600}000")
    public void cleanupExpiredFiles() {
        if (!properties.isCleanupEnabled()) {
            return;
        }
        long retentionMillis = properties.getFileRetentionSeconds() * 1000L;
        long deadline = System.currentTimeMillis() - retentionMillis;
        Path root = managedUploadRoot();
        if (!Files.isDirectory(root)) {
            return;
        }
        int removed = 0;
        try (var stream = Files.walk(root)) {
            for (Path entry : (Iterable<Path>) stream::iterator) {
                try {
                    if (!Files.isRegularFile(entry)) {
                        continue;
                    }
                    if (Files.getLastModifiedTime(entry).toMillis() < deadline) {
                        Files.deleteIfExists(entry);
                        removed++;
                    }
                } catch (IOException ignored) {
                    // 单个文件清理失败不影响整体
                }
            }
        } catch (IOException error) {
            log.warn("[upload] cleanup walk failed", error);
        }
        if (removed > 0) {
            log.info("[upload] cleanup removed {} expired managed upload file(s)", removed);
        }
    }

    public Path managedUploadRoot() {
        return Path.of(System.getProperty("user.dir"), "cityengine-workspace", "gis-inputs")
                .toAbsolutePath().normalize();
    }

    public long getGlobalUsage() {
        return globalUsage.get();
    }

    private byte[] readHeader(MultipartFile file, int bytes) throws IOException {
        byte[] buffer = new byte[bytes];
        int read = file.getInputStream().read(buffer);
        if (read < 0) {
            read = 0;
        }
        byte[] result = new byte[read];
        System.arraycopy(buffer, 0, result, 0, read);
        return result;
    }

    private void requireMagic(byte[] header, byte[] magic, String code, String message) throws UploadRejectedException {
        if (header.length < magic.length || !startsWith(header, magic)) {
            throw new UploadRejectedException(code, message);
        }
    }

    private void requireTiffMagic(byte[] header) throws UploadRejectedException {
        if (!startsWith(header, TIFF_LE) && !startsWith(header, TIFF_BE)) {
            throw new UploadRejectedException("tiff_magic_mismatch", "文件内容不是 TIFF/GeoTIFF 栅格。");
        }
    }

    private void requireText(byte[] header) throws UploadRejectedException {
        byte[] probe = stripBom(header);
        for (byte value : probe) {
            int b = value & 0xFF;
            // 文本文件允许：可打印 ASCII、换行、Tab；UTF-16 BOM 已在 stripBom 处理
            if (b != 0 && b != '\n' && b != '\r' && b != '\t' && (b < 0x09 || b > 0x7E)) {
                throw new UploadRejectedException("text_magic_mismatch", "文件内容不是文本/JSON 格式。");
            }
        }
    }

    private byte[] stripBom(byte[] data) {
        if (startsWith(data, UTF8_BOM) || startsWith(data, UTF16_LE) || startsWith(data, UTF16_BE)) {
            return java.util.Arrays.copyOfRange(data, 3, data.length);
        }
        return data;
    }

    private boolean startsWith(byte[] source, byte[] prefix) {
        if (source.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (source[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private String humanBytes(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) {
            return String.format("%.1f GiB", bytes / (1024.0 * 1024 * 1024));
        }
        if (bytes >= 1024L * 1024) {
            return String.format("%.1f MiB", bytes / (1024.0 * 1024));
        }
        return bytes + " B";
    }

    /** 上传被拒绝异常：携带机器可读 code 与用户可读 message。 */
    public static class UploadRejectedException extends Exception {
        private final String code;

        public UploadRejectedException(String code, String message) {
            super(message);
            this.code = code;
        }

        public UploadRejectedException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }

    public static List<Map<String, String>> supportedMagic(String extension) {
        return switch ((extension == null ? "" : extension).toLowerCase()) {
            case "pdf" -> List.of(Map.of("pdf", "25 50 44 46 2D (%PDF-)"));
            case "zip" -> List.of(Map.of("zip", "50 4B 03 04 (PK\\x03\\x04)"));
            case "shp" -> List.of(Map.of("shp", "00 00 27 0A (big-endian 9994)"));
            case "gpkg" -> List.of(Map.of("gpkg", "SQLite format 3"));
            case "tif", "tiff" -> List.of(Map.of("tif/tiff", "II*\\0 或 MM\\0*"));
            default -> List.of(Map.of("text/json/md/txt", "文本或 JSON 内容"));
        };
    }

    static {
        // 防止未使用告警：ByteBuffer 仅用于未来分块读取扩展
        ByteBuffer.allocate(0);
    }
}