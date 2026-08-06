package org.example.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UploadSecurityServiceTest {

    private UploadProperties propertiesFor(boolean avEnabled, long perUser, long global) {
        UploadProperties properties = new UploadProperties();
        properties.setAntivirusEnabled(avEnabled);
        properties.setPerUserQuotaBytes(perUser);
        properties.setGlobalQuotaBytes(global);
        return properties;
    }

    @Test
    void renamedPdf_thatIsActuallyText_failsMagicCheck() {
        UploadSecurityService service = new UploadSecurityService(propertiesFor(false, -1, -1));
        // 名为 .pdf 但内容是普通文本 → 深格式校验应拒绝
        MockMultipartFile fake = new MockMultipartFile("file", "doc.pdf", "application/pdf",
                "this is just text, not a real pdf".getBytes(StandardCharsets.UTF_8));
        UploadSecurityService.UploadRejectedException error =
                assertThrows(UploadSecurityService.UploadRejectedException.class,
                        () -> service.deepFormatCheck(fake, "pdf"));
        assertEquals("pdf_magic_mismatch", error.getCode());
    }

    @Test
    void validJsonTextPassesJsonAndMdCheck() throws Exception {
        UploadSecurityService service = new UploadSecurityService(propertiesFor(false, -1, -1));
        MockMultipartFile json = new MockMultipartFile("file", "data.geojson", "application/json",
                "{\"type\":\"FeatureCollection\",\"features\":[]}".getBytes(StandardCharsets.UTF_8));
        assertDoesNotThrow(() -> service.deepFormatCheck(json, "geojson"));
        MockMultipartFile md = new MockMultipartFile("file", "notes.md", "text/markdown",
                "# Title\nSome text".getBytes(StandardCharsets.UTF_8));
        assertDoesNotThrow(() -> service.deepFormatCheck(md, "md"));
    }

    @Test
    void renamedJsonWithBinaryContentFails() {
        UploadSecurityService service = new UploadSecurityService(propertiesFor(false, -1, -1));
        MockMultipartFile binary = new MockMultipartFile("file", "data.json", "application/json",
                new byte[]{0x00, 0x01, 0x02, (byte) 0xFF, 0x00, 0x03});
        UploadSecurityService.UploadRejectedException error =
                assertThrows(UploadSecurityService.UploadRejectedException.class,
                        () -> service.deepFormatCheck(binary, "json"));
        assertEquals("text_magic_mismatch", error.getCode());
    }

    @Test
    void perUserQuotaIsEnforced() throws Exception {
        UploadSecurityService service = new UploadSecurityService(propertiesFor(false, 100, -1));
        // 累积 60 字节通过
        assertDoesNotThrow(() -> service.acquireQuota("admin", 60));
        // 再 60 字节超 100 上限
        UploadSecurityService.UploadRejectedException error =
                assertThrows(UploadSecurityService.UploadRejectedException.class,
                        () -> service.acquireQuota("admin", 60));
        assertEquals("quota_exceeded_per_user", error.getCode());
    }

    @Test
    void globalQuotaIsExceeded() throws Exception {
        UploadSecurityService service = new UploadSecurityService(propertiesFor(false, -1, 500));
        assertDoesNotThrow(() -> service.acquireQuota("a", 300));
        assertDoesNotThrow(() -> service.acquireQuota("b", 200));
        UploadSecurityService.UploadRejectedException error =
                assertThrows(UploadSecurityService.UploadRejectedException.class,
                        () -> service.acquireQuota("c", 100));
        assertEquals("quota_exceeded_global", error.getCode());
    }

    @Test
    void avEnabledButUnconfiguredFailsClosed() {
        UploadProperties properties = new UploadProperties();
        properties.setAntivirusEnabled(true);
        properties.setAntivirusUrl("");
        UploadSecurityService service = new UploadSecurityService(properties);
        MockMultipartFile file = new MockMultipartFile("file", "a.txt", "text/plain", "hi".getBytes());
        UploadSecurityService.UploadRejectedException error =
                assertThrows(UploadSecurityService.UploadRejectedException.class,
                        () -> service.check(file, "txt", "admin", 2));
        assertEquals("av_unconfigured", error.getCode());
    }
}