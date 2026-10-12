package com.dameokja.backend.notification.experiment;

import java.io.BufferedWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import tools.jackson.databind.ObjectMapper;

final class ConnectionTraceRecorder implements AutoCloseable {
    static final long SLOW_NANOS = 25_000_000;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ArrayBlockingQueue<Map<String, Object>> queue = new ArrayBlockingQueue<>(20_000);
    private final ConcurrentHashMap<Long, Borrow> owners = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Map<String, Object>> waiters = new ConcurrentHashMap<>();
    private final ThreadLocal<RequestScope> requests = new ThreadLocal<>();
    private final AtomicLong identifiers = new AtomicLong();
    private final AtomicLong lost = new AtomicLong();
    private final BufferedWriter output;
    private final Thread writer;
    private final Thread sampler;
    private volatile boolean running = true;
    private volatile String failure = "";
    private long lastStacks;

    ConnectionTraceRecorder() throws Exception {
        Path directory = Path.of(System.getenv("CONNECTION_TRACE_DIRECTORY"));
        Files.createDirectories(directory);
        output = Files.newBufferedWriter(directory.resolve("connections.jsonl"));
        writer = Thread.ofPlatform().daemon().name("connection-trace-writer").start(this::writeLoop);
        sampler = Thread.ofPlatform().daemon().name("connection-trace-sampler").start(this::sampleLoop);
        emit("trace-config", Map.of("sampleMillis", 50, "slowMillis", 25, "pid", ProcessHandle.current().pid()));
    }

    RequestScope beginRequest(String uri) {
        RequestScope scope = new RequestScope(identifiers.incrementAndGet(), uri, System.nanoTime());
        requests.set(scope);
        return scope;
    }

    void endRequest(RequestScope scope, int status) {
        long duration = System.nanoTime() - scope.start;
        if (duration >= SLOW_NANOS) {
            emit("http", Map.of("requestId", scope.id, "uri", scope.uri, "durationMs", millis(duration),
                    "acquireMs", millis(scope.acquireNanos), "usageMs", millis(scope.usageNanos), "status", status));
        }
        requests.remove();
    }

    long awaiting() {
        long id = identifiers.incrementAndGet();
        RequestScope request = requests.get();
        waiters.put(id, Map.of("thread", Thread.currentThread().getName(), "threadId", Thread.currentThread().threadId(),
                "requestId", request == null ? 0 : request.id, "sinceEpochMs", System.currentTimeMillis()));
        return id;
    }

    Borrow acquired(long id, long started, long connectionId) {
        Map<String, Object> waiting = waiters.remove(id);
        long now = System.nanoTime();
        RequestScope request = requests.get();
        Borrow borrow = new Borrow(id, connectionId, now, waiting, request);
        owners.put(id, borrow);
        long duration = now - started;
        if (request != null) {
            request.acquireNanos += duration;
        }
        if (duration >= SLOW_NANOS) {
            emit("acquire", Map.of("borrowId", id, "connectionId", connectionId, "durationMs", millis(duration), "owner", waiting));
        }
        return borrow;
    }

    void acquisitionFailed(long id, Exception exception) {
        Map<String, Object> waiting = waiters.remove(id);
        emit("acquire-error", Map.of("owner", waiting, "errorType", exception.getClass().getName()));
    }

    void returned(Borrow borrow) {
        owners.remove(borrow.id);
        long duration = System.nanoTime() - borrow.start;
        if (borrow.request != null) {
            borrow.request.usageNanos += duration;
        }
        if (duration >= SLOW_NANOS) {
            emit("usage", Map.of("borrowId", borrow.id, "connectionId", borrow.connectionId,
                    "durationMs", millis(duration), "owner", borrow.owner, "lastOperation", borrow.operation));
        }
    }

    void completedOperation(Borrow borrow, String sql, long start, long cpuStart) {
        long duration = System.nanoTime() - start;
        if (duration >= SLOW_NANOS) {
            emit("sql", Map.of("borrowId", borrow.id, "connectionId", borrow.connectionId, "owner", borrow.owner,
                    "operation", sql, "durationMs", millis(duration), "threadCpuMs", millis(cpuNanos() - cpuStart)));
        }
        borrow.operationActive = false;
    }

    long cpuNanos() {
        return ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
    }

    void emit(String type, Map<String, ?> values) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", type);
        item.put("epochMs", System.currentTimeMillis());
        item.put("instant", Instant.now().toString());
        item.putAll(values);
        if (!queue.offer(item)) {
            lost.incrementAndGet();
        }
    }

    private void sampleLoop() {
        try {
            while (running) {
                List<Map<String, Object>> held = new ArrayList<>();
                boolean captureStacks = !waiters.isEmpty() && System.nanoTime() - lastStacks > 250_000_000;
                for (Borrow borrow : owners.values()) {
                    held.add(snapshot(borrow, captureStacks));
                }
                emit("pool-sample", Map.of("owners", held, "waiters", List.copyOf(waiters.values()), "lostEvents", lost.get()));
                if (captureStacks) {
                    lastStacks = System.nanoTime();
                }
                Thread.sleep(50);
            }
        } catch (Exception exception) {
            failure = exception.toString();
        }
    }

    private Map<String, Object> snapshot(Borrow borrow, boolean captureStacks) {
        Map<String, Object> row = new LinkedHashMap<>(borrow.owner);
        row.put("borrowId", borrow.id);
        row.put("connectionId", borrow.connectionId);
        row.put("usageAgeMs", millis(System.nanoTime() - borrow.start));
        row.put("operation", borrow.operation);
        row.put("operationActive", borrow.operationActive);
        row.put("operationAgeMs", millis(System.nanoTime() - borrow.operationStart));
        if (captureStacks) {
            ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo((long) borrow.owner.get("threadId"), 24);
            if (info != null) {
                row.put("threadState", info.getThreadState().name());
                row.put("stack", java.util.Arrays.stream(info.getStackTrace()).map(Object::toString).toList());
            }
        }
        return row;
    }

    private void writeLoop() {
        try {
            while (running || !queue.isEmpty()) {
                Map<String, Object> row = queue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (row != null) {
                    output.write(mapper.writeValueAsString(row));
                    output.newLine();
                }
                if (queue.isEmpty()) {
                    output.flush();
                }
            }
        } catch (Exception exception) {
            failure = exception.toString();
        }
    }

    @Override
    public void close() throws Exception {
        running = false;
        sampler.join(1000);
        writer.join(5000);
        output.close();
        Path status = Path.of(System.getenv("CONNECTION_TRACE_DIRECTORY"), "trace-finished.json");
        Files.writeString(status, mapper.writeValueAsString(Map.of("lostEvents", lost.get(), "failure", failure,
                "remainingOwners", owners.size(), "remainingWaiters", waiters.size())));
    }

    static double millis(long nanos) { return nanos / 1_000_000.0; }

    static final class RequestScope {
        final long id;
        final String uri;
        final long start;
        long acquireNanos;
        long usageNanos;

        RequestScope(long id, String uri, long start) {
            this.id = id;
            this.uri = uri;
            this.start = start;
        }
    }

    static final class Borrow {
        final long id;
        final long connectionId;
        final long start;
        final Map<String, Object> owner;
        final RequestScope request;
        volatile String operation = "between-sql";
        volatile long operationStart;
        volatile boolean operationActive;

        Borrow(long id, long connectionId, long start, Map<String, Object> owner, RequestScope request) {
            this.id = id;
            this.connectionId = connectionId;
            this.start = start;
            this.owner = owner;
            this.request = request;
        }
    }
}
