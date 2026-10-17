package com.dameokja.backend.analysis.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dameokja.backend.analysis.domain.AnalysisCompletion;
import com.dameokja.backend.analysis.domain.AnalysisCompletionFailure;
import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.domain.AnalysisStatus;
import com.dameokja.backend.analysis.exception.AnalysisExceptionCode;
import com.dameokja.backend.analysis.infrastructure.AnalysisCallbackParking;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStore;
import com.dameokja.backend.analysis.infrastructure.AnalysisPendingNotificationStore;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.notification.application.AnalysisFinishedEventService;
import com.dameokja.backend.notification.application.NotificationStreamService;
import com.dameokja.backend.push.application.PushAnalysisCreationService;
import com.dameokja.backend.support.RedisTestSupport;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.json.JsonMapper;

// 콜백 결과를 적용하면서 전달 행을 끝내는지, AI ID 등록 전에 온 콜백은 보관했다가 등록 뒤 적용하는지 확인한다.
@ExtendWith(RedisTestSupport.class)
class AnalysisCallbackDispatchTest {
    private final AnalysisJobStore analysisJobStore = spy(new AnalysisJobStore(Clock.system(BusinessTime.ZONE), Duration.ofHours(1),
            RedisTestSupport.redisTemplate(), JsonMapper.builder().build()));
    private final PushAnalysisCreationService pushes = mock(PushAnalysisCreationService.class);
    private final AnalysisFinishedEventService analysisFinishedEventService = mock(AnalysisFinishedEventService.class);
    private final NotificationStreamService notificationStreamService = mock(NotificationStreamService.class);
    private final AnalysisDispatchUpdater analysisDispatchUpdater = mock(AnalysisDispatchUpdater.class);
    private final AnalysisDispatchWorker analysisDispatchWorker = mock(AnalysisDispatchWorker.class);
    private final AnalysisCallbackParking analysisCallbackParking = new AnalysisCallbackParking(Duration.ofMinutes(10), RedisTestSupport.redisTemplate(),
            JsonMapper.builder().build());
    private final AnalysisPendingNotificationStore analysisPendingNotificationStore = spy(new AnalysisPendingNotificationStore(RedisTestSupport.redisTemplate()));
    private final AnalysisCompletionApplier analysisCompletionApplier = new AnalysisCompletionApplier(analysisJobStore, new AnalysisCompletionNotifier(analysisJobStore,
            analysisPendingNotificationStore, pushes,
            analysisFinishedEventService, notificationStreamService, Clock.system(BusinessTime.ZONE), Duration.ofMillis(1), 3), analysisDispatchUpdater, analysisCallbackParking);
    private final AnalysisCallbackService callbacks = new AnalysisCallbackService(analysisJobStore, new AnalysisCompletionValidator(), analysisCallbackParking, analysisCompletionApplier,
            analysisDispatchWorker);
    private final AnalysisCompletion failure = new AnalysisCompletion(AnalysisStatus.FAILED, OffsetDateTime.parse("2026-10-16T12:00:00+09:00"),
            null, new AnalysisCompletionFailure("MODEL_UNAVAILABLE", "분석 실패", true, null));

    @Test
    void finishesTheDispatchBeforeNotifyingAndWakesTheWorkerForTheNextImage() {
        AnalysisJob analysisJob = registeredJob(1);
        when(notificationStreamService.isConnected(7L)).thenReturn(true);
        callbacks.receive("ai-0", failure);
        InOrder order = inOrder(analysisDispatchUpdater, analysisFinishedEventService, analysisDispatchWorker);
        order.verify(analysisDispatchUpdater).finish(analysisJob.analysisImages().getFirst().requestId());
        order.verify(analysisFinishedEventService).publishImageFinished(7L, analysisJob.id(), "image-0", AnalysisStatus.FAILED, 1, 0, 1);
        order.verify(analysisDispatchWorker).wakeUp();
    }

    @Test
    void keepsTheSavedResultButAsksForRetryWhenTheDispatchCannotBeFinished() {
        AnalysisJob analysisJob = registeredJob(1);
        doThrow(new DataAccessResourceFailureException("mysql")).when(analysisDispatchUpdater).finish(any());
        assertUnavailable(() -> callbacks.receive("ai-0", failure));
        assertThat(analysisJobStore.findOwned(analysisJob.id(), 7L).analysisImages().getFirst().completion()).isEqualTo(failure);
        verifyNoInteractions(analysisFinishedEventService, pushes, analysisDispatchWorker);
        clearInvocations(analysisDispatchUpdater);
        doNothing().when(analysisDispatchUpdater).finish(any());
        callbacks.receive("ai-0", failure);
        verify(analysisDispatchUpdater).finish(analysisJob.analysisImages().getFirst().requestId());
    }

