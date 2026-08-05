package org.example.agent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTaskServiceTest {
    @Test
    void completedJobExposesResultAndCanBeRetried() throws Exception {
        AgentTaskService tasks = new AgentTaskService();
        String jobId = tasks.submit("test", "memory", () -> result());

        Map<String, Object> status = waitForTerminal(tasks, jobId);
        assertEquals("succeeded", status.get("status"));
        assertEquals("Success", tasks.result(jobId).outcome().get("status"));

        Map<String, Object> retry = tasks.retry(jobId);
        assertEquals("queued", retry.get("status"));
        assertNotEquals(jobId, retry.get("jobId"));
    }

    @Test
    void queuedOrRunningJobCanBeCancelled() throws Exception {
        AgentTaskService tasks = new AgentTaskService();
        String jobId = tasks.submit("slow", "memory", () -> {
            try { Thread.sleep(5_000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            return result();
        });
        Map<String, Object> cancelled = tasks.cancel(jobId);
        assertTrue(List.of("cancelled", "succeeded").contains(cancelled.get("status")));
    }

    private Map<String, Object> waitForTerminal(AgentTaskService tasks, String jobId) throws Exception {
        for (int attempt = 0; attempt < 30; attempt++) {
            Map<String, Object> status = tasks.status(jobId);
            if (List.of("succeeded", "failed", "cancelled").contains(status.get("status"))) return status;
            Thread.sleep(20);
        }
        return tasks.status(jobId);
    }

    private AgentLoopService.AgentResult result() {
        return new AgentLoopService.AgentResult("ok", List.of(), null, false, Map.of(), List.of(), List.of(), Map.of("status", "Success"));
    }
}
