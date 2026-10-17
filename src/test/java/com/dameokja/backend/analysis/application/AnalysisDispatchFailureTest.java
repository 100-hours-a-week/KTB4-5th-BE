package com.dameokja.backend.analysis.application;

import static com.dameokja.backend.analysis.application.AnalysisDispatchFixture.LEASED;
import static com.dameokja.backend.analysis.application.AnalysisDispatchFixture.dispatch;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
import com.dameokja.backend.analysis.exception.AnalysisExceptionCode;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStore;
import com.dameokja.backend.global.exception.CustomException;
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
    private final AnalysisJobStore analysisJobStore = new AnalysisJobStore(clock, Duration.ofHours(1), RedisTestSupport.redisTemplate(), JsonMapper.builder().build());
    private final AnalysisDispatchUpdater analysisDispatchUpdater = mock(AnalysisDispatchUpdater.class);
    private final AnalysisCompletionNotifier analysisCompletionNotifier = mock(AnalysisCompletionNotifier.class);
    private final AnalysisDispatchFailure failure = new AnalysisDispatchFailure(analysisJobStore, analysisDispatchUpdater, analysisCompletionNotifier, clock);

    @Test
    void showsTheFailureToTheUserBeforeClosingTheDispatchAndThenNotifies() {
        AnalysisDispatch analysisDispatch = dispatchOf(analysisJobStore.create("job-1", 7L, "AUTO", List.of(AnalysisImage.pending("first", "ab".repeat(32)))));
        when(analysisDispatchUpdater.fail(LEASED, AnalysisFailureCode.ANALYSIS_EXPIRED)).thenReturn(true);
        failure.fail(LEASED, analysisDispatch, AnalysisFailureCode.ANALYSIS_EXPIRED);
        AnalysisJob stored = analysisJobStore.findOwned("job-1", 7L);
        AnalysisCompletion completion = stored.analysisImages().getFirst().completion();
        assertThat(completion.status()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(completion.error().code()).isEqualTo("ANALYSIS_EXPIRED");
        assertThat(completion.error().retryable()).isTrue();
        assertThat(stored.expiresAt()).isNotNull();
        InOrder order = inOrder(analysisDispatchUpdater, analysisCompletionNotifier);
        order.verify(analysisCompletionNotifier).addPending("job-1");
        order.verify(analysisDispatchUpdater).fail(LEASED, AnalysisFailureCode.ANALYSIS_EXPIRED);
        order.verify(analysisCompletionNotifier).notifyFinished(any(AnalysisJob.class), eq(analysisDispatch.getRequestId()), eq(completion));
    }

    // 알림 대기 목록에 올리지 못하면 전달 행을 닫지 않아, 선점 기한 뒤 다시 처리하며 알림도 다시 올린다.
    @Test
    void keepsTheDispatchOpenWhenTheNotificationCannotBeAddedToPending() {
        AnalysisDispatch analysisDispatch = dispatchOf(analysisJobStore.create("job-1", 7L, "AUTO", List.of(AnalysisImage.pending("first", "ab".repeat(32)))));
        doThrow(new CustomException(AnalysisExceptionCode.CALLBACK_UNAVAILABLE)).when(analysisCompletionNotifier).addPending("job-1");
        assertThatThrownBy(() -> failure.fail(LEASED, analysisDispatch, AnalysisFailureCode.ANALYSIS_EXPIRED)).isInstanceOf(CustomException.class);
        verify(analysisDispatchUpdater, never()).fail(any(LeasedDispatch.class), any(AnalysisFailureCode.class));
    }

    @Test
    void keepsAResultThatArrivedFirstAndOnlyClosesTheDispatch() {
        AnalysisJob analysisJob = analysisJobStore.create("job-1", 7L, "AUTO", List.of(AnalysisImage.pending("first", "ab".repeat(32))));
        String requestId = analysisJob.analysisImages().getFirst().requestId();
        analysisJobStore.register("job-1", 7L, requestId, "ai-1");
        AnalysisCompletion earlier = new AnalysisCompletion(AnalysisStatus.FAILED, OffsetDateTime.now(clock), null,
                new AnalysisCompletionFailure("MODEL_UNAVAILABLE", "실패", true, null));
        analysisJobStore.complete("ai-1", earlier);
        failure.fail(LEASED, dispatchOf(analysisJob), AnalysisFailureCode.ANALYSIS_EXPIRED);
        assertThat(analysisJobStore.findOwned("job-1", 7L).analysisImages().getFirst().completion()).isEqualTo(earlier);
        verify(analysisDispatchUpdater).fail(LEASED, AnalysisFailureCode.ANALYSIS_EXPIRED);
        verify(analysisCompletionNotifier, never()).notifyFinished(any(), any(), any());
    }

    @Test
    void closesTheDispatchOfAJobThatNoLongerExists() {
        failure.fail(LEASED, dispatchOf(new AnalysisJob("job-1", 7L, "AUTO", List.of(AnalysisImage.pending("first", "ab".repeat(32))),
                OffsetDateTime.now(clock), null, false)), AnalysisFailureCode.ANALYSIS_EXPIRED);
        verify(analysisDispatchUpdater).fail(LEASED, AnalysisFailureCode.ANALYSIS_EXPIRED);
        verifyNoInteractions(analysisCompletionNotifier);
    }

    private AnalysisDispatch dispatchOf(AnalysisJob analysisJob) {
        AnalysisDispatch analysisDispatch = dispatch(AnalysisDispatchStatus.DISPATCHING, 0);
        ReflectionTestUtils.setField(analysisDispatch, "requestId", analysisJob.analysisImages().getFirst().requestId());
        return analysisDispatch;
    }
}
