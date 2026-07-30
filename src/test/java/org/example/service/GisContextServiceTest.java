package org.example.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GisContextServiceTest {

    // Windows can briefly retain a handle after Files.move(..., ATOMIC_MOVE).
    // JUnit cleanup then turns an otherwise successful persistence test into a
    // failure, so Maven's target cleanup owns these small test directories.
    @TempDir(cleanup = CleanupMode.NEVER)
    Path temporaryDirectory;

    @Test
    void refreshKeepsSessionContextAndRejectsStaleWrites() {
        GisContextService service = new GisContextService(temporaryDirectory.resolve("contexts.json"));
        service.restore();

        GisContextService.SaveResult initial = service.saveGeoJson(
                "browser-session", "{\"aoi\":{\"id\":\"aoi-1\"}}", 0);
        assertFalse(initial.conflict());
        assertEquals(1, initial.contextVersion());

        // A page refresh keeps the same session id and writes against the
        // version returned by the server.
        GisContextService.SaveResult refreshed = service.saveGeoJson(
                "browser-session", "{\"buildings\":{\"id\":\"buildings-1\"}}", initial.contextVersion());
        assertFalse(refreshed.conflict());
        assertEquals(2, refreshed.contextVersion());

        GisContextService.SaveResult stale = service.saveGeoJson(
                "browser-session", "{\"aoi\":{\"id\":\"stale\"}}", initial.contextVersion());
        assertTrue(stale.conflict());
        assertEquals(2, stale.contextVersion());
        assertTrue(service.getGeoJson("browser-session").contains("aoi-1"));
    }

    @Test
    void javaRestartRestoresOnlyTheMatchingSession() {
        Path contextFile = temporaryDirectory.resolve("contexts.json");
        GisContextService beforeRestart = new GisContextService(contextFile);
        beforeRestart.restore();
        beforeRestart.saveGeoJson("session-a", "{\"aoi\":{\"id\":\"a\"}}", 0);
        beforeRestart.saveGeoJson("session-b", "{\"aoi\":{\"id\":\"b\"}}", 0);

        GisContextService afterRestart = new GisContextService(contextFile);
        afterRestart.restore();
        assertEquals(1, afterRestart.getContextVersion("session-a"));
        assertTrue(afterRestart.getGeoJson("session-a").contains("\"id\":\"a\""));
        assertTrue(afterRestart.getGeoJson("session-b").contains("\"id\":\"b\""));

        afterRestart.activateSession("missing-session");
        assertEquals("{}", afterRestart.getGeoJson());
        assertEquals(0, afterRestart.getContextVersion("missing-session"));
    }

    @Test
    void agentResultMergeDoesNotInvalidateTheFrontendVersion() {
        GisContextService service = new GisContextService(temporaryDirectory.resolve("contexts.json"));
        service.restore();
        service.saveGeoJson("browser-session", "{\"aoi\":{\"id\":\"aoi-1\"}}", 0);

        service.activateSession("browser-session");
        service.saveGeoJson("{\"buildings\":{\"id\":\"buildings-1\"}}");

        assertEquals(1, service.getContextVersion("browser-session"));
        assertTrue(service.getGeoJson().contains("buildings-1"));
    }
}
