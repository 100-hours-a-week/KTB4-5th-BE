package com.dameokja.backend.analysis.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.dameokja.backend.analysis.domain.AnalysisFailureCode;
import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.support.MySqlJpaTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Import({AnalysisDispatchLeaseService.class, AnalysisDispatchUpdater.class, AnalysisDispatchQueue.class,
        AnalysisDispatchLeaseIntegrationTest.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AnalysisDispatchLeaseIntegrationTest extends MySqlJpaTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 13, 8, 0);

    @Autowired AnalysisDispatchLeaseService leases;
    @Autowired AnalysisDispatchUpdater updater;
    @Autowired AnalysisDispatchQueue queue;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM analysis_dispatches");
    }

    @Test
    void leasesOnlyAsManyImagesAsThereAreFreeSlots() {
        List<AnalysisImage> images = enqueue(3);
        List<LeasedDispatch> first = leases.leaseDue();

        assertThat(first).hasSize(2);
        assertThat(leases.leaseDue()).isEmpty();
        assertThat(updater.accept(first.getFirst(), "ai-1")).isTrue();
        assertThat(leases.leaseDue()).isEmpty();
        updater.finish(images.getFirst().requestId());
        assertThat(leases.leaseDue()).hasSize(1);
        assertThat(count("DISPATCHING") + count("AWAITING_CALLBACK")).isEqualTo(2);
    }

    // 게이트 행 잠금이 없으면 두 BE가 같은 시점에 빈 슬롯 2개를 각각 보고 4건을 보낼 수 있다.
    @Test
    void concurrentWorkersNeverExceedTheSlotCount() throws Exception {
        enqueue(10);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<List<LeasedDispatch>>> futures = IntStream.range(0, 8)
                    .mapToObj(index -> CompletableFuture.supplyAsync(leases::leaseDue, executor)).toList();
            int leased = futures.stream().mapToInt(future -> future.orTimeout(10, TimeUnit.SECONDS).join().size()).sum();
            assertThat(leased).isEqualTo(2);
        }
        assertThat(count("DISPATCHING")).isEqualTo(2);
    }

    @Test
    void returnsAnUnacceptedExpiredDispatchToTheQueueButChecksAnAcceptedOne() {
        List<AnalysisImage> images = enqueue(2);
        List<LeasedDispatch> leased = leases.leaseDue();
        updater.accept(leased.getLast(), "ai-accepted");
        jdbc.update("UPDATE analysis_dispatches SET lease_until = ?", NOW.minusSeconds(1));

        List<LeasedDispatch> expired = leases.leaseExpired();

        assertThat(expired).hasSize(2);
        assertThat(statusOf(images.getFirst())).isEqualTo("PENDING");
        assertThat(statusOf(images.getLast())).isEqualTo("CHECKING");
        assertThat(updater.extend(expired.getFirst(), "PROCESSING") || updater.extend(expired.getLast(), "PROCESSING")).isTrue();
        assertThat(statusOf(images.getLast())).isEqualTo("AWAITING_CALLBACK");
    }

    @Test
    void ignoresResultsFromAWorkerWhoseLeaseWasTakenOver() {
        List<AnalysisImage> images = enqueue(1);
        LeasedDispatch stale = leases.leaseDue().getFirst();
        jdbc.update("UPDATE analysis_dispatches SET lease_until = ?", NOW.minusSeconds(1));
        leases.leaseExpired();
        LeasedDispatch current = leases.leaseDue().getFirst();

        assertThat(updater.accept(stale, "ai-stale")).isFalse();
        assertThat(updater.fail(stale, AnalysisFailureCode.ANALYSIS_EXPIRED)).isFalse();
        assertThat(updater.accept(current, "ai-current")).isTrue();
        assertThat(jdbc.queryForObject("SELECT ai_analysis_id FROM analysis_dispatches", String.class)).isEqualTo("ai-current");
        updater.finish(images.getFirst().requestId());
        assertThat(updater.extend(current, "PROCESSING")).isFalse();
        assertThat(statusOf(images.getFirst())).isEqualTo("DONE");
    }

    @Test
    void doesNotResendOrCountTheAttemptWhenAiIsBusy() {
        List<AnalysisImage> images = enqueue(1);
        LeasedDispatch leased = leases.leaseDue().getFirst();
        updater.retryLater(leased, Duration.ofSeconds(5), false, AnalysisDispatchErrorCode.AI_BUSY);

        assertThat(leases.leaseDue()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT attempt_count FROM analysis_dispatches", Integer.class)).isZero();
        assertThat(statusOf(images.getFirst())).isEqualTo("PENDING");
    }

    private List<AnalysisImage> enqueue(int count) {
        List<AnalysisImage> images = IntStream.range(0, count).mapToObj(index -> AnalysisImage.pending("image-" + index, "ab".repeat(32))).toList();
        queue.enqueue("00000000-0000-0000-0000-000000000001", 7L, "AUTO", images);
        return images;
    }

    private int count(String status) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM analysis_dispatches WHERE status = ?", Integer.class, status);
    }

    private String statusOf(AnalysisImage image) {
        return jdbc.queryForObject("SELECT status FROM analysis_dispatches WHERE request_id = ?", String.class, image.requestId());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        @Primary
        Clock dispatchClock() {
            // 서울 2026-10-13 08:00
            return Clock.fixed(Instant.parse("2026-10-12T23:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        AnalysisDispatchProperties analysisDispatchProperties() {
            return new AnalysisDispatchProperties(2, Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofMinutes(1),
                    3, Duration.ofSeconds(1), Duration.ofSeconds(30), 20);
        }
    }
}
