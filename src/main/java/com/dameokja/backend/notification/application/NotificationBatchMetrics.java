package com.dameokja.backend.notification.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class NotificationBatchMetrics {
    private static final String STAGE_DURATION = "notification.batch.stage.duration";
    private final MeterRegistry registry;
    private final EnumMap<Stage, AtomicLong> lastDurations = new EnumMap<>(Stage.class);
    private final Counter completedRefrigerators;
    private final Counter failedRefrigerators;
    private final Counter createdJobs;

    public NotificationBatchMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (Stage stage : Stage.values()) {
            registerStage(stage);
        }
        completedRefrigerators = registry.counter("notification.batch.refrigerators", "result", "completed");
        failedRefrigerators = registry.counter("notification.batch.refrigerators", "result", "failed");
        createdJobs = registry.counter("notification.batch.push.jobs.created");
    }

    public void measure(Stage stage, Runnable action) {
        measureResult(stage, () -> {
            action.run();
            return null;
        });
    }

    public <T> T measureResult(Stage stage, Supplier<T> action) {
        long startedNanos = registry.config().clock().monotonicTime();
        String outcome = "error";
        try {
            T result = action.get();
            outcome = "completed";
            return result;
        } finally {
            long elapsedNanos = registry.config().clock().monotonicTime() - startedNanos;
            registry.timer(STAGE_DURATION, "stage", stage.label, "outcome", outcome)
                    .record(elapsedNanos, TimeUnit.NANOSECONDS);
            lastDurations.get(stage).set(elapsedNanos);
            log.info("event=notification_batch_stage stage={} outcome={} elapsed_ms={}",
                    stage.label, outcome, TimeUnit.NANOSECONDS.toMillis(elapsedNanos));
        }
    }

    public void recordGeneration(int refrigeratorCount, int failedCount) {
        completedRefrigerators.increment(refrigeratorCount - failedCount);
        failedRefrigerators.increment(failedCount);
    }

    public void recordCreatedJobs(int count) {
        createdJobs.increment(count);
    }

    private void registerStage(Stage stage) {
        for (String outcome : new String[]{"completed", "error"}) {
            Timer.builder(STAGE_DURATION).tags("stage", stage.label, "outcome", outcome)
                    .serviceLevelObjectives(Duration.ofMinutes(5)).register(registry);
        }
        AtomicLong lastDuration = new AtomicLong();
        lastDurations.put(stage, lastDuration);
        // 마지막 실행 시간은 다음 scrape까지 지워지지 않아야 짧은 단계도 그래프에 남는다.
        Gauge.builder("notification.batch.stage.last.duration", lastDuration,
                        value -> value.get() / (double) TimeUnit.SECONDS.toNanos(1))
                .tag("stage", stage.label).baseUnit("seconds").register(registry);
    }

    public enum Stage {
        BATCH("batch"),
        TARGET_LOOKUP("target_lookup"),
        GENERATION("generation"),
        JOB_CREATION("job_creation"),
        DISPATCH("dispatch");

        private final String label;

        Stage(String label) {
            this.label = label;
        }
    }
}
