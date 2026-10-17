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
    private static final String JOB_ID = "00000000-0000-0000-0000-000000000001";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 13, 8, 0);

    @Autowired AnalysisDispatchLeaseService analysisDispatchLeaseService;
    @Autowired AnalysisDispatchUpdater analysisDispatchUpdater;
    @Autowired AnalysisDispatchQueue analysisDispatchQueue;
    @Autowired AnalysisDispatchProperties analysisDispatchProperties;
    @Autowired JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM analysis_dispatches");
    }

    @Test
    void leasesOnlyAsManyImagesAsThereAreFreeSlots() {
        List<AnalysisImage> analysisImages = enqueue(3);
        List<LeasedDispatch> first = analysisDispatchLeaseService.leaseDue();

        assertThat(first).hasSize(2);
        assertThat(analysisDispatchLeaseService.leaseDue()).isEmpty();
        assertThat(analysisDispatchUpdater.accept(first.getFirst(), "ai-1", false)).isTrue();
        assertThat(analysisDispatchLeaseService.leaseDue()).isEmpty();
        analysisDispatchUpdater.finish(analysisImages.getFirst().requestId(), JOB_ID);
        assertThat(analysisDispatchLeaseService.leaseDue()).hasSize(1);
        assertThat(count("DISPATCHING") + count("AWAITING_CALLBACK")).isEqualTo(2);
    }

    // 게이트 행 잠금이 없으면 두 BE가 같은 시점에 빈 슬롯 2개를 각각 보고 4건을 보낼 수 있다.
    @Test
    void concurrentWorkersNeverExceedTheSlotCount() throws Exception {
        enqueue(10);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<List<LeasedDispatch>>> futures = IntStream.range(0, 8)
                    .mapToObj(index -> CompletableFuture.supplyAsync(analysisDispatchLeaseService::leaseDue, executor)).toList();
            int leasedDispatch = futures.stream().mapToInt(future -> future.orTimeout(10, TimeUnit.SECONDS).join().size()).sum();
            assertThat(leasedDispatch).isEqualTo(2);
        }
        assertThat(count("DISPATCHING")).isEqualTo(2);
    }

    @Test
    void returnsAnUnacceptedExpiredDispatchToTheQueueButChecksAnAcceptedOne() {
        List<AnalysisImage> analysisImages = enqueue(2);
        List<LeasedDispatch> leasedDispatch = analysisDispatchLeaseService.leaseDue();
        analysisDispatchUpdater.accept(leasedDispatch.getLast(), "ai-accepted", false);
        jdbcTemplate.update("UPDATE analysis_dispatches SET lease_until = ?", NOW.minusSeconds(1));

        List<LeasedDispatch> expired = analysisDispatchLeaseService.leaseExpired();

        assertThat(expired).hasSize(2);
        assertThat(statusOf(analysisImages.getFirst())).isEqualTo("PENDING");
        assertThat(statusOf(analysisImages.getLast())).isEqualTo("CHECKING");
        assertThat(analysisDispatchUpdater.extend(expired.getFirst(), "PROCESSING") || analysisDispatchUpdater.extend(expired.getLast(), "PROCESSING")).isTrue();
        assertThat(statusOf(analysisImages.getLast())).isEqualTo("AWAITING_CALLBACK");
    }

    @Test
    void ignoresResultsFromAWorkerWhoseLeaseWasTakenOver() {
        List<AnalysisImage> analysisImages = enqueue(1);
        LeasedDispatch stale = analysisDispatchLeaseService.leaseDue().getFirst();
        jdbcTemplate.update("UPDATE analysis_dispatches SET lease_until = ?", NOW.minusSeconds(1));
        analysisDispatchLeaseService.leaseExpired();
        LeasedDispatch latestJob = analysisDispatchLeaseService.leaseDue().getFirst();

        assertThat(analysisDispatchUpdater.accept(stale, "ai-stale", false)).isFalse();
        assertThat(analysisDispatchUpdater.fail(stale, AnalysisFailureCode.ANALYSIS_EXPIRED)).isFalse();
        assertThat(analysisDispatchUpdater.accept(latestJob, "ai-current", false)).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT ai_analysis_id FROM analysis_dispatches", String.class)).isEqualTo("ai-current");
        analysisDispatchUpdater.finish(analysisImages.getFirst().requestId(), JOB_ID);
        assertThat(analysisDispatchUpdater.extend(latestJob, "PROCESSING")).isFalse();
        assertThat(statusOf(analysisImages.getFirst())).isEqualTo("DONE");
    }

    @Test
    void doesNotResendOrCountTheAttemptWhenAiIsBusy() {
        List<AnalysisImage> analysisImages = enqueue(1);
        LeasedDispatch leasedDispatch = analysisDispatchLeaseService.leaseDue().getFirst();
        analysisDispatchUpdater.retryLater(leasedDispatch, Duration.ofSeconds(5), false, AnalysisDispatchErrorCode.AI_BUSY);

        assertThat(analysisDispatchLeaseService.leaseDue()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT attempt_count FROM analysis_dispatches", Integer.class)).isZero();
        assertThat(statusOf(analysisImages.getFirst())).isEqualTo("PENDING");
    }

    // 같은 사진을 다시 요청한 작업은 같은 requestId로 따로 전달하고, AI가 이미 끝낸 분석이면 바로 확인한다.
    @Test
    void keepsADispatchPerJobForTheSameRequestAndChecksAnAlreadyFinishedOneImmediately() {
        AnalysisImage analysisImage = AnalysisImage.pending("image-0", "ab".repeat(32), "same-request");
        analysisDispatchQueue.enqueue(JOB_ID, 7L, "AUTO", List.of(analysisImage));
        analysisDispatchQueue.enqueue("00000000-0000-0000-0000-000000000002", 7L, "AUTO", List.of(analysisImage));
        List<LeasedDispatch> leasedDispatch = analysisDispatchLeaseService.leaseDue();
        assertThat(leasedDispatch).hasSize(2);

        assertThat(analysisDispatchUpdater.accept(leasedDispatch.getFirst(), "ai-shared", true)).isTrue();
        assertThat(analysisDispatchUpdater.accept(leasedDispatch.getLast(), "ai-shared", false)).isTrue();
        assertThat(jdbcTemplate.queryForList("SELECT lease_until FROM analysis_dispatches ORDER BY analysis_dispatch_id", LocalDateTime.class))
                .containsExactly(NOW, NOW.plus(analysisDispatchProperties.callbackTimeout()));
        analysisDispatchUpdater.finish("same-request", JOB_ID);
        assertThat(jdbcTemplate.queryForList("SELECT status FROM analysis_dispatches ORDER BY analysis_dispatch_id", String.class))
                .containsExactly("DONE", "AWAITING_CALLBACK");
    }

    private List<AnalysisImage> enqueue(int count) {
        List<AnalysisImage> analysisImages = IntStream.range(0, count).mapToObj(index -> AnalysisImage.pending("image-" + index, "ab".repeat(32))).toList();
        analysisDispatchQueue.enqueue(JOB_ID, 7L, "AUTO", analysisImages);
        return analysisImages;
    }

    private int count(String status) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM analysis_dispatches WHERE status = ?", Integer.class, status);
    }

    private String statusOf(AnalysisImage analysisImage) {
        return jdbcTemplate.queryForObject("SELECT status FROM analysis_dispatches WHERE request_id = ?", String.class, analysisImage.requestId());
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
