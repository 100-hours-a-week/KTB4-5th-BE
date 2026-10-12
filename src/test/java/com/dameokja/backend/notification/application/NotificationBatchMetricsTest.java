package com.dameokja.backend.notification.application;

import io.micrometer.core.instrument.MockClock;
import io.micrometer.core.instrument.simple.SimpleConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationBatchMetricsTest {
    private final MockClock clock = new MockClock();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry(SimpleConfig.DEFAULT, clock);
    private final NotificationBatchMetrics metrics = new NotificationBatchMetrics(registry);

    @Test
    void recordsElapsedTimeAndReturnsTheOriginalResult() {
        int created = metrics.measureResult(NotificationBatchMetrics.Stage.JOB_CREATION, () -> {
            clock.add(Duration.ofSeconds(12));
            return 200;
        });

        assertThat(created).isEqualTo(200);
        assertThat(registry.get("notification.batch.stage.duration")
                .tags("stage", "job_creation", "outcome", "completed").timer()
                .totalTime(TimeUnit.SECONDS)).isEqualTo(12);
        assertThat(registry.get("notification.batch.stage.last.duration")
                .tag("stage", "job_creation").gauge().value()).isEqualTo(12);
    }

    @Test
    void recordsFailedStageDurationWithoutSwallowingTheException() {
        IllegalStateException failure = new IllegalStateException("대상 조회 실패");

        assertThatThrownBy(() -> metrics.measure(NotificationBatchMetrics.Stage.TARGET_LOOKUP, () -> {
            clock.add(Duration.ofSeconds(2));
            throw failure;
        })).isSameAs(failure);

        assertThat(registry.get("notification.batch.stage.duration")
                .tags("stage", "target_lookup", "outcome", "error").timer().count()).isEqualTo(1);
        assertThat(registry.get("notification.batch.stage.last.duration")
                .tag("stage", "target_lookup").gauge().value()).isEqualTo(2);
    }

    @Test
    void includesGenerationAndRetryWaitInTheWholeBatchDuration() {
        metrics.measure(NotificationBatchMetrics.Stage.BATCH, () -> {
            metrics.measure(NotificationBatchMetrics.Stage.GENERATION, () -> clock.add(Duration.ofSeconds(20)));
            metrics.measure(NotificationBatchMetrics.Stage.DISPATCH, () -> clock.add(Duration.ofSeconds(285)));
        });

        assertThat(registry.get("notification.batch.stage.last.duration")
                .tag("stage", "batch").gauge().value()).isEqualTo(305);
    }

    @Test
    void countsCompletedFridgesSeparatelyFromFailedFridgesAndCreatedJobs() {
        metrics.recordGeneration(100, 3);
        metrics.recordCreatedJobs(194);

        assertThat(registry.get("notification.batch.refrigerators")
                .tag("result", "completed").counter().count()).isEqualTo(97);
        assertThat(registry.get("notification.batch.refrigerators")
                .tag("result", "failed").counter().count()).isEqualTo(3);
        assertThat(registry.get("notification.batch.push.jobs.created").counter().count()).isEqualTo(194);
    }
}
