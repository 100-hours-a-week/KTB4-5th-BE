package com.dameokja.backend.analysis.infrastructure;

import static com.dameokja.backend.support.RedisTestSupport.redisTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dameokja.backend.analysis.domain.AnalysisCompletion;
import com.dameokja.backend.analysis.domain.AnalysisCompletionFailure;
import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.domain.AnalysisStatus;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.support.RedisTestSupport;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(RedisTestSupport.class)
class AnalysisJobStoreTest {
    private final Clock clock = mock(Clock.class);
    private final Instant instant = Instant.parse("2026-10-07T01:00:00Z");
    private final Duration duration = Duration.ofMinutes(1);

    @Test
    void independentInstancesRegisterDifferentImagesWithoutLosingEitherUpdate() throws Exception {
        AnalysisJobStore first = createAnalysisJobStore();
        AnalysisJobStore second = new AnalysisJobStore(clock, duration, redisTemplate(), JsonMapper.builder().build());
        AnalysisJob job = first.create(7L, "AUTO", List.of(image("first"), image("second")));
        assertThat(job.expiresAt()).isNull();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> one = executor.submit(() -> registerAfterStart(first, start, job, 0, "ai-first"));
            Future<?> two = executor.submit(() -> registerAfterStart(second, start, job, 1, "ai-second"));
            start.countDown();
            one.get(5, TimeUnit.SECONDS);
            two.get(5, TimeUnit.SECONDS);
        }
        assertThat(second.findOwned(job.id(), 7L).analysisImages()).extracting(AnalysisImage::aiAnalysisId).containsExactly("ai-first", "ai-second");
        assertThat(redisTemplate().getExpire("analysis:job:" + job.id(), TimeUnit.MILLISECONDS)).isEqualTo(-1L);
    }

    private void registerAfterStart(AnalysisJobStore store, CountDownLatch start, AnalysisJob job, int imageIndex, String analysisId) {
        try {
            start.await();
            store.register(job.id(), 7L, job.analysisImages().get(imageIndex).requestId(), analysisId);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    @Test
    void recordsAcceptanceByRequestWithoutOverwritingOtherImages() {
        AnalysisJobStore analysisJobStore = createAnalysisJobStore();
        AnalysisJob analysisJob = analysisJobStore.create(7L, "AUTO", List.of(image("first"), image("second")));
        String requestId = analysisJob.analysisImages().getFirst().requestId();
        analysisJobStore.register(analysisJob.id(), 7L, requestId, "ai-first");
        analysisJobStore.register(analysisJob.id(), 7L, requestId, "ai-first");
        assertThat(analysisJobStore.findOwned(analysisJob.id(), 7L).analysisImages()).extracting(AnalysisImage::aiAnalysisId).containsExactly("ai-first", null);
        assertThatThrownBy(() -> analysisJobStore.register(analysisJob.id(), 7L, requestId, "different-ai-id")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> analysisJobStore.register(analysisJob.id(), 7L, "unknown-request", "ai-id")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOtherUsersWithoutChangingTheirJob() {
        AnalysisJobStore analysisJobStore = createAnalysisJobStore();
        AnalysisJob analysisJob = analysisJobStore.create(7L, "AUTO", List.of(image("first")));
        assertThatThrownBy(() -> analysisJobStore.findOwned(analysisJob.id(), 8L)).isInstanceOf(CustomException.class)
                .extracting("exceptionCode.code").isEqualTo("GLOBAL-403-001");
        assertThatThrownBy(() -> analysisJobStore.register(analysisJob.id(), 8L, analysisJob.analysisImages().getFirst().requestId(), "ai-id")).isInstanceOf(CustomException.class);
        assertThat(analysisJobStore.findOwned(analysisJob.id(), 7L).analysisImages().getFirst().aiAnalysisId()).isNull();
    }

    @Test
    void keepsPendingJobsAfterTheFormerCreationDeadline() {
        AnalysisJobStore analysisJobStore = createAnalysisJobStore();
        AnalysisJob job = analysisJobStore.create(7L, "AUTO", List.of(image("first")));
        when(clock.instant()).thenReturn(instant.plus(Duration.ofDays(1)));
        analysisJobStore.register(job.id(), 7L, job.analysisImages().getFirst().requestId(), "ai-id");
        assertThat(analysisJobStore.findOwned(job.id(), 7L).expiresAt()).isNull();
        assertThat(redisTemplate().getExpire("analysis:job:" + job.id())).isEqualTo(-1L);
        assertThat(redisTemplate().getExpire("analysis:ai:ai-id")).isEqualTo(-1L);
    }

    @Test
    void listsOnlyTheUsersJobsNewestFirst() {
        AnalysisJobStore analysisJobStore = createAnalysisJobStore();
        AnalysisJob older = analysisJobStore.create(7L, "AUTO", List.of(image("first")));
        when(clock.instant()).thenReturn(instant.plusSeconds(1));
        AnalysisJob newer = analysisJobStore.create(7L, "AUTO", List.of(image("second")));
        analysisJobStore.create(8L, "AUTO", List.of(image("other")));
        assertThat(analysisJobStore.findAllOwned(7L)).extracting(AnalysisJob::id).containsExactly(newer.id(), older.id());
        assertThat(analysisJobStore.findAllOwned(9L)).isEmpty();
    }

    @Test
    void removesExpiredAndMissingJobsFromTheUserIndex() {
        AnalysisJobStore analysisJobStore = createAnalysisJobStore();
        AnalysisJob missing = analysisJobStore.create(7L, "AUTO", List.of(image("missing")));
        AnalysisJob expired = analysisJobStore.create(7L, "AUTO", List.of(image("expired")));
        AnalysisJob active = analysisJobStore.create(7L, "AUTO", List.of(image("active")));
        redisTemplate().delete("analysis:job:" + missing.id());
        analysisJobStore.register(expired.id(), 7L, expired.analysisImages().getFirst().requestId(), "ai-expired");
        analysisJobStore.complete("ai-expired", new AnalysisCompletion(AnalysisStatus.FAILED, instant.atZone(BusinessTime.ZONE).toOffsetDateTime(),
                null, new AnalysisCompletionFailure("MODEL_UNAVAILABLE", "분석 실패", false, null)));
        when(clock.instant()).thenReturn(instant.plus(duration));
        assertThat(analysisJobStore.findAllOwned(7L)).extracting(AnalysisJob::id).containsExactly(active.id());
        assertThat(redisTemplate().opsForZSet().range("analysis:user:7", 0, -1)).containsExactly(active.id());
    }

    @Test
    void removesDeletedJobFromTheUserIndex() {
        AnalysisJobStore analysisJobStore = createAnalysisJobStore();
        AnalysisJob deleted = analysisJobStore.create(7L, "AUTO", List.of(image("deleted")));
        AnalysisJob kept = analysisJobStore.create(7L, "AUTO", List.of(image("kept")));
        analysisJobStore.delete(deleted.id());
        assertThat(redisTemplate().opsForZSet().range("analysis:user:7", 0, -1)).containsExactly(kept.id());
    }

    private AnalysisJobStore createAnalysisJobStore() {
        when(clock.instant()).thenReturn(instant);
        when(clock.getZone()).thenReturn(BusinessTime.ZONE);
        when(clock.withZone(any())).thenReturn(clock);
        return new AnalysisJobStore(clock, duration, redisTemplate(), JsonMapper.builder().build());
    }

    private AnalysisImage image(String objectKey) { return AnalysisImage.pending(objectKey, "ab".repeat(32)); }
}
