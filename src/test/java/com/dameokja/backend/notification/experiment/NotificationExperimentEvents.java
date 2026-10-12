package com.dameokja.backend.notification.experiment;

import java.time.Instant;
import java.util.Collection;
import java.util.Queue;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.transaction.support.TransactionSynchronizationManager;

final class NotificationExperimentEvents {
    private final Queue<Long> generationCommits = new ConcurrentLinkedQueue<>();
    // 대량 기록 때 배열 전체 복사 비용이 측정 시간에 섞이지 않도록 한다.
    private final Queue<Long> acceptances = new ConcurrentLinkedQueue<>();
    private final Map<String, AtomicInteger> sendsPerEndpoint = new ConcurrentHashMap<>();
    private long startedNanos;
    private long startedEpochMillis;

    void start() {
        generationCommits.clear();
        acceptances.clear();
        sendsPerEndpoint.clear();
        startedEpochMillis = Instant.now().toEpochMilli();
        startedNanos = System.nanoTime();
    }

    void recordGenerationCommit() {
        // 트랜잭션 프록시가 성공 반환한 뒤에만 기록해 INSERT 시각을 커밋 시각으로 오인하지 않는다.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Generation observer must run outside the committed transaction");
        }
        generationCommits.add(System.nanoTime());
    }

    void recordAcceptance(String endpoint) {
        acceptances.add(System.nanoTime());
        sendsPerEndpoint.computeIfAbsent(endpoint, ignored -> new AtomicInteger()).incrementAndGet();
    }

    Map<String, Object> snapshot() {
        return Map.of(
                "batchStartedEpochMillis", startedEpochMillis,
                "generationTransactionsCommitted", generationCommits.size(),
                "firstNotificationCommitObservedSeconds", firstSeconds(generationCommits),
                "lastNotificationCommitObservedSeconds", lastSeconds(generationCommits),
                "stubAcceptances", acceptances.size(),
                "firstStubAcceptanceSeconds", firstSeconds(acceptances),
                "lastStubAcceptanceSeconds", lastSeconds(acceptances),
                "uniqueDeviceEndpointsAccepted", sendsPerEndpoint.size(),
                "eachDeviceAcceptedExactlyTwo", sendsPerEndpoint.size() == NotificationExperimentFixture.EXPECTED_DEVICES
                        && sendsPerEndpoint.values().stream().allMatch(count -> count.get() == 2));
    }

    private Object firstSeconds(Collection<Long> events) {
        return elapsedSeconds(events.stream().mapToLong(Long::longValue).min());
    }

    private Object lastSeconds(Collection<Long> events) {
        return elapsedSeconds(events.stream().mapToLong(Long::longValue).max());
    }

    private Object elapsedSeconds(OptionalLong timestamp) {
        if (timestamp.isEmpty()) {
            return "N/A";
        }
        return (timestamp.orElseThrow() - startedNanos) / 1_000_000_000.0;
    }
}
