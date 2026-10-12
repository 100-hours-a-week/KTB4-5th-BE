package com.dameokja.backend.notification.experiment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ConnectionLedgerRecord {
    private ConnectionLedgerRecord() { }

    static Map<String, Object> fields(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            values.put((String) pairs[index], pairs[index + 1]);
        }
        return values;
    }

    static long micros(long nanos) { return nanos / 1_000; }

    static final class Request {
        final long id;
        final String clientId;
        final String uri;
        final long epochMillis = System.currentTimeMillis();
        final long start = System.nanoTime();
        final Thread thread = Thread.currentThread();
        final List<Borrow> borrows = new ArrayList<>();
        final List<Transaction> transactions = new ArrayList<>();
        long end;
        int status;

        Request(long id, String clientId, String uri) {
            this.id = id;
            this.clientId = clientId;
            this.uri = uri;
        }

        Map<String, Object> snapshot() {
            return fields("type", "request", "id", id, "clientId", clientId, "uri", uri,
                    "epochMs", epochMillis, "startNano", start, "durationUs", micros(end - start), "status", status,
                    "threadId", thread.threadId(), "thread", thread.getName(),
                    "borrows", borrows.stream().map(borrow -> borrow.snapshot(start)).toList(),
                    "transactions", transactions.stream().map(tx -> tx.snapshot(start)).toList());
        }
    }

    static final class Borrow {
        final long id;
        final long connectionId;
        final long requested;
        final long acquired;
        final long cpuStart;
        final long txId;
        final Request request;
        final Thread thread = Thread.currentThread();
        final List<Sql> sql = new ArrayList<>();
        final List<long[]> controls = new ArrayList<>();
        volatile String phase = "application";
        volatile long phaseStart;
        volatile long lastDbEnd;
        long closeStart;
        long returned;
        long cpuEnd;
        int sqlCount;

        Borrow(long id, long connectionId, long requested, Request request, long txId, long cpuStart) {
            this.id = id;
            this.connectionId = connectionId;
            this.requested = requested;
            this.acquired = System.nanoTime();
            this.phaseStart = acquired;
            this.lastDbEnd = acquired;
            this.request = request;
            this.txId = txId;
            this.cpuStart = cpuStart;
        }

        Map<String, Object> snapshot(long origin) {
            return fields("id", id, "connectionId", connectionId, "txId", txId,
                    "requestedUs", micros(requested - origin), "acquiredUs", micros(acquired - origin),
                    "closeStartUs", closeStart == 0 ? -1 : micros(closeStart - origin),
                    "returnedUs", returned == 0 ? -1 : micros(returned - origin),
                    "threadCpuUs", micros(cpuEnd - cpuStart), "sqlCount", sqlCount,
                    "sql", sql.stream().map(item -> item.row(origin)).toList(),
                    "controls", controls.stream().map(row -> new long[]{row[0], micros(row[1] - origin), micros(row[2] - origin), row.length > 3 ? row[3] : -1}).toList());
        }
    }

    static final class Sql {
        final int sqlId;
        final long start;
        final long cpuStart;
        long executeEnd;
        long executeCpuEnd;
        long resultClose;
        long lastNextEnd;
        long nextNanos;
        long rows;
        boolean failed;

        Sql(int sqlId, long start, long cpuStart) {
            this.sqlId = sqlId;
            this.start = start;
            this.cpuStart = cpuStart;
        }

        long[] row(long origin) {
            return new long[]{sqlId, micros(start - origin), micros(executeEnd - origin),
                    micros(executeCpuEnd - cpuStart), resultClose == 0 ? -1 : micros(resultClose - origin),
                    lastNextEnd == 0 ? -1 : micros(lastNextEnd - origin), micros(nextNanos), rows, failed ? 1 : 0};
        }
    }

    static final class Transaction {
        final long id;
        final String name;
        final boolean readOnly;
        final long beginBefore = System.nanoTime();
        long beginAfter;
        long completionBefore;
        long completionAfter;
        String outcome = "open";

        Transaction(long id, String name, boolean readOnly) {
            this.id = id;
            this.name = name;
            this.readOnly = readOnly;
        }

        Map<String, Object> snapshot(long origin) {
            return fields("id", id, "name", name, "readOnly", readOnly,
                    "beginBeforeUs", micros(beginBefore - origin),
                    "beginAfterUs", beginAfter == 0 ? -1 : micros(beginAfter - origin),
                    "completionBeforeUs", completionBefore == 0 ? -1 : micros(completionBefore - origin),
                    "completionAfterUs", completionAfter == 0 ? -1 : micros(completionAfter - origin), "outcome", outcome);
        }
    }
}
