package com.dameokja.backend.analysis.infrastructure;

import static com.dameokja.backend.support.RedisTestSupport.redisTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dameokja.backend.analysis.domain.AnalysisCompletion;
import com.dameokja.backend.analysis.domain.AnalysisCompletionFailure;
import com.dameokja.backend.analysis.domain.AnalysisCompletionItem;
import com.dameokja.backend.analysis.domain.AnalysisCompletionSuccess;
import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.domain.AnalysisReviewReason;
import com.dameokja.backend.analysis.domain.AnalysisStatus;
import com.dameokja.backend.analysis.domain.RecognitionStatus;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.support.RedisTestSupport;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.RedisScript;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(RedisTestSupport.class)
class AnalysisCompletionStoreTest {
    private static final Instant NOW = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    private static final Duration TTL = Duration.ofHours(1);
    private final AnalysisJobStore store = newStore(NOW);

    @Test
    void persistsAResultForAnotherInstanceAndStartsItsRetentionDeadline() {
        AnalysisJob job = registeredJob("ai-first");
        assertThat(redisTemplate().getExpire("analysis:job:" + job.id(), TimeUnit.MILLISECONDS)).isEqualTo(-1L);
        AnalysisCompletion result = completed();
        store.complete("ai-first", result);
        String stored = redisTemplate().opsForValue().get("analysis:job:" + job.id());
        assertThat(JsonMapper.builder().build().readTree(stored).at("/analysisImages/0/completion/completedAt").asString())
                .isEqualTo("2026-10-13T10:00:10+09:00");
        AnalysisJob restored = newStore(NOW.plusSeconds(5)).findByAiAnalysisId("ai-first");
        assertThat(restored.userId()).isEqualTo(7L);
        assertThat(restored.analysisImages().getFirst().completion()).isEqualTo(result);
        assertThat(restored.notificationPending()).isTrue();
        assertThat(restored.expiresAt().toInstant()).isEqualTo(NOW.plus(TTL));
        assertThat(redisTemplate().getExpire("analysis:job:" + job.id(), TimeUnit.MILLISECONDS)).isBetween(1L, TTL.toMillis());
        assertThat(redisTemplate().getExpire("analysis:ai:ai-first", TimeUnit.MILLISECONDS)).isBetween(1L, TTL.toMillis());
    }

    @Test
    void repeatsAnIdenticalResultWithoutReplacingItAndRejectsAConflictingResult() {
        AnalysisJob job = registeredJob("ai-first");
        store.complete("ai-first", completed());
        String stored = redisTemplate().opsForValue().get("analysis:job:" + job.id());
        AnalysisCompletion sameInstant = new AnalysisCompletion(AnalysisStatus.COMPLETED, OffsetDateTime.parse("2026-10-13T10:00:10+09:00"), completed().result(), null);
        store.complete("ai-first", sameInstant);
        assertThat(redisTemplate().opsForValue().get("analysis:job:" + job.id())).isEqualTo(stored);
        assertThatThrownBy(() -> store.complete("ai-first", failed())).isInstanceOf(IllegalStateException.class);
        assertThat(redisTemplate().opsForValue().get("analysis:job:" + job.id())).isEqualTo(stored);
    }

