package org.example.agent;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/** Bounded in-process job registry for slow agent requests. */
@Service
public class AgentTaskService {
    private static final int MAX_TASKS = 100;
    private static final int TRIM_THRESHOLD = 80;
    private final ExecutorService executor = Executors.newFixedThreadPool(4, runnable -> {
        Thread thread = new Thread(runnable, "spatial-agent-job");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, Task> tasks = new ConcurrentHashMap<>();

    public synchronized String submit(String message, String memoryId, Supplier<AgentLoopService.AgentResult> work) {
        trim();
        if (tasks.size() >= MAX_TASKS) {
            throw new CapacityExceededException();
        }
        Task task = new Task("job-" + UUID.randomUUID(), message, memoryId, work);
        tasks.put(task.id, task);
        task.future = executor.submit(() -> run(task));
        return task.id;
    }

    public Map<String, Object> status(String jobId) {
        Task task = tasks.get(jobId);
        return task == null ? Map.of("status", "NotFound", "jobId", jobId) : task.snapshot();
    }

    public Map<String, Object> cancel(String jobId) {
        Task task = tasks.get(jobId);
        if (task == null) return Map.of("status", "NotFound", "jobId", jobId);
        Future<?> future = task.future;
        if (future == null || future.isDone()) return task.snapshot();
        boolean cancelled = future.cancel(true);
        if (cancelled) {
            task.status = "cancelled";
            task.finishedAt = Instant.now();
        }
        return task.snapshot();
    }

    public Map<String, Object> retry(String jobId) {
        Task previous = tasks.get(jobId);
        if (previous == null) return Map.of("status", "NotFound", "jobId", jobId);
        if ("queued".equals(previous.status) || "running".equals(previous.status)) return previous.snapshot();
        String replacement = submit(previous.message, previous.memoryId, previous.work);
        return Map.of("status", "queued", "jobId", replacement, "retryOf", jobId);
    }

    public AgentLoopService.AgentResult result(String jobId) {
        Task task = tasks.get(jobId);
        return task == null ? null : task.result;
    }

    private void run(Task task) {
        task.status = "running";
        task.startedAt = Instant.now();
        try {
            AgentLoopService.AgentResult result = task.work.get();
            if (!Thread.currentThread().isInterrupted()) {
                task.result = result;
                task.status = "succeeded";
            }
        } catch (Exception error) {
            task.error = error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
            task.status = "failed";
        } finally {
            if (task.finishedAt == null) task.finishedAt = Instant.now();
        }
    }

    private void trim() {
        if (tasks.size() < TRIM_THRESHOLD) return;
        tasks.entrySet().removeIf(entry -> entry.getValue().finishedAt != null);
    }

    public static final class CapacityExceededException extends IllegalStateException {
        public CapacityExceededException() {
            super("Agent job capacity is exhausted");
        }
    }

    private static final class Task {
        private final String id;
        private final String message;
        private final String memoryId;
        private final Supplier<AgentLoopService.AgentResult> work;
        private volatile String status = "queued";
        private final Instant queuedAt = Instant.now();
        private volatile Instant startedAt;
        private volatile Instant finishedAt;
        private volatile String error;
        private volatile Future<?> future;
        private volatile AgentLoopService.AgentResult result;

        private Task(String id, String message, String memoryId, Supplier<AgentLoopService.AgentResult> work) {
            this.id = id; this.message = message; this.memoryId = memoryId; this.work = work;
        }

        private Map<String, Object> snapshot() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("jobId", id); value.put("status", status); value.put("memoryId", memoryId);
            value.put("queuedAt", queuedAt.toString());
            if (startedAt != null) value.put("startedAt", startedAt.toString());
            if (finishedAt != null) value.put("finishedAt", finishedAt.toString());
            if (error != null) value.put("error", error);
            return value;
        }
    }
}
