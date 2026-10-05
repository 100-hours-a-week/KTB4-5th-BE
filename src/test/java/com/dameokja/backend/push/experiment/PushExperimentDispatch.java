package com.dameokja.backend.push.experiment;

import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.push.application.PushDispatchService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;

@RequiredArgsConstructor
final class PushExperimentDispatch {
    private static final int OBSERVATION_INTERVAL_MS = 100;
    private final PushExperimentConfig config;
    private final PushExperimentData dataset;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final SimulatedPushHttpClient httpClient;
    private final PushDispatchService dispatchService;

    void executeBeforeShutdown(Map<String, Object> report) throws Exception {
        switch (config.mode()) {
            case GENERATION -> sendDueJobs(report);
            case RECOVERY, STARTUP_OBSERVE -> prepareRecoveryStates(report);
            default -> {}
        }
    }

    void executeAfterRestart(Map<String, Object> report) throws Exception {
        report.put("statesAfterRestart", states());
        report.put("httpAttemptsAfterRestart", httpClient.requestCount());
        report.put("dueAfterRestart", dueCount());
        switch (config.mode()) {
            case RECOVERY -> sendDueJobs(report);
            case STARTUP_OBSERVE -> observeWithoutDispatch(report);
            default -> {}
        }
    }

    private void prepareRecoveryStates(Map<String, Object> report) throws Exception {
        PushExperimentMeasurement.measure(report, "recoverySetup", () -> {
            dataset.prepareRecovery();
            return null;
        });
        report.put("statesBeforeRestart", states());
        report.put("httpAttemptsBeforeRestart", httpClient.requestCount());
        report.put("dueBeforeRestart", dueCount());
        LocalDateTime now = businessNow();
        report.put("sendableBeforeRestart", jdbc.queryForObject("""
                SELECT COUNT(*) FROM push_notifications
                WHERE status IN ('PENDING','RETRY') AND next_attempt_at <= ? AND expires_at > ?
                """, Long.class, now, now));
    }

    private void sendDueJobs(Map<String, Object> report) throws Exception {
        Optional<LocalDateTime> next = PushExperimentMeasurement.measure(report, "dispatch", dispatchService::dispatchDueJobs);
        report.put("nextAttemptAt", next.orElse(null));
        report.put("httpAttempts", httpClient.requestCount());
        report.put("dueAfterDispatch", dueCount());
        report.put("finalStates", states());
        report.put("finalRows", dataset.tableCounts());
    }

    private void observeWithoutDispatch(Map<String, Object> report) throws Exception {
        PushExperimentMeasurement.measure(report, "startupObservation", () -> {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.observationWaitMs());
            long remaining;
            while ((remaining = deadline - System.nanoTime()) > 0 && dueCount() > 0) {
                Thread.sleep(Math.min(OBSERVATION_INTERVAL_MS, Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining))));
            }
            return null;
        });
        report.put("statesAfterObservation", states());
        report.put("httpAttemptsAfterObservation", httpClient.requestCount());
        report.put("dueAfterObservation", dueCount());
        report.put("finalRows", dataset.tableCounts());
    }

    private long dueCount() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM push_notifications WHERE status IN ('PENDING','RETRY') AND next_attempt_at <= ?
                """, Long.class, businessNow());
    }

    private List<Map<String, Object>> states() {
        return jdbc.queryForList("""
                SELECT status, attempt_count, next_attempt_at, expires_at, COUNT(*) AS count
                FROM push_notifications GROUP BY status, attempt_count, next_attempt_at, expires_at
                ORDER BY status, attempt_count, next_attempt_at, expires_at
                """);
    }

    private LocalDateTime businessNow() { return BusinessTime.now(clock); }
}