    @Test
    void simultaneousCallbacksRetainBothResultsAndOnlyFinishTheWholeGroup() throws Exception {
        AnalysisJob job = store.create(7L, "RECEIPT", List.of(image("first"), image("second")));
        store.register(job.id(), 7L, job.analysisImages().get(0).requestId(), "ai-first");
        store.register(job.id(), 7L, job.analysisImages().get(1).requestId(), "ai-second");
        assertThat(store.findOwned(job.id(), 7L).notificationPending()).isFalse();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<AnalysisJob> first = executor.submit(() -> completeAfterStart(start, "ai-first", completed()));
            Future<AnalysisJob> second = executor.submit(() -> completeAfterStart(start, "ai-second", failed()));
            start.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        }
        AnalysisJob restored = store.findOwned(job.id(), 7L);
        assertThat(restored.analysisImages()).extracting(AnalysisImage::completion).containsExactly(completed(), failed());
        assertThat(restored.notificationPending()).isTrue();
    }

    @Test
    void simultaneousDuplicatesFinishWithoutChangingTheStoredResult() throws Exception {
        AnalysisJob job = registeredJob("ai-first");
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<AnalysisJob> first = executor.submit(() -> completeAfterStart(start, "ai-first", completed()));
            Future<AnalysisJob> second = executor.submit(() -> completeAfterStart(start, "ai-first", completed()));
            start.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(second.get(5, TimeUnit.SECONDS));
        }
        assertThat(store.findOwned(job.id(), 7L).analysisImages().getFirst().completion()).isEqualTo(completed());
    }

    @Test
    void waitsForEveryImageIncludingFailuresAndDoesNotRequeueAnAlreadyHandledNotification() {
        AnalysisJob job = store.create(7L, "RECEIPT", List.of(image("first"), image("second")));
        store.register(job.id(), 7L, job.analysisImages().get(0).requestId(), "ai-first");
        store.register(job.id(), 7L, job.analysisImages().get(1).requestId(), "ai-second");
        assertThat(store.complete("ai-first", failed()).notificationPending()).isFalse();
        AnalysisJob completedJob = store.complete("ai-second", failed());
        assertThat(completedJob.notificationPending()).isTrue();
        AnalysisJob handled = new AnalysisJob(job.id(), job.userId(), job.inputHint(), completedJob.analysisImages(), job.submittedAt(), completedJob.expiresAt(), false);
        redisTemplate().opsForValue().set("analysis:job:" + job.id(), JsonMapper.builder().build().writeValueAsString(handled), TTL);
        assertThat(store.complete("ai-second", failed()).notificationPending()).isFalse();
    }

    @Test
    void preservesNullableFieldsDatesDecimalMeasurementsAndReviewReasons() {
        registeredJob("ai-first");
        AnalysisCompletionItem item = new AnalysisCompletionItem("item-1", RecognitionStatus.NEEDS_REVIEW,
                null, "DAIRY", "REFRIGERATED", "WEIGHT", null, new AnalysisCompletionItem.Weight(new BigDecimal("150.50"), "ML"),
                new AnalysisCompletionItem.Expiration(null, "ESTIMATED_CONSUMPTION_WINDOW",
                        new AnalysisCompletionItem.EstimatedRange(LocalDate.parse("2026-10-14"), LocalDate.parse("2026-10-16"))),
                List.of(new AnalysisReviewReason(AnalysisReviewReason.Field.EXPIRATION, AnalysisReviewReason.Code.EXPIRATION_ESTIMATED,
                        List.of("보관 조건으로 예상했습니다.", "소비기한을 확인해 주세요.")),
                        new AnalysisReviewReason(AnalysisReviewReason.Field.ITEM, AnalysisReviewReason.Code.ITEM_REVIEW_REQUIRED, List.of("상품명을 확인해 주세요."))));
        AnalysisCompletion completion = new AnalysisCompletion(AnalysisStatus.COMPLETED, completed().completedAt(),
                new AnalysisCompletionSuccess("PRODUCT", List.of(item)), null);
        store.complete("ai-first", completion);
        assertThat(newStore(NOW).findByAiAnalysisId("ai-first").analysisImages().getFirst().completion()).isEqualTo(completion);
    }

    @Test
    void preservesTheFirstOwnerWhenAnAiIdentifierIsRegisteredToAnotherRequest() {
        AnalysisJob first = registeredJob("ai-first");
        AnalysisJob second = store.create(8L, "RECEIPT", List.of(image("second")));
        assertThatThrownBy(() -> store.register(second.id(), 8L, second.analysisImages().getFirst().requestId(), "ai-first"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(store.findByAiAnalysisId("ai-first").id()).isEqualTo(first.id());
        assertThat(store.findOwned(second.id(), 8L).analysisImages().getFirst().aiAnalysisId()).isNull();
    }

    @Test
    void refusesUnregisteredAndExpiredResultsWithoutRestoringExpiredJobs() {
        assertThatThrownBy(() -> store.complete("unknown", completed())).isInstanceOf(CustomException.class);
        AnalysisJob job = registeredJob("ai-first");
        store.complete("ai-first", completed());
        assertThatThrownBy(() -> newStore(NOW.plus(TTL)).complete("ai-first", completed())).isInstanceOf(CustomException.class);
        assertThat(store.findOwned(job.id(), 7L).analysisImages().getFirst().completion()).isEqualTo(completed());
        redisTemplate().delete("analysis:job:" + job.id());
        assertThatThrownBy(() -> store.complete("ai-first", completed())).isInstanceOf(CustomException.class);
        assertThat(redisTemplate().hasKey("analysis:job:" + job.id())).isFalse();
    }

    @Test
    void readsExistingJobJsonAndBuildsItsIndexWithoutLosingTheRequest() {
        AnalysisJob job = store.create(7L, "RECEIPT", List.of(image("first")));
        String key = "analysis:job:" + job.id();
        String legacy = redisTemplate().opsForValue().get(key).replace(",\"notificationPending\":false", "").replace(",\"completion\":null", "");
        redisTemplate().opsForValue().set(key, legacy, TTL);
        assertThat(store.findOwned(job.id(), 7L).notificationPending()).isFalse();
        store.register(job.id(), 7L, job.analysisImages().getFirst().requestId(), "ai-first");
        assertThat(store.findByAiAnalysisId("ai-first").analysisImages().getFirst().requestId()).isEqualTo(job.analysisImages().getFirst().requestId());
    }

    @Test
    void doesNotRecreateAJobOrIndexIfTheJobExpiresBetweenReadAndReplacement() {
        AnalysisJob job = store.create(7L, "RECEIPT", List.of(image("first")));
        String key = "analysis:job:" + job.id();
        String previous = redisTemplate().opsForValue().get(key);
        redisTemplate().delete(key);
        RedisScript<Long> replace = RedisScript.of(new ClassPathResource("redis/analysis/replace-analysis-job.lua"), Long.class);
        assertThat(redisTemplate().execute(replace, List.of(key, "analysis:ai:ai-first"), previous, previous, job.id())).isZero();
        assertThat(redisTemplate().hasKey(key)).isFalse();
        assertThat(redisTemplate().hasKey("analysis:ai:ai-first")).isFalse();
    }

    private AnalysisJob completeAfterStart(CountDownLatch start, String analysisId, AnalysisCompletion completion) throws Exception {
        start.await();
        return newStore(NOW).complete(analysisId, completion);
    }

    private AnalysisJob registeredJob(String analysisId) {
        AnalysisJob job = store.create(7L, "RECEIPT", List.of(image("first")));
        store.register(job.id(), 7L, job.analysisImages().getFirst().requestId(), analysisId);
        return store.findOwned(job.id(), 7L);
    }

    private AnalysisJobStore newStore(Instant instant) {
        return new AnalysisJobStore(Clock.fixed(instant, BusinessTime.ZONE), TTL, redisTemplate(), JsonMapper.builder().build());
    }

    private AnalysisImage image(String key) { return AnalysisImage.pending(key, "ab".repeat(32)); }

    private AnalysisCompletion completed() {
        return new AnalysisCompletion(AnalysisStatus.COMPLETED, OffsetDateTime.parse("2026-10-13T01:00:10Z"),
                new AnalysisCompletionSuccess("PRODUCT", List.of()), null);
    }

    private AnalysisCompletion failed() {
        return new AnalysisCompletion(AnalysisStatus.FAILED, OffsetDateTime.parse("2026-10-13T01:00:10Z"), null,
                new AnalysisCompletionFailure("MODEL_UNAVAILABLE", "분석을 수행하지 못했습니다.", true, 1000));
    }
}
