package com.dameokja.backend.analysis.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.dameokja.backend.analysis.domain.AnalysisCompletion;
import com.dameokja.backend.analysis.domain.AnalysisCompletionFailure;
import com.dameokja.backend.analysis.domain.AnalysisCompletionSuccess;
import com.dameokja.backend.analysis.domain.AnalysisDocumentType;
import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.domain.AnalysisNotificationChannel;
import com.dameokja.backend.analysis.domain.AnalysisStatus;
import com.dameokja.backend.analysis.exception.AnalysisExceptionCode;
import com.dameokja.backend.analysis.infrastructure.AnalysisCallbackParking;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStore;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(RedisTestSupport.class)
class AnalysisCompletionDeliveryTest {
    private final AnalysisJobStore store = spy(new AnalysisJobStore(Clock.system(BusinessTime.ZONE), Duration.ofHours(1),
            RedisTestSupport.redisTemplate(), JsonMapper.builder().build()));
    private final PushAnalysisCreationService pushes = mock(PushAnalysisCreationService.class);
    private final AnalysisFinishedEventService analysisFinishedEventService = mock(AnalysisFinishedEventService.class);
    private final NotificationStreamService notificationStreamService = mock(NotificationStreamService.class);
    private final AnalysisDispatchUpdater dispatches = mock(AnalysisDispatchUpdater.class);
    private final AnalysisDispatchWorker worker = mock(AnalysisDispatchWorker.class);
    private final AnalysisCallbackParking parking = new AnalysisCallbackParking(Duration.ofMinutes(10), RedisTestSupport.redisTemplate(),
            JsonMapper.builder().build());
    private final AnalysisCompletionApplier applier = new AnalysisCompletionApplier(store,
            new AnalysisCompletionNotifier(store, pushes, analysisFinishedEventService, notificationStreamService), dispatches, parking);
    private final AnalysisCallbackService callbacks = new AnalysisCallbackService(store, new AnalysisCompletionValidator(), parking, applier, worker);
    private final AnalysisCompletion failure = new AnalysisCompletion(AnalysisStatus.FAILED, OffsetDateTime.parse("2026-10-16T12:00:00+09:00"),
            null, new AnalysisCompletionFailure("MODEL_UNAVAILABLE", "분석 실패", true, null));

    @Test
    void publishesImageThenFinalEventWhenTheLastImageCompletesWithMixedResults() {
        AnalysisJob job = registeredJob(2);
        when(notificationStreamService.isConnected(7L)).thenReturn(true);
        AnalysisCompletion success = new AnalysisCompletion(AnalysisStatus.COMPLETED, failure.completedAt(),
                new AnalysisCompletionSuccess(AnalysisDocumentType.PRODUCT, List.of()), null);
        callbacks.receive("ai-0", success);
        verify(analysisFinishedEventService).publishImageFinished(7L, job.id(), "image-0", AnalysisStatus.COMPLETED, 2, 1, 0);
        verifyNoInteractions(notificationStreamService, pushes);
        callbacks.receive("ai-1", failure);
        AnalysisJob stored = store.findOwned(job.id(), 7L);
        InOrder order = inOrder(analysisFinishedEventService);
        order.verify(analysisFinishedEventService).publishImageFinished(7L, job.id(), "image-0", AnalysisStatus.COMPLETED, 2, 1, 0);
        order.verify(analysisFinishedEventService).publishImageFinished(7L, job.id(), "image-1", AnalysisStatus.FAILED, 2, 1, 1);
        order.verify(analysisFinishedEventService).publish(7L, job.id(), AnalysisStatus.PARTIALLY_COMPLETED, 2, 1, 1, stored.expiresAt());
        order.verifyNoMoreInteractions();
        verifyNoInteractions(pushes);
        assertThat(stored.notificationPending()).isFalse();
        assertThat(stored.notificationChannel()).isEqualTo(AnalysisNotificationChannel.SSE);
    }

    @Test
    void duplicateCallbackAfterCompletionRepeatsOnlyTheImageEvent() {
        AnalysisJob job = registeredJob(2);
        when(notificationStreamService.isConnected(7L)).thenReturn(true);
        AnalysisCompletion success = new AnalysisCompletion(AnalysisStatus.COMPLETED, failure.completedAt(),
                new AnalysisCompletionSuccess(AnalysisDocumentType.PRODUCT, List.of()), null);
        callbacks.receive("ai-0", success);
        callbacks.receive("ai-1", failure);
        clearInvocations(analysisFinishedEventService, notificationStreamService, pushes);
        callbacks.receive("ai-0", success);
        verify(analysisFinishedEventService).publishImageFinished(7L, job.id(), "image-0", AnalysisStatus.COMPLETED, 2, 1, 1);
        verifyNoMoreInteractions(analysisFinishedEventService);
        verifyNoInteractions(notificationStreamService, pushes);
    }

