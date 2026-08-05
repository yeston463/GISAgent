package org.example.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GisContextServiceConcurrencyTest {

    @TempDir(cleanup = CleanupMode.NEVER)
    Path temporaryDirectory;

    @Test
    void userACannotBePreemptedByUserB() {
        GisContextService service = new GisContextService(temporaryDirectory.resolve("contexts.json"));
        service.restore();

        assertTrue(service.acquireEditLock("session-1", "userA", 30));
        assertFalse(service.acquireEditLock("session-1", "userB", 30));

        Map<String, Object> status = service.getEditStatus("session-1");
        assertTrue((Boolean) status.get("locked"));
        assertEquals("userA", status.get("userId"));
        assertTrue((Long) status.get("remainingTtl") > 0);
    }

    @Test
    void lockExpiresAfterTtlThenBAcquires() throws InterruptedException {
        GisContextService service = new GisContextService(temporaryDirectory.resolve("contexts.json"));
        service.restore();

        assertTrue(service.acquireEditLock("session-1", "userA", 1));
        assertFalse(service.acquireEditLock("session-1", "userB", 30));

        Thread.sleep(1100);

        assertTrue(service.acquireEditLock("session-1", "userB", 30));
        Map<String, Object> status = service.getEditStatus("session-1");
        assertEquals("userB", status.get("userId"));
    }

    @Test
    void versionConflictDetected() {
        GisContextService service = new GisContextService(temporaryDirectory.resolve("contexts.json"));
        service.restore();

        GisContextService.SaveResult first = service.saveGeoJson(
                "session-1", "{\"aoi\":{\"id\":\"v1\"}}", 0);
        assertFalse(first.conflict());
        assertEquals(1, first.contextVersion());

        GisContextService.SaveResult stale = service.updateWithVersion("session-1", "{\"aoi\":{\"id\":\"v2-stale\"}}", 0);
        assertTrue(stale.conflict());
        assertEquals(1, stale.contextVersion());
    }

    @Test
    void releaseLockAllowsOtherUserToEdit() {
        GisContextService service = new GisContextService(temporaryDirectory.resolve("contexts.json"));
        service.restore();

        assertTrue(service.acquireEditLock("session-1", "userA", 30));
        assertFalse(service.acquireEditLock("session-1", "userB", 30));

        assertTrue(service.releaseEditLock("session-1", "userA"));

        Map<String, Object> status = service.getEditStatus("session-1");
        assertFalse((Boolean) status.get("locked"));

        assertTrue(service.acquireEditLock("session-1", "userB", 30));
        assertEquals("userB", service.getEditStatus("session-1").get("userId"));
    }

    @Test
    void releaseByNonOwnerDoesNothing() {
        GisContextService service = new GisContextService(temporaryDirectory.resolve("contexts.json"));
        service.restore();

        assertTrue(service.acquireEditLock("session-1", "userA", 30));
        assertFalse(service.releaseEditLock("session-1", "userB"));

        Map<String, Object> status = service.getEditStatus("session-1");
        assertTrue((Boolean) status.get("locked"));
        assertEquals("userA", status.get("userId"));
    }

    @Test
    void lockEntryRecordHoldsValues() {
        long future = System.currentTimeMillis() + 30000;
        GisContextService.LockEntry entry = new GisContextService.LockEntry("userX", future);
        assertEquals("userX", entry.userId());
        assertEquals(future, entry.expiresAt());
        assertNotNull(entry.toString());
    }

    @Test
    void updateWithVersionIncrementsOnMatch() {
        GisContextService service = new GisContextService(temporaryDirectory.resolve("contexts.json"));
        service.restore();

        service.saveGeoJson("session-1", "{\"aoi\":{\"id\":\"v1\"}}", 0);

        long currentVersion = service.getContextVersion("session-1");
        GisContextService.SaveResult result = service.updateWithVersion(
                "session-1", "{\"buildings\":{\"id\":\"b1\"}}", currentVersion);

        assertFalse(result.conflict());
        assertEquals(currentVersion + 1, result.contextVersion());
        assertTrue(service.getGeoJson("session-1").contains("b1"));
    }
}
