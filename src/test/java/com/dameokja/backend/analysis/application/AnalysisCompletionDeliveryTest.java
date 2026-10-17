package com.dameokja.backend.analysis.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
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
import com.dameokja.backend.analysis.infrastructure.AnalysisCallbackParking;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStore;
import com.dameokja.backend.analysis.infrastructure.AnalysisNotificationRetryStore;
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
    private final AnalysisDispatchUpdater analysisDispatchUpdater = mock(AnalysisDispatchUpdater.class);
    private final AnalysisDispatchWorker analysisDispatchWorker = mock(AnalysisDispatchWorker.class);
    private final AnalysisCallbackParking analysisCallbackParking = new AnalysisCallbackParking(Duration.ofMinutes(10), RedisTestSupport.redisTemplate(),
            JsonMapper.builder().build());
    private final AnalysisNotificationRetryStore analysisNotificationRetryStore = spy(new AnalysisNotificationRetryStore(RedisTestSupport.redisTemplate()));
    private final AnalysisCompletionNotifier analysisCompletionNotifier = new AnalysisCompletionNotifier(store, analysisNotificationRetryStore, pushes, analysisFinishedEventService,
            notificationStreamService, Clock.system(BusinessTime.ZONE), Duration.ofMillis(1), 3);
    private final AnalysisCompletionApplier analysisCompletionApplier = new AnalysisCompletionApplier(store, analysisCompletionNotifier, analysisDispatchUpdater, analysisCallbackParking);
    private final AnalysisCallbackService callbacks = new AnalysisCallbackService(store, new AnalysisCompletionValidator(), analysisCallbackParking, analysisCompletionApplier, analysisDispatchWorker);
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
    void duplicateCallbackAfterCompletionSendsNoEventAgain() {
        AnalysisJob job = registeredJob(2);
        when(notificationStreamService.isConnected(7L)).thenReturn(true);
        AnalysisCompletion success = new AnalysisCompletion(AnalysisStatus.COMPLETED, failure.completedAt(),
                new AnalysisCompletionSuccess(AnalysisDocumentType.PRODUCT, List.of()), null);
        callbacks.receive("ai-0", success);
        callbacks.receive("ai-1", failure);
        clearInvocations(analysisFinishedEventService, notificationStreamService, pushes);
        callbacks.receive("ai-0", success);
        verifyNoInteractions(analysisFinishedEventService, notificationStreamService, pushes);
        assertThat(store.findOwned(job.id(), 7L).publishedImageEvents()).hasSize(2);
    }

    @Test
    void sendsEachImageEventOnceWithoutPresenceOrPushWhileTheJobIsUnfinished() {
        AnalysisJob job = registeredJob(3);
        callbacks.receive("ai-0", failure);
        callbacks.receive("ai-1", failure);
        callbacks.receive("ai-0", failure);
        verify(analysisFinishedEventService).publishImageFinished(7L, job.id(), "image-0", AnalysisStatus.FAILED, 3, 0, 1);
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
        callbacks.receive("ai-0", failure);
        assertThat(store.findOwned(job.id(), 7L).analysisImages().getFirst().completion()).isEqualTo(failure);
        assertThat(store.findOwned(job.id(), 7L).imageEventPublished(job.analysisImages().getFirst().requestId())).isFalse();
        verifyNoInteractions(notificationStreamService, pushes);
        retry(5);
        verify(analysisFinishedEventService, times(2)).publishImageFinished(7L, job.id(), "image-0", AnalysisStatus.FAILED, images, 0, 1);
        if (images == 1) {
            verify(pushes).create(job.id(), 7L, store.findOwned(job.id(), 7L).expiresAt());
        } else {
            verifyNoInteractions(notificationStreamService, pushes);
        }
    }

    @Test
    void sendsTheImageEventOnceWhenConcurrentAppliesBothSeeItUnsent() {
        AnalysisJob job = registeredJob(2);
        callbacks.receive("ai-0", failure);
        AnalysisJob stale = store.findOwned(job.id(), 7L).withdrawImageEvent(job.analysisImages().getFirst().requestId());
        assertThat(store.claimImageEvent(stale.id(), stale.analysisImages().getFirst().requestId())).isFalse();
        verify(analysisFinishedEventService).publishImageFinished(7L, job.id(), "image-0", AnalysisStatus.FAILED, 2, 0, 1);
    }

    @Test
    void createsPushAfterThreeSseRetriesForAnOfflineUser() {
        AnalysisJob job = registeredJob(2);
        callbacks.receive("ai-0", failure);
        callbacks.receive("ai-1", failure);
        retry(2);
        verifyNoInteractions(pushes);
        retry(5);
        verify(analysisFinishedEventService).publishImageFinished(7L, job.id(), "image-0", AnalysisStatus.FAILED, 2, 0, 1);
        verify(analysisFinishedEventService).publishImageFinished(7L, job.id(), "image-1", AnalysisStatus.FAILED, 2, 0, 2);
        verifyNoMoreInteractions(analysisFinishedEventService);
        verify(pushes).create(job.id(), 7L, store.findOwned(job.id(), 7L).expiresAt());
        verify(notificationStreamService, times(4)).isConnected(7L);
        assertThat(store.findOwned(job.id(), 7L).notificationChannel()).isEqualTo(AnalysisNotificationChannel.PUSH);
        assertThat(store.findOwned(job.id(), 7L).notificationPending()).isFalse();
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
    void retriesSsePublicationAndStopsOnceItSucceeds() {
        AnalysisJob job = registeredJob(1);
        when(notificationStreamService.isConnected(7L)).thenReturn(true);
        doThrow(new DataAccessResourceFailureException("pub/sub")).doNothing().when(analysisFinishedEventService).publish(any(), any(), any(), anyInt(), anyInt(), anyInt(), any());
        callbacks.receive("ai-0", failure);
        assertThat(store.findOwned(job.id(), 7L).notificationPending()).isTrue();
        retry(3);
        verify(analysisFinishedEventService, times(2)).publish(7L, job.id(), AnalysisStatus.FAILED, 1, 0, 1, store.findOwned(job.id(), 7L).expiresAt());
        verifyNoInteractions(pushes);
        assertThat(store.findOwned(job.id(), 7L).notificationPending()).isFalse();
    }

    @Test
    void switchesToPushWhenSsePublicationKeepsFailing() {
        AnalysisJob job = registeredJob(1);
        when(notificationStreamService.isConnected(7L)).thenReturn(true);
        doThrow(new DataAccessResourceFailureException("pub/sub")).when(analysisFinishedEventService).publish(any(), any(), any(), anyInt(), anyInt(), anyInt(), any());
        callbacks.receive("ai-0", failure);
        retry(5);
        verify(analysisFinishedEventService, times(4)).publish(any(), any(), any(), anyInt(), anyInt(), anyInt(), any());
        AnalysisJob saved = store.findOwned(job.id(), 7L);
        verify(pushes).create(job.id(), 7L, saved.expiresAt());
        assertThat(saved.notificationChannel()).isEqualTo(AnalysisNotificationChannel.PUSH);
        assertThat(saved.notificationPending()).isFalse();
    }

    @Test
    void retriesTheSameSseChannelAfterAcknowledgementFails() {
        AnalysisJob job = registeredJob(1);
        when(notificationStreamService.isConnected(7L)).thenReturn(true);
        doThrow(new DataAccessResourceFailureException("ack")).doCallRealMethod().when(store).acknowledgeNotification(job.id());
        callbacks.receive("ai-0", failure);
        retry(3);
        verify(analysisFinishedEventService, times(2)).publish(7L, job.id(), AnalysisStatus.FAILED, 1, 0, 1, store.findOwned(job.id(), 7L).expiresAt());
        verifyNoInteractions(pushes);
    }

    @Test
    void retainsPushSelectionIfTheUserConnectsBeforeRetry() {
        AnalysisJob job = registeredJob(1);
        doThrow(new DataAccessResourceFailureException("database")).doNothing().when(pushes).create(any(), any(), any());
        callbacks.receive("ai-0", failure);
        retry(3);
        verify(pushes).create(any(), any(), any());
        when(notificationStreamService.isConnected(7L)).thenReturn(true);
        retry(3);
        verify(pushes, times(2)).create(any(), any(), any());
        verify(notificationStreamService, times(4)).isConnected(7L);
        assertThat(store.findOwned(job.id(), 7L).notificationPending()).isFalse();
        verify(analysisFinishedEventService).publishImageFinished(7L, job.id(), "image-0", AnalysisStatus.FAILED, 1, 0, 1);
        verifyNoMoreInteractions(analysisFinishedEventService);
        assertThat(store.findOwned(job.id(), 7L).notificationChannel()).isEqualTo(AnalysisNotificationChannel.PUSH);
    }

    @Test
    void keepsTheResultPendingWhenPresenceLookupFailsUntilSseRetriesRunOut() {
        AnalysisJob job = registeredJob(1);
        when(notificationStreamService.isConnected(7L)).thenThrow(new DataAccessResourceFailureException("redis"));
        callbacks.receive("ai-0", failure);
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
        callbacks.receive("ai-0", failure);
        verify(analysisFinishedEventService).publishImageFinished(7L, job.id(), "image-0", AnalysisStatus.FAILED, 1, 0, 1);
        verifyNoMoreInteractions(analysisFinishedEventService);
        verifyNoInteractions(pushes);
        verify(store, never()).acknowledgeNotification(job.id());
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

    @Test
    void stopsRetryingOnceEverythingIsDelivered() {
        AnalysisJob job = registeredJob(1);
        when(notificationStreamService.isConnected(7L)).thenReturn(true);
        callbacks.receive("ai-0", failure);
        verify(analysisNotificationRetryStore).finish(job.id());
        retry(2);
        verify(analysisFinishedEventService).publish(any(), any(), any(), anyInt(), anyInt(), anyInt(), any());
    }

    private void retry(int times) {
        for (int round = 0; round < times; round++) {
            try {
                Thread.sleep(3);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            analysisCompletionNotifier.retryDue();
        }
    }
}