    @Test
    void sendsFirstAndMiddleFailuresAsImageFinishedIncludingDuplicateCallbacksWithoutPresenceOrPush() {
        AnalysisJob job = registeredJob(3);
        callbacks.receive("ai-0", failure);
        callbacks.receive("ai-1", failure);
        callbacks.receive("ai-0", failure);
        verify(analysisFinishedEventService).publishImageFinished(7L, job.id(), "image-0", AnalysisStatus.FAILED, 3, 0, 1);
        verify(analysisFinishedEventService).publishImageFinished(7L, job.id(), "image-0", AnalysisStatus.FAILED, 3, 0, 2);
        verify(analysisFinishedEventService).publishImageFinished(7L, job.id(), "image-1", AnalysisStatus.FAILED, 3, 0, 2);
        verifyNoMoreInteractions(analysisFinishedEventService);
        verifyNoInteractions(notificationStreamService, pushes);
        AnalysisJob saved = store.findOwned(job.id(), 7L);
        assertThat(saved.notificationPending()).isFalse();
        assertThat(saved.notificationChannel()).isNull();
        assertThat(saved.expiresAt()).isNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void retriesImageFinishedAfterPublishFailureWithoutLosingTheSavedImageResult(int images) {
        AnalysisJob job = registeredJob(images);
        doThrow(new DataAccessResourceFailureException("pub/sub")).doNothing().when(analysisFinishedEventService).publishImageFinished(any(), any(), any(), any(), anyInt(), anyInt(), anyInt());
        assertUnavailable(() -> callbacks.receive("ai-0", failure));
        assertThat(store.findOwned(job.id(), 7L).analysisImages().getFirst().completion()).isEqualTo(failure);
        verifyNoInteractions(notificationStreamService, pushes);
        callbacks.receive("ai-0", failure);
        verify(analysisFinishedEventService, times(2)).publishImageFinished(7L, job.id(), "image-0", AnalysisStatus.FAILED, images, 0, 1);
        if (images == 1) {
            verify(pushes).create(job.id(), 7L, store.findOwned(job.id(), 7L).expiresAt());
        } else {
            verifyNoInteractions(notificationStreamService, pushes);
        }
    }

    @Test
    void sendsEveryImageEventAndCreatesPushForTheOfflineFinalImage() {
        AnalysisJob job = registeredJob(2);
        callbacks.receive("ai-0", failure);
        callbacks.receive("ai-1", failure);
        callbacks.receive("ai-1", failure);
        verify(analysisFinishedEventService).publishImageFinished(7L, job.id(), "image-0", AnalysisStatus.FAILED, 2, 0, 1);
        verify(analysisFinishedEventService, times(2)).publishImageFinished(7L, job.id(), "image-1", AnalysisStatus.FAILED, 2, 0, 2);
        verifyNoMoreInteractions(analysisFinishedEventService);
        verify(pushes).create(job.id(), 7L, store.findOwned(job.id(), 7L).expiresAt());
        verify(notificationStreamService).isConnected(7L);
    }

    @Test
    void sendsImageFinishedForAnIncompleteLegacyJobEvenIfItAlreadyHasAnExpiry() {
        AnalysisJob job = registeredJob(2);
        AnalysisJob legacy = new AnalysisJob(job.id(), 7L, job.inputHint(), store.findOwned(job.id(), 7L).analysisImages(),
                job.submittedAt(), job.submittedAt().plusHours(1), false);
        RedisTestSupport.redisTemplate().opsForValue().set("analysis:job:" + job.id(), JsonMapper.builder().build().writeValueAsString(legacy));
        callbacks.receive("ai-0", failure);
        verify(analysisFinishedEventService).publishImageFinished(7L, job.id(), "image-0", AnalysisStatus.FAILED, 2, 0, 1);
        verifyNoInteractions(notificationStreamService, pushes);
    }

    @ParameterizedTest
    @CsvSource({"COMPLETED,1,0", "FAILED,0,1"})
    void publishesFinalStatusAndCountsAfterAllImagesEnd(AnalysisStatus status, int completedImageCount, int failedImageCount) {
        AnalysisJob job = registeredJob(1);
        when(notificationStreamService.isConnected(7L)).thenReturn(true);
        AnalysisCompletion completion = status == AnalysisStatus.FAILED ? failure : new AnalysisCompletion(status, failure.completedAt(),
                new AnalysisCompletionSuccess(AnalysisDocumentType.PRODUCT, List.of()), null);
        callbacks.receive("ai-0", completion);
        verify(analysisFinishedEventService).publishImageFinished(7L, job.id(), "image-0", status, 1, completedImageCount, failedImageCount);
        verify(analysisFinishedEventService).publish(7L, job.id(), status, 1, completedImageCount, failedImageCount, store.findOwned(job.id(), 7L).expiresAt());
        verifyNoMoreInteractions(analysisFinishedEventService);
        verifyNoInteractions(pushes);
    }

    @Test
    void retriesSsePublicationWithoutSwitchingToPushAfterDisconnect() {
        AnalysisJob job = registeredJob(1);
        when(notificationStreamService.isConnected(7L)).thenReturn(true);
        doThrow(new DataAccessResourceFailureException("pub/sub")).doNothing().when(analysisFinishedEventService).publish(any(), any(), any(), anyInt(), anyInt(), anyInt(), any());
        assertUnavailable(() -> callbacks.receive("ai-0", failure));
        when(notificationStreamService.isConnected(7L)).thenReturn(false);
        callbacks.receive("ai-0", failure);
        verify(notificationStreamService).isConnected(7L);
        verifyNoInteractions(pushes);
        assertThat(store.findOwned(job.id(), 7L).notificationPending()).isFalse();
    }

    @Test
    void retriesTheSameSseChannelAfterAcknowledgementFails() {
        AnalysisJob job = registeredJob(1);
        when(notificationStreamService.isConnected(7L)).thenReturn(true);
        doThrow(new DataAccessResourceFailureException("ack")).doCallRealMethod().when(store).acknowledgeNotification(job.id());
        assertUnavailable(() -> callbacks.receive("ai-0", failure));
        when(notificationStreamService.isConnected(7L)).thenReturn(false);
        callbacks.receive("ai-0", failure);
        verify(analysisFinishedEventService, times(2)).publish(7L, job.id(), AnalysisStatus.FAILED, 1, 0, 1, store.findOwned(job.id(), 7L).expiresAt());
        verifyNoInteractions(pushes);
    }

    @Test
    void retainsPushSelectionIfTheUserConnectsBeforeRetry() {
        AnalysisJob job = registeredJob(1);
        doThrow(new DataAccessResourceFailureException("database")).doNothing().when(pushes).create(any(), any(), any());
        assertUnavailable(() -> callbacks.receive("ai-0", failure));
        when(notificationStreamService.isConnected(7L)).thenReturn(true);
        callbacks.receive("ai-0", failure);
        verify(notificationStreamService).isConnected(7L);
        verify(analysisFinishedEventService, times(2)).publishImageFinished(7L, job.id(), "image-0", AnalysisStatus.FAILED, 1, 0, 1);
        verifyNoMoreInteractions(analysisFinishedEventService);
        assertThat(store.findOwned(job.id(), 7L).notificationChannel()).isEqualTo(AnalysisNotificationChannel.PUSH);
    }

    @Test
    void keepsTheResultPendingWhenPresenceLookupFailsInsteadOfCreatingPush() {
        AnalysisJob job = registeredJob(1);
        when(notificationStreamService.isConnected(7L)).thenThrow(new DataAccessResourceFailureException("redis"));
        assertUnavailable(() -> callbacks.receive("ai-0", failure));
        AnalysisJob saved = store.findOwned(job.id(), 7L);
        assertThat(saved.analysisImages().getFirst().completion()).isEqualTo(failure);
        assertThat(saved.notificationChannel()).isNull();
        assertThat(saved.notificationPending()).isTrue();
        verify(analysisFinishedEventService).publishImageFinished(7L, job.id(), "image-0", AnalysisStatus.FAILED, 1, 0, 1);
        verifyNoMoreInteractions(analysisFinishedEventService);
        verifyNoInteractions(pushes);
    }

    @Test
    void doesNotPublishFinalBeforeAChannelIsSafelyStored() {
        AnalysisJob job = registeredJob(1);
        when(notificationStreamService.isConnected(7L)).thenReturn(true);
        doThrow(new DataAccessResourceFailureException("selection")).when(store).selectNotificationChannel(job.id(), AnalysisNotificationChannel.SSE);
        assertUnavailable(() -> callbacks.receive("ai-0", failure));
        verify(analysisFinishedEventService).publishImageFinished(7L, job.id(), "image-0", AnalysisStatus.FAILED, 1, 0, 1);
        verifyNoMoreInteractions(analysisFinishedEventService);
        verifyNoInteractions(pushes);
        verify(store, never()).acknowledgeNotification(job.id());
    }

    @Test
    void finishesTheDispatchBeforeNotifyingAndWakesTheWorkerForTheNextImage() {
        AnalysisJob job = registeredJob(1);
        when(notificationStreamService.isConnected(7L)).thenReturn(true);
        callbacks.receive("ai-0", failure);
        InOrder order = inOrder(dispatches, analysisFinishedEventService, worker);
        order.verify(dispatches).finish(job.analysisImages().getFirst().requestId());
        order.verify(analysisFinishedEventService).publishImageFinished(7L, job.id(), "image-0", AnalysisStatus.FAILED, 1, 0, 1);
        order.verify(worker).wakeUp();
    }

    @Test
    void keepsTheSavedResultButAsksForRetryWhenTheDispatchCannotBeFinished() {
        AnalysisJob job = registeredJob(1);
        doThrow(new DataAccessResourceFailureException("mysql")).when(dispatches).finish(any());
        assertUnavailable(() -> callbacks.receive("ai-0", failure));
        assertThat(store.findOwned(job.id(), 7L).analysisImages().getFirst().completion()).isEqualTo(failure);
        verifyNoInteractions(analysisFinishedEventService, pushes, worker);
        clearInvocations(dispatches);
        doNothing().when(dispatches).finish(any());
        callbacks.receive("ai-0", failure);
        verify(dispatches).finish(job.analysisImages().getFirst().requestId());
    }

    @Test
    void parksACallbackThatArrivesBeforeRegistrationAndAppliesItOnceTheAiIdIsRegistered() {
        AnalysisJob job = store.create(7L, "AUTO", List.of(AnalysisImage.pending("image-0", "ab".repeat(32))));
        callbacks.receive("ai-0", failure);
        assertThat(store.findOwned(job.id(), 7L).analysisImages().getFirst().completion()).isNull();
        assertThat(parking.find("ai-0")).contains(failure);
        verifyNoInteractions(dispatches, analysisFinishedEventService, worker);

        store.register(job.id(), 7L, job.analysisImages().getFirst().requestId(), "ai-0");
        assertThat(applier.applyParked("ai-0")).isTrue();
        assertThat(store.findOwned(job.id(), 7L).analysisImages().getFirst().completion()).isEqualTo(failure);
        verify(dispatches).finish(job.analysisImages().getFirst().requestId());
        verify(analysisFinishedEventService).publishImageFinished(7L, job.id(), "image-0", AnalysisStatus.FAILED, 1, 0, 1);
        assertThat(parking.find("ai-0")).isEmpty();
        assertThat(applier.applyParked("ai-0")).isFalse();
    }

    @Test
    void appliesTheCallbackDirectlyWhenRegistrationLandsWhileItIsBeingParked() {
        AnalysisJob job = registeredJob(1);
        doReturn(false).doCallRealMethod().when(store).isRegistered("ai-0");
        callbacks.receive("ai-0", failure);
        assertThat(store.findOwned(job.id(), 7L).analysisImages().getFirst().completion()).isEqualTo(failure);
        verify(worker).wakeUp();
    }

    @Test
    void keepsTheParkedCallbackWhenApplyingItFails() {
        AnalysisJob job = store.create(7L, "AUTO", List.of(AnalysisImage.pending("image-0", "ab".repeat(32))));
        callbacks.receive("ai-0", failure);
        store.register(job.id(), 7L, job.analysisImages().getFirst().requestId(), "ai-0");
        doThrow(new DataAccessResourceFailureException("mysql")).when(dispatches).finish(any());
        assertUnavailable(() -> applier.applyParked("ai-0"));
        assertThat(parking.find("ai-0")).contains(failure);
    }

    @Test
    void asksForRetryWhenTheCallbackCannotBeParked() {
        doThrow(new DataAccessResourceFailureException("redis")).when(store).isRegistered("ai-0");
        assertUnavailable(() -> callbacks.receive("ai-0", failure));
    }

    private AnalysisJob registeredJob(int images) {
        List<AnalysisImage> pending = java.util.stream.IntStream.range(0, images)
                .mapToObj(index -> AnalysisImage.pending("image-" + index, "ab".repeat(32))).toList();
        AnalysisJob job = store.create(7L, "AUTO", pending);
        for (int index = 0; index < images; index++) {
            store.register(job.id(), 7L, pending.get(index).requestId(), "ai-" + index);
        }
        return job;
    }

    private void assertUnavailable(Runnable receive) {
        assertThatThrownBy(receive::run).isInstanceOf(CustomException.class)
                .extracting("exceptionCode").isEqualTo(AnalysisExceptionCode.CALLBACK_UNAVAILABLE);
    }
}
