package com.dameokja.backend.analysis.application;

import static com.dameokja.backend.analysis.application.AnalysisDispatchFixture.LEASED;
import static com.dameokja.backend.analysis.application.AnalysisDispatchFixture.dispatch;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dameokja.backend.analysis.domain.AnalysisCompletion;
import com.dameokja.backend.analysis.domain.AnalysisCompletionFailure;
import com.dameokja.backend.analysis.domain.AnalysisDispatch;
import com.dameokja.backend.analysis.domain.AnalysisFailureCode;
import com.dameokja.backend.analysis.domain.AnalysisDispatchStatus;
import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.domain.AnalysisStatus;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStore;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.support.RedisTestSupport;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(RedisTestSupport.class)
class AnalysisDispatchFailureTest {
    private final Clock clock = Clock.system(BusinessTime.ZONE);
    private final AnalysisJobStore store = new AnalysisJobStore(clock, Duration.ofHours(1), RedisTestSupport.redisTemplate(), JsonMapper.builder().build());
    private final AnalysisDispatchUpdater updater = mock(AnalysisDispatchUpdater.class);
    private final AnalysisCompletionNotifier notifier = mock(AnalysisCompletionNotifier.class);
    private final AnalysisDispatchFailure failure = new AnalysisDispatchFailure(store, updater, notifier, clock);

    @Test
    void showsTheFailureToTheUserBeforeClosingTheDispatchAndThenNotifies() {
        AnalysisDispatch dispatch = dispatchOf(store.create("job-1", 7L, "AUTO", List.of(AnalysisImage.pending("first", "ab".repeat(32)))));
        when(updater.fail(LEASED, AnalysisFailureCode.ANALYSIS_EXPIRED)).thenReturn(true);
        failure.fail(LEASED, dispatch, AnalysisFailureCode.ANALYSIS_EXPIRED);
        AnalysisJob stored = store.findOwned("job-1", 7L);
        AnalysisCompletion completion = stored.analysisImages().getFirst().completion();
        assertThat(completion.status()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(completion.error().code()).isEqualTo("ANALYSIS_EXPIRED");
        assertThat(completion.error().retryable()).isTrue();
        assertThat(stored.expiresAt()).isNotNull();
        InOrder order = inOrder(updater, notifier);
        order.verify(updater).fail(LEASED, AnalysisFailureCode.ANALYSIS_EXPIRED);
        order.verify(notifier).notifyFinished(any(AnalysisJob.class), eq(dispatch.getRequestId()), eq(completion));
    }

    @Test
    void keepsAResultThatArrivedFirstAndOnlyClosesTheDispatch() {
        AnalysisJob job = store.create("job-1", 7L, "AUTO", List.of(AnalysisImage.pending("first", "ab".repeat(32))));
        String requestId = job.analysisImages().getFirst().requestId();
        store.register("job-1", 7L, requestId, "ai-1");
        AnalysisCompletion earlier = new AnalysisCompletion(AnalysisStatus.FAILED, OffsetDateTime.now(clock), null,
                new AnalysisCompletionFailure("MODEL_UNAVAILABLE", "실패", true, null));
        store.complete("ai-1", earlier);
        failure.fail(LEASED, dispatchOf(job), AnalysisFailureCode.ANALYSIS_EXPIRED);
        assertThat(store.findOwned("job-1", 7L).analysisImages().getFirst().completion()).isEqualTo(earlier);
        verify(updater).fail(LEASED, AnalysisFailureCode.ANALYSIS_EXPIRED);
        verify(notifier, never()).notifyFinished(any(), any(), any());
    }

    @Test
    void closesTheDispatchOfAJobThatNoLongerExists() {
        failure.fail(LEASED, dispatchOf(new AnalysisJob("job-1", 7L, "AUTO", List.of(AnalysisImage.pending("first", "ab".repeat(32))),
                OffsetDateTime.now(clock), null, false)), AnalysisFailureCode.ANALYSIS_EXPIRED);
        verify(updater).fail(LEASED, AnalysisFailureCode.ANALYSIS_EXPIRED);
        verifyNoInteractions(notifier);
    }

    private AnalysisDispatch dispatchOf(AnalysisJob job) {
        AnalysisDispatch dispatch = dispatch(AnalysisDispatchStatus.DISPATCHING, 0);
        ReflectionTestUtils.setField(dispatch, "requestId", job.analysisImages().getFirst().requestId());
        return dispatch;
    }
}
