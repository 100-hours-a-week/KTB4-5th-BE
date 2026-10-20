package com.dameokja.backend.analysis.application.job;

import static com.dameokja.backend.support.RedisTestSupport.redisTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dameokja.backend.analysis.application.dispatch.AnalysisDispatchFixture;
import com.dameokja.backend.analysis.domain.AnalysisCompletion;
import com.dameokja.backend.analysis.domain.AnalysisFailureCode;
import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.domain.AnalysisStatus;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStore;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.support.RedisTestSupport;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(RedisTestSupport.class)
class AnalysisOverdueJobExpirerTest {
    private static final Instant SUBMITTED_AT = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    // 전체 기한 1분 + 선점 기간 5초
    private static final Duration OVERDUE_AFTER = Duration.ofSeconds(65);
    private final Clock clock = mock(Clock.class);
    private final AnalysisJobStore analysisJobStore = new AnalysisJobStore(clock, Duration.ofDays(7), redisTemplate(), JsonMapper.builder().build());
    private final AnalysisOverdueJobExpirer analysisOverdueJobExpirer = new AnalysisOverdueJobExpirer(analysisJobStore, AnalysisDispatchFixture.PROPERTIES, clock);

    @BeforeEach
    void setup() {
        now(SUBMITTED_AT);
    }

    @Test
    void keepsAJobInProgressUntilTheWorkerDeadlinePasses() {
        AnalysisJob analysisJob = analysisJobStore.create(7L, "AUTO", List.of(AnalysisImage.pending("first", "ab".repeat(32))));
        now(SUBMITTED_AT.plus(OVERDUE_AFTER).minusMillis(1));

        AnalysisJob checkedJob = analysisOverdueJobExpirer.expireIfOverdue(analysisJob);

        assertThat(checkedJob).isEqualTo(analysisJob);
        assertThat(checkedJob.finished()).isFalse();
    }

    // 전달 행이 없어 워커가 끝내지 못한 작업도 사용자가 결과를 확인하고 지울 수 있어야 한다.
    @Test
    void recordsOnlyUnfinishedImagesAsExpiredOnceTheWorkerDeadlinePasses() {
        AnalysisJob analysisJob = analysisJobStore.create(7L, "AUTO", List.of(AnalysisImage.pending("first", "ab".repeat(32)), AnalysisImage.pending("second", "cd".repeat(32))));
        String firstRequestId = analysisJob.analysisImages().getFirst().requestId();
        AnalysisCompletion earlierFailure = new AnalysisCompletion(AnalysisStatus.FAILED, BusinessTime.now(clock).atZone(BusinessTime.ZONE).toOffsetDateTime(),
                null, AnalysisFailureCode.ANALYSIS_REJECTED.toFailure());
        AnalysisJob partlyFinishedJob = analysisJobStore.fail(analysisJob.id(), firstRequestId, earlierFailure);
        now(SUBMITTED_AT.plus(OVERDUE_AFTER));

        AnalysisJob expiredJob = analysisOverdueJobExpirer.expireIfOverdue(partlyFinishedJob);

        assertThat(expiredJob.finished()).isTrue();
        assertThat(expiredJob.expiresAt()).isNotNull();
        assertThat(expiredJob.analysisImages()).extracting(image -> image.completion().error().code())
                .containsExactly(AnalysisFailureCode.ANALYSIS_REJECTED.name(), AnalysisFailureCode.ANALYSIS_EXPIRED.name());
        assertThat(analysisJobStore.find(analysisJob.id())).contains(expiredJob);
        assertThat(redisTemplate().getExpire("analysis:job:" + analysisJob.id())).isPositive();
    }

    private void now(Instant instant) {
        when(clock.instant()).thenReturn(instant);
        when(clock.withZone(any())).thenReturn(Clock.fixed(instant, BusinessTime.ZONE));
    }
}
