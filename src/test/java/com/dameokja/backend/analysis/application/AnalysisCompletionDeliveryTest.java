package com.dameokja.backend.analysis.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
    private final AnalysisFinishedEventService events = mock(AnalysisFinishedEventService.class);
    private final NotificationStreamService connections = mock(NotificationStreamService.class);
    private final AnalysisCallbackService callbacks = new AnalysisCallbackService(store, new AnalysisCompletionValidator(), pushes, events, connections);
    private final AnalysisCompletion failure = new AnalysisCompletion(AnalysisStatus.FAILED, OffsetDateTime.parse("2026-10-16T12:00:00+09:00"),
            null, new AnalysisCompletionFailure("MODEL_UNAVAILABLE", "분석 실패", true, null));

    @Test
    void publishesOnlyOnceForConnectedUsersAfterAllImagesEndIncludingFailures() {
        AnalysisJob job = registeredJob(2);
        when(connections.isConnected(7L)).thenReturn(true);
        AnalysisCompletion success = new AnalysisCompletion(AnalysisStatus.COMPLETED, failure.completedAt(),
                new AnalysisCompletionSuccess(AnalysisDocumentType.PRODUCT, List.of()), null);
        callbacks.receive("ai-0", success);
        verify(events).publishImageFinished(7L, job.id(), "image-0", AnalysisStatus.COMPLETED, 2, 1, 0);
        verifyNoInteractions(connections, pushes);
        callbacks.receive("ai-1", failure);
        callbacks.receive("ai-0", success);
        AnalysisJob stored = store.findOwned(job.id(), 7L);
        InOrder order = inOrder(events);
        order.verify(events).publishImageFinished(7L, job.id(), "image-0", AnalysisStatus.COMPLETED, 2, 1, 0);
        order.verify(events).publishImageFinished(7L, job.id(), "image-1", AnalysisStatus.FAILED, 2, 1, 1);
        order.verify(events).publish(7L, job.id(), AnalysisStatus.PARTIALLY_COMPLETED, 2, 1, 1, stored.expiresAt());
        order.verify(events).publishImageFinished(7L, job.id(), "image-0", AnalysisStatus.COMPLETED, 2, 1, 1);
        order.verifyNoMoreInteractions();
        verifyNoInteractions(pushes);
        assertThat(stored.notificationPending()).isFalse();
        assertThat(stored.notificationChannel()).isEqualTo(AnalysisNotificationChannel.SSE);
    }

    @Test
    void sendsFirstAndMiddleFailuresAsImageFinishedIncludingDuplicateCallbacksWithoutPresenceOrPush() {
        AnalysisJob job = registeredJob(3);
        callbacks.receive("ai-0", failure);
        callbacks.receive("ai-1", failure);
        callbacks.receive("ai-0", failure);
        verify(events).publishImageFinished(7L, job.id(), "image-0", AnalysisStatus.FAILED, 3, 0, 1);
        verify(events).publishImageFinished(7L, job.id(), "image-0", AnalysisStatus.FAILED, 3, 0, 2);
        verify(events).publishImageFinished(7L, job.id(), "image-1", AnalysisStatus.FAILED, 3, 0, 2);
        verifyNoMoreInteractions(events);
        verifyNoInteractions(connections, pushes);
        AnalysisJob saved = store.findOwned(job.id(), 7L);
        assertThat(saved.notificationPending()).isFalse();
        assertThat(saved.notificationChannel()).isNull();
        assertThat(saved.expiresAt()).isNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void retriesImageFinishedAfterPublishFailureWithoutLosingTheSavedImageResult(int images) {
        AnalysisJob job = registeredJob(images);
        doThrow(new DataAccessResourceFailureException("pub/sub")).doNothing().when(events).publishImageFinished(any(), any(), any(), any(), anyInt(), anyInt(), anyInt());
        assertUnavailable(() -> callbacks.receive("ai-0", failure));
        assertThat(store.findOwned(job.id(), 7L).analysisImages().getFirst().completion()).isEqualTo(failure);
        verifyNoInteractions(connections, pushes);
        callbacks.receive("ai-0", failure);
        verify(events, times(2)).publishImageFinished(7L, job.id(), "image-0", AnalysisStatus.FAILED, images, 0, 1);
        if (images == 1) {
            verify(pushes).create(job.id(), 7L, store.findOwned(job.id(), 7L).expiresAt());
        } else {
            verifyNoInteractions(connections, pushes);
        }
    }

    @Test
    void sendsEveryImageEventAndCreatesPushForTheOfflineFinalImage() {
        AnalysisJob job = registeredJob(2);
        callbacks.receive("ai-0", failure);
        callbacks.receive("ai-1", failure);
        callbacks.receive("ai-1", failure);
        verify(events).publishImageFinished(7L, job.id(), "image-0", AnalysisStatus.FAILED, 2, 0, 1);
        verify(events, times(2)).publishImageFinished(7L, job.id(), "image-1", AnalysisStatus.FAILED, 2, 0, 2);
        verifyNoMoreInteractions(events);
        verify(pushes).create(job.id(), 7L, store.findOwned(job.id(), 7L).expiresAt());
        verify(connections).isConnected(7L);
    }

    @Test
    void sendsImageFinishedForAnIncompleteLegacyJobEvenIfItAlreadyHasAnExpiry() {
        AnalysisJob job = registeredJob(2);
        AnalysisJob legacy = new AnalysisJob(job.id(), 7L, job.inputHint(), store.findOwned(job.id(), 7L).analysisImages(),
                job.submittedAt(), job.submittedAt().plusHours(1), false);
        RedisTestSupport.redisTemplate().opsForValue().set("analysis:job:" + job.id(), JsonMapper.builder().build().writeValueAsString(legacy));
        callbacks.receive("ai-0", failure);
        verify(events).publishImageFinished(7L, job.id(), "image-0", AnalysisStatus.FAILED, 2, 0, 1);
        verifyNoInteractions(connections, pushes);
    }

    @ParameterizedTest
    @CsvSource({"COMPLETED,1,0", "FAILED,0,1"})
    void publishesFinalStatusAndCountsAfterAllImagesEnd(AnalysisStatus status, int completed, int failed) {
        AnalysisJob job = registeredJob(1);
        when(connections.isConnected(7L)).thenReturn(true);
        AnalysisCompletion completion = status == AnalysisStatus.FAILED ? failure : new AnalysisCompletion(status, failure.completedAt(),
                new AnalysisCompletionSuccess(AnalysisDocumentType.PRODUCT, List.of()), null);
        callbacks.receive("ai-0", completion);
        verify(events).publishImageFinished(7L, job.id(), "image-0", status, 1, completed, failed);
        verify(events).publish(7L, job.id(), status, 1, completed, failed, store.findOwned(job.id(), 7L).expiresAt());
        verifyNoMoreInteractions(events);
        verifyNoInteractions(pushes);
    }

    @Test
    void retriesSsePublicationWithoutSwitchingToPushAfterDisconnect() {
        AnalysisJob job = registeredJob(1);
        when(connections.isConnected(7L)).thenReturn(true);
        doThrow(new DataAccessResourceFailureException("pub/sub")).doNothing().when(events).publish(any(), any(), any(), anyInt(), anyInt(), anyInt(), any());
        assertUnavailable(() -> callbacks.receive("ai-0", failure));
        when(connections.isConnected(7L)).thenReturn(false);
        callbacks.receive("ai-0", failure);
        verify(connections).isConnected(7L);
        verifyNoInteractions(pushes);
        assertThat(store.findOwned(job.id(), 7L).notificationPending()).isFalse();
    }

    @Test
    void retriesTheSameSseChannelAfterAcknowledgementFails() {
        AnalysisJob job = registeredJob(1);
        when(connections.isConnected(7L)).thenReturn(true);
        doThrow(new DataAccessResourceFailureException("ack")).doCallRealMethod().when(store).acknowledgeNotification(job.id());
        assertUnavailable(() -> callbacks.receive("ai-0", failure));
        when(connections.isConnected(7L)).thenReturn(false);
        callbacks.receive("ai-0", failure);
        verify(events, times(2)).publish(7L, job.id(), AnalysisStatus.FAILED, 1, 0, 1, store.findOwned(job.id(), 7L).expiresAt());
        verifyNoInteractions(pushes);
    }

    @Test
    void retainsPushSelectionIfTheUserConnectsBeforeRetry() {
        AnalysisJob job = registeredJob(1);
        doThrow(new DataAccessResourceFailureException("database")).doNothing().when(pushes).create(any(), any(), any());
        assertUnavailable(() -> callbacks.receive("ai-0", failure));
        when(connections.isConnected(7L)).thenReturn(true);
        callbacks.receive("ai-0", failure);
        verify(connections).isConnected(7L);
        verify(events, times(2)).publishImageFinished(7L, job.id(), "image-0", AnalysisStatus.FAILED, 1, 0, 1);
        verifyNoMoreInteractions(events);
        assertThat(store.findOwned(job.id(), 7L).notificationChannel()).isEqualTo(AnalysisNotificationChannel.PUSH);
    }

    @Test
    void keepsTheResultPendingWhenPresenceLookupFailsInsteadOfCreatingPush() {
        AnalysisJob job = registeredJob(1);
        when(connections.isConnected(7L)).thenThrow(new DataAccessResourceFailureException("redis"));
        assertUnavailable(() -> callbacks.receive("ai-0", failure));
        AnalysisJob saved = store.findOwned(job.id(), 7L);
        assertThat(saved.analysisImages().getFirst().completion()).isEqualTo(failure);
        assertThat(saved.notificationChannel()).isNull();
        assertThat(saved.notificationPending()).isTrue();
        verify(events).publishImageFinished(7L, job.id(), "image-0", AnalysisStatus.FAILED, 1, 0, 1);
        verifyNoMoreInteractions(events);
        verifyNoInteractions(pushes);
    }

    @Test
    void doesNotPublishFinalBeforeAChannelIsSafelyStored() {
        AnalysisJob job = registeredJob(1);
        when(connections.isConnected(7L)).thenReturn(true);
        doThrow(new DataAccessResourceFailureException("selection")).when(store).selectNotificationChannel(job.id(), AnalysisNotificationChannel.SSE);
        assertUnavailable(() -> callbacks.receive("ai-0", failure));
        verify(events).publishImageFinished(7L, job.id(), "image-0", AnalysisStatus.FAILED, 1, 0, 1);
        verifyNoMoreInteractions(events);
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

    private void assertUnavailable(Runnable receive) {
        assertThatThrownBy(receive::run).isInstanceOf(CustomException.class)
                .extracting("exceptionCode").isEqualTo(AnalysisExceptionCode.CALLBACK_UNAVAILABLE);
    }
}
