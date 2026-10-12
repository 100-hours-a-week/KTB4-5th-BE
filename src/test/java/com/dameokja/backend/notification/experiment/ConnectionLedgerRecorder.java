package com.dameokja.backend.notification.experiment;

import static com.dameokja.backend.notification.experiment.ConnectionLedgerRecord.fields;
import static com.dameokja.backend.notification.experiment.ConnectionLedgerRecord.micros;

import java.io.BufferedWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import tools.jackson.databind.ObjectMapper;

final class ConnectionLedgerRecorder implements AutoCloseable {
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ArrayBlockingQueue<Object> queue = new ArrayBlockingQueue<>(8_192);
    private final AtomicLong ids = new AtomicLong();
    private final AtomicLong lost = new AtomicLong();
    private final ConcurrentHashMap<String, Integer> sqlNames = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, ConnectionLedgerRecord.Borrow> owners = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Map<String, Object>> waiters = new ConcurrentHashMap<>();
    private final ThreadLocal<ConnectionLedgerRecord.Request> requests = new ThreadLocal<>();
    private final ThreadLocal<ArrayDeque<ConnectionLedgerRecord.Transaction>> transactions = ThreadLocal.withInitial(ArrayDeque::new);
    private final Path directory;
    private final BufferedWriter output;
    private final Thread writer;
    private final Thread sampler;
    private volatile boolean running = true;
    private volatile String failure = "";

    ConnectionLedgerRecorder() throws Exception {
        directory = Path.of(System.getenv("CONNECTION_LEDGER_DIRECTORY"));
        Files.createDirectories(directory);
        output = Files.newBufferedWriter(directory.resolve("ledger.jsonl"));
        writer = Thread.ofPlatform().daemon().name("connection-ledger-writer").start(this::writeLoop);
        sampler = Thread.ofPlatform().daemon().name("connection-ledger-sampler").start(this::sampleLoop);
        emit(fields("type", "config", "epochMs", System.currentTimeMillis(), "pid", ProcessHandle.current().pid(),
                "sqlRow", "sqlId,startUs,executeEndUs,javaCpuUs,resultCloseUs,lastNextEndUs,nextUs,rows,failed",
                "controlNames", Map.of(1, "commit", 2, "rollback", 3, "setAutoCommit", 4, "setReadOnly", 5, "setTransactionIsolation"),
                "sampleTargetMs", 100, "threadCpuSupported", THREADS.isCurrentThreadCpuTimeSupported()));
    }

    ConnectionLedgerRecord.Request beginRequest(String clientId, String uri) {
        ConnectionLedgerRecord.Request request = new ConnectionLedgerRecord.Request(ids.incrementAndGet(), clientId, uri);
        requests.set(request);
        return request;
    }

    void endRequest(ConnectionLedgerRecord.Request request, int status) {
        request.end = System.nanoTime();
        request.status = status;
        requests.remove();
        emit(request);
    }

    ConnectionLedgerRecord.Request request() { return requests.get(); }

    long awaiting(long nano) {
        long id = ids.incrementAndGet();
        ConnectionLedgerRecord.Request request = requests.get();
        waiters.put(id, fields("requestId", request == null ? 0 : request.id, "threadId", Thread.currentThread().threadId(), "startNano", nano));
        return id;
    }

    void failedAcquisition(long id) { waiters.remove(id); }

    ConnectionLedgerRecord.Borrow acquired(long id, long connectionId, long requested) {
        waiters.remove(id);
        ConnectionLedgerRecord.Request request = requests.get();
        ConnectionLedgerRecord.Transaction tx = transactions.get().peek();
        ConnectionLedgerRecord.Borrow borrow = new ConnectionLedgerRecord.Borrow(id, connectionId, requested, request, tx == null ? 0 : tx.id, cpu());
        owners.put(connectionId, borrow);
        if (request != null) {
            request.borrows.add(borrow);
        }
        return borrow;
    }

    void returned(ConnectionLedgerRecord.Borrow borrow) {
        borrow.returned = System.nanoTime();
        borrow.cpuEnd = cpu();
        owners.remove(borrow.connectionId, borrow);
        if (borrow.request == null && borrow.returned - borrow.acquired >= 25_000_000) {
            emit(fields("type", "background-borrow", "epochMs", System.currentTimeMillis(), "threadId", borrow.thread.threadId(),
                    "thread", borrow.thread.getName(), "borrow", borrow.snapshot(borrow.requested)));
        }
    }

    int sqlId(String text) {
        return sqlNames.computeIfAbsent(text, sql -> {
            // Multiple SQL families can first appear concurrently; use the shared atomic ID.
            int id = Math.toIntExact(ids.incrementAndGet());
            emit(fields("type", "sql-name", "id", id, "sql", sql));
            return id;
        });
    }

    ConnectionLedgerRecord.Transaction beginTransaction(String name, boolean readOnly) {
        if (request() == null) {
            return null;
        }
        ConnectionLedgerRecord.Transaction tx = new ConnectionLedgerRecord.Transaction(ids.incrementAndGet(), name, readOnly);
        transactions.get().push(tx);
        request().transactions.add(tx);
        return tx;
    }

    void finishTransaction(ConnectionLedgerRecord.Transaction tx) {
        transactions.get().remove(tx);
    }

    static long cpu() { return THREADS.getCurrentThreadCpuTime(); }

    private void emit(Object value) {
        if (!queue.offer(value)) {
            lost.incrementAndGet();
        }
    }

    private void sampleLoop() {
        try {
            while (running) {
                ArrayList<Map<String, Object>> held = new ArrayList<>();
                long now = System.nanoTime();
                for (ConnectionLedgerRecord.Borrow borrow : owners.values()) {
                    held.add(fields("connectionId", borrow.connectionId, "borrowId", borrow.id,
                            "requestId", borrow.request == null ? 0 : borrow.request.id, "threadId", borrow.thread.threadId(),
                            "thread", borrow.thread.getName(), "state", borrow.thread.getState().name(), "txId", borrow.txId,
                            "phase", borrow.phase, "phaseAgeUs", micros(now - borrow.phaseStart), "usageAgeUs", micros(now - borrow.acquired)));
                }
                emit(fields("type", "pool", "epochMs", System.currentTimeMillis(), "nano", now,
                        "owners", held, "waiters", new ArrayList<>(waiters.values()), "lostEvents", lost.get(), "queueDepth", queue.size()));
                Thread.sleep(100);
            }
        } catch (Exception exception) {
            failure = exception.toString();
        }
    }

    private void writeLoop() {
        try {
            while (running || !queue.isEmpty()) {
                Object value = queue.poll(100, TimeUnit.MILLISECONDS);
                if (value != null) {
                    Object row = value instanceof ConnectionLedgerRecord.Request request ? request.snapshot() : value;
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
        sampler.join(2_000);
        writer.join(10_000);
        output.close();
        Files.writeString(directory.resolve("ledger-finished.json"), mapper.writeValueAsString(fields("lostEvents", lost.get(),
                "failure", failure, "owners", owners.size(), "waiters", waiters.size(), "queueRemaining", queue.size(), "writerAlive", writer.isAlive())));
    }
}
