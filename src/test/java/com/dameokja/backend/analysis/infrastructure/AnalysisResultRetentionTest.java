package com.dameokja.backend.analysis.infrastructure;

import static com.dameokja.backend.support.RedisTestSupport.redisTemplate;
import static org.assertj.core.api.Assertions.assertThat;

import com.dameokja.backend.analysis.domain.AnalysisCompletion;
import com.dameokja.backend.analysis.domain.AnalysisCompletionSuccess;
import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.domain.AnalysisStatus;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.support.RedisTestSupport;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.data.redis.core.script.RedisScript;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(RedisTestSupport.class)
class AnalysisResultRetentionTest {
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private static final Duration RETENTION = Duration.ofDays(7);
    private final AnalysisJobStore store = storeAt(NOW);

    @Test
    void retainsPendingResultsAndStartsOneSharedDeadlineWhenTheLastImageTerminates() {
        AnalysisJob job = storeAt(NOW.minus(Duration.ofDays(1))).create(7L, "AUTO",
                List.of(AnalysisImage.pending("first", "ab".repeat(32)), AnalysisImage.pending("second", "cd".repeat(32))));
        store.register(job.id(), 7L, job.analysisImages().getFirst().requestId(), "first");
        store.register(job.id(), 7L, job.analysisImages().getLast().requestId(), "second");
        AnalysisJob partial = store.complete("first", completed());
        assertThat(partial.expiresAt()).isNull();
        for (String key : keys(job)) {
            assertThat(redisTemplate().getExpire(key)).isEqualTo(-1L);
        }
        AnalysisJob terminal = store.complete("second", completed());
        assertThat(terminal.expiresAt().toInstant()).isEqualTo(NOW.plus(RETENTION));
        RedisScript<Long> deadline = RedisScript.of("return redis.call('PEXPIRETIME', KEYS[1])", Long.class);
        for (String key : keys(job)) {
            assertThat(redisTemplate().execute(deadline, List.of(key))).isEqualTo(terminal.expiresAt().toInstant().toEpochMilli());
        }
    }

    @Test
    void duplicateCallbacksAndRegistrationsDoNotExtendTheResultDeadline() {
        AnalysisJob job = store.create(7L, "AUTO", List.of(AnalysisImage.pending("first", "ab".repeat(32))));
        store.register(job.id(), 7L, job.analysisImages().getFirst().requestId(), "first");
        AnalysisJob terminal = store.complete("first", completed());
        Long ttl = redisTemplate().getExpire("analysis:job:" + job.id(), java.util.concurrent.TimeUnit.MILLISECONDS);
        AnalysisJobStore later = storeAt(NOW.plusSeconds(120));
        assertThat(later.complete("first", completed()).expiresAt()).isEqualTo(terminal.expiresAt());
        later.register(job.id(), 7L, job.analysisImages().getFirst().requestId(), "first");
        assertThat(later.findOwned(job.id(), 7L).expiresAt()).isEqualTo(terminal.expiresAt());
        assertThat(redisTemplate().getExpire("analysis:job:" + job.id(), java.util.concurrent.TimeUnit.MILLISECONDS)).isBetween(1L, ttl);
    }

    private AnalysisJobStore storeAt(Instant instant) {
        return new AnalysisJobStore(Clock.fixed(instant, BusinessTime.ZONE), RETENTION, redisTemplate(), JsonMapper.builder().build());
    }

    private AnalysisCompletion completed() {
        return new AnalysisCompletion(AnalysisStatus.COMPLETED, NOW.minus(Duration.ofHours(3)).atZone(BusinessTime.ZONE).toOffsetDateTime(),
                new AnalysisCompletionSuccess("RECEIPT", List.of()), null);
    }

    private List<String> keys(AnalysisJob job) { return List.of("analysis:job:" + job.id(), "analysis:ai:first", "analysis:ai:second"); }
}