    @Test
    void parksACallbackThatArrivesBeforeRegistrationAndAppliesItOnceTheAiIdIsRegistered() {
        AnalysisJob analysisJob = analysisJobStore.create(7L, "AUTO", List.of(AnalysisImage.pending("image-0", "ab".repeat(32))));
        callbacks.receive("ai-0", failure);
        assertThat(analysisJobStore.findOwned(analysisJob.id(), 7L).analysisImages().getFirst().completion()).isNull();
        assertThat(analysisCallbackParking.find("ai-0")).contains(failure);
        verifyNoInteractions(analysisDispatchUpdater, analysisFinishedEventService, analysisDispatchWorker);

        analysisJobStore.register(analysisJob.id(), 7L, analysisJob.analysisImages().getFirst().requestId(), "ai-0");
        assertThat(analysisCompletionApplier.applyParked("ai-0")).isTrue();
        assertThat(analysisJobStore.findOwned(analysisJob.id(), 7L).analysisImages().getFirst().completion()).isEqualTo(failure);
        verify(analysisDispatchUpdater).finish(analysisJob.analysisImages().getFirst().requestId());
        verify(analysisFinishedEventService).publishImageFinished(7L, analysisJob.id(), "image-0", AnalysisStatus.FAILED, 1, 0, 1);
        assertThat(analysisCallbackParking.find("ai-0")).isEmpty();
        assertThat(analysisCompletionApplier.applyParked("ai-0")).isFalse();
    }

    @Test
    void appliesTheCallbackDirectlyWhenRegistrationLandsWhileItIsBeingParked() {
        AnalysisJob analysisJob = registeredJob(1);
        doReturn(false).doCallRealMethod().when(analysisJobStore).isRegistered("ai-0");
        callbacks.receive("ai-0", failure);
        assertThat(analysisJobStore.findOwned(analysisJob.id(), 7L).analysisImages().getFirst().completion()).isEqualTo(failure);
        verify(analysisDispatchWorker).wakeUp();
    }

    @Test
    void keepsTheParkedCallbackWhenApplyingItFails() {
        AnalysisJob analysisJob = analysisJobStore.create(7L, "AUTO", List.of(AnalysisImage.pending("image-0", "ab".repeat(32))));
        callbacks.receive("ai-0", failure);
        analysisJobStore.register(analysisJob.id(), 7L, analysisJob.analysisImages().getFirst().requestId(), "ai-0");
        doThrow(new DataAccessResourceFailureException("mysql")).when(analysisDispatchUpdater).finish(any());
        assertUnavailable(() -> analysisCompletionApplier.applyParked("ai-0"));
        assertThat(analysisCallbackParking.find("ai-0")).contains(failure);
    }

    @Test
    void asksForRetryWhenTheCallbackCannotBeParked() {
        doThrow(new DataAccessResourceFailureException("redis")).when(analysisJobStore).isRegistered("ai-0");
        assertUnavailable(() -> callbacks.receive("ai-0", failure));
    }

    @Test
    void asksForCallbackRetryOnlyWhenTheNotificationCannotBeScheduled() {
        AnalysisJob analysisJob = registeredJob(1);
        doThrow(new DataAccessResourceFailureException("redis")).when(analysisPendingNotificationStore).schedule(any(), any());
        assertUnavailable(() -> callbacks.receive("ai-0", failure));
        assertThat(analysisJobStore.findOwned(analysisJob.id(), 7L).analysisImages().getFirst().completion()).isEqualTo(failure);
        verifyNoInteractions(analysisFinishedEventService, pushes);
        verify(analysisDispatchUpdater, never()).finish(any());
    }

    private AnalysisJob registeredJob(int analysisImages) {
        List<AnalysisImage> pending = java.util.stream.IntStream.range(0, analysisImages)
                .mapToObj(index -> AnalysisImage.pending("image-" + index, "ab".repeat(32))).toList();
        AnalysisJob analysisJob = analysisJobStore.create(7L, "AUTO", pending);
        for (int index = 0; index < analysisImages; index++) {
            analysisJobStore.register(analysisJob.id(), 7L, pending.get(index).requestId(), "ai-" + index);
        }
        return analysisJob;
    }

    private void assertUnavailable(Runnable receive) {
        assertThatThrownBy(receive::run).isInstanceOf(CustomException.class)
                .extracting("exceptionCode").isEqualTo(AnalysisExceptionCode.CALLBACK_UNAVAILABLE);
    }
}
