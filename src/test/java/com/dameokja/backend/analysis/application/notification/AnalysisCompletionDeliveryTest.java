package com.dameokja.backend.analysis.application.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.dameokja.backend.analysis.application.completion.AnalysisCompletionPersistence;
import com.dameokja.backend.analysis.application.job.AnalysisJobAcceptance;
import com.dameokja.backend.analysis.application.job.AnalysisJobStateAccess;
import com.dameokja.backend.analysis.domain.AnalysisCompletion;
import com.dameokja.backend.analysis.domain.AnalysisCompletionFailure;
import com.dameokja.backend.analysis.domain.AnalysisCompletionSuccess;
import com.dameokja.backend.analysis.domain.AnalysisDocumentType;
import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.domain.AnalysisNotificationChannel;
import com.dameokja.backend.analysis.domain.AnalysisStatus;
import com.dameokja.backend.analysis.infrastructure.kafka.AnalysisCompletedPayload;
import com.dameokja.backend.analysis.support.AnalysisDatabaseContractTestSupport;
import com.dameokja.backend.analysis.support.AnalysisDatabaseJobFixture;
import com.dameokja.backend.global.common.EventEnvelope;
import com.dameokja.backend.notification.application.AnalysisFinishedEventService;
import com.dameokja.backend.notification.application.NotificationStreamService;
import com.dameokja.backend.push.application.PushAnalysisCreationService;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AnalysisCompletionDeliveryTest extends AnalysisDatabaseContractTestSupport {
    @Autowired AnalysisJobStateAccess analysisJobStateAccess;
    @Autowired AnalysisJobAcceptance analysisJobAcceptance;
    @Autowired AnalysisCompletionPersistence analysisCompletionPersistence;
    @Autowired AnalysisDatabaseJobFixture analysisDatabaseJobFixture;
    @Autowired AnalysisCompletionNotifier analysisCompletionNotifier;
    @MockitoSpyBean AnalysisNotificationState analysisNotificationState;
    @MockitoBean PushAnalysisCreationService pushAnalysisCreationService;
    @MockitoBean AnalysisFinishedEventService analysisFinishedEventService;
    @MockitoBean NotificationStreamService notificationStreamService;
    private final Map<String, String> requestIdsByAnalysisId = new HashMap<>();

    @BeforeEach
    void prepareOwnerAndClock() {
        clock.set("2026-10-31T03:00:00Z");
        analysisDatabaseJobFixture.ensureUser(7L);
    }

    private final AnalysisCompletion failure = new AnalysisCompletion(AnalysisStatus.FAILED, OffsetDateTime.parse("2026-10-16T12:00:00+09:00"),
            null, new AnalysisCompletionFailure("MODEL_UNAVAILABLE", "분석 실패", true, null));

    @Test
    void publishesImageThenFinalEventWhenTheLastImageCompletesWithMixedResults() {
        AnalysisJob analysisJob = registeredJob(2);
        when(notificationStreamService.isConnected(7L)).thenReturn(true);
        AnalysisCompletion success = new AnalysisCompletion(AnalysisStatus.COMPLETED, failure.completedAt(),
                new AnalysisCompletionSuccess(AnalysisDocumentType.PRODUCT, List.of()), null);
        receive("ai-0", success);
        verify(analysisFinishedEventService).publishImageFinished(7L, analysisJob.id(), "image-0", AnalysisStatus.COMPLETED, 2, 1, 0);
        verifyNoInteractions(notificationStreamService, pushAnalysisCreationService);
        receive("ai-1", failure);
        AnalysisJob storedAnalysisJob = analysisJobStateAccess.findOwned(analysisJob.id(), 7L);
        InOrder order = inOrder(analysisFinishedEventService);
        order.verify(analysisFinishedEventService).publishImageFinished(7L, analysisJob.id(), "image-0", AnalysisStatus.COMPLETED, 2, 1, 0);
        order.verify(analysisFinishedEventService).publishImageFinished(7L, analysisJob.id(), "image-1", AnalysisStatus.FAILED, 2, 1, 1);
        order.verify(analysisFinishedEventService).publish(7L, analysisJob.id(), AnalysisStatus.PARTIALLY_COMPLETED, 2, 1, 1, storedAnalysisJob.expiresAt());
        order.verifyNoMoreInteractions();
        verifyNoInteractions(pushAnalysisCreationService);
        assertThat(storedAnalysisJob.notificationPending()).isFalse();
        assertThat(storedAnalysisJob.notificationChannel()).isEqualTo(AnalysisNotificationChannel.SSE);
    }

    @Test
    void duplicateCompletionSendsNoEventAgain() {
        AnalysisJob analysisJob = registeredJob(2);
        when(notificationStreamService.isConnected(7L)).thenReturn(true);
        AnalysisCompletion success = new AnalysisCompletion(AnalysisStatus.COMPLETED, failure.completedAt(),
                new AnalysisCompletionSuccess(AnalysisDocumentType.PRODUCT, List.of()), null);
        receive("ai-0", success);
        receive("ai-1", failure);
        clearInvocations(analysisFinishedEventService, notificationStreamService, pushAnalysisCreationService);
        receive("ai-0", success);
        verifyNoInteractions(analysisFinishedEventService, notificationStreamService, pushAnalysisCreationService);
        assertThat(analysisJobStateAccess.findOwned(analysisJob.id(), 7L).publishedImageEvents()).hasSize(2);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void retriesImageFinishedAfterPublishFailureWithoutLosingTheSavedImageResult(int images) {
        AnalysisJob analysisJob = registeredJob(images);
        when(notificationStreamService.isConnected(7L)).thenReturn(true);
        doThrow(new DataAccessResourceFailureException("pub/sub")).doNothing().when(analysisFinishedEventService).publishImageFinished(any(), any(), any(), any(), anyInt(), anyInt(), anyInt());
        receive("ai-0", failure);
        assertThat(analysisJobStateAccess.findOwned(analysisJob.id(), 7L).analysisImages().getFirst().completion()).isEqualTo(failure);
        assertThat(analysisJobStateAccess.findOwned(analysisJob.id(), 7L).imageEventPublished(analysisJob.analysisImages().getFirst().requestId())).isFalse();
        retry(5);
        verify(analysisFinishedEventService, times(2)).publishImageFinished(7L, analysisJob.id(), "image-0", AnalysisStatus.FAILED, images, 0, 1);
        verifyNoInteractions(pushAnalysisCreationService);
        assertThat(analysisJobStateAccess.findOwned(analysisJob.id(), 7L).imageEventPublished(analysisJob.analysisImages().getFirst().requestId())).isTrue();
    }

    @Test
    void createsPushImmediatelyForAnOfflineUser() {
        AnalysisJob analysisJob = registeredJob(1);
        receive("ai-0", failure);
        verify(pushAnalysisCreationService).create(analysisJob.id(), 7L, analysisJobStateAccess.findOwned(analysisJob.id(), 7L).expiresAt());
        verify(notificationStreamService).isConnected(7L);
        verify(analysisFinishedEventService, never()).publish(any(), any(), any(), anyInt(), anyInt(), anyInt(), any());
        assertThat(analysisJobStateAccess.findOwned(analysisJob.id(), 7L).notificationPending()).isFalse();
        retry(4);
        verifyNoMoreInteractions(pushAnalysisCreationService, notificationStreamService);
    }

    @Test
    void retriesSsePublicationAndStopsOnceItSucceeds() {
        AnalysisJob analysisJob = registeredJob(1);
        when(notificationStreamService.isConnected(7L)).thenReturn(true);
        doThrow(new DataAccessResourceFailureException("pub/sub")).doNothing().when(analysisFinishedEventService).publish(any(), any(), any(), anyInt(), anyInt(), anyInt(), any());
        receive("ai-0", failure);
        assertThat(analysisJobStateAccess.findOwned(analysisJob.id(), 7L).notificationPending()).isTrue();
        retry(3);
        verify(analysisFinishedEventService, times(2)).publish(7L, analysisJob.id(), AnalysisStatus.FAILED, 1, 0, 1, analysisJobStateAccess.findOwned(analysisJob.id(), 7L).expiresAt());
        verifyNoInteractions(pushAnalysisCreationService);
        assertThat(analysisJobStateAccess.findOwned(analysisJob.id(), 7L).notificationPending()).isFalse();
    }

    @Test
    void switchesToPushWhenSsePublicationKeepsFailing() {
        AnalysisJob analysisJob = registeredJob(1);
        when(notificationStreamService.isConnected(7L)).thenReturn(true);
        doThrow(new DataAccessResourceFailureException("pub/sub")).when(analysisFinishedEventService).publish(any(), any(), any(), anyInt(), anyInt(), anyInt(), any());
        receive("ai-0", failure);
        retry(5);
        verify(analysisFinishedEventService, times(3)).publish(any(), any(), any(), anyInt(), anyInt(), anyInt(), any());
        AnalysisJob savedAnalysisJob = analysisJobStateAccess.findOwned(analysisJob.id(), 7L);
        verify(pushAnalysisCreationService).create(analysisJob.id(), 7L, savedAnalysisJob.expiresAt());
        assertThat(savedAnalysisJob.notificationChannel()).isEqualTo(AnalysisNotificationChannel.PUSH);
        assertThat(savedAnalysisJob.notificationPending()).isFalse();
    }

    @Test
    void retriesAfterPublicationWhenTheDeliveryRecordCannotBeStored() {
        AnalysisJob analysisJob = registeredJob(1);
        when(notificationStreamService.isConnected(7L)).thenReturn(true);
        doThrow(new DataAccessResourceFailureException("ack")).doCallRealMethod().when(analysisNotificationState).acknowledgeNotification(any());
        receive("ai-0", failure);
        retry(3);
        verify(analysisFinishedEventService, times(2)).publish(7L, analysisJob.id(), AnalysisStatus.FAILED, 1, 0, 1, analysisJobStateAccess.findOwned(analysisJob.id(), 7L).expiresAt());
        verifyNoInteractions(pushAnalysisCreationService);
    }

    @Test
    void retainsPushSelectionIfTheUserConnectsBeforeRetry() {
        AnalysisJob analysisJob = registeredJob(1);
        doThrow(new DataAccessResourceFailureException("database")).doNothing().when(pushAnalysisCreationService).create(any(), any(), any());
        receive("ai-0", failure);
        assertThat(analysisJobStateAccess.findOwned(analysisJob.id(), 7L).notificationPending()).isTrue();
        verify(pushAnalysisCreationService).create(any(), any(), any());
        when(notificationStreamService.isConnected(7L)).thenReturn(true);
        retry(3);
        verify(pushAnalysisCreationService, times(2)).create(any(), any(), any());
        verify(notificationStreamService).isConnected(7L);
        assertThat(analysisJobStateAccess.findOwned(analysisJob.id(), 7L).notificationPending()).isFalse();
        verify(analysisFinishedEventService).publishImageFinished(7L, analysisJob.id(), "image-0", AnalysisStatus.FAILED, 1, 0, 1);
        verifyNoMoreInteractions(analysisFinishedEventService);
        assertThat(analysisJobStateAccess.findOwned(analysisJob.id(), 7L).notificationChannel()).isEqualTo(AnalysisNotificationChannel.PUSH);
    }

    @Test
    void handsOffToPushAfterThreePresenceLookupFailures() {
        AnalysisJob analysisJob = registeredJob(1);
        when(notificationStreamService.isConnected(7L)).thenThrow(new DataAccessResourceFailureException("redis"));
        receive("ai-0", failure);
        retry(1);
        verifyNoInteractions(pushAnalysisCreationService);
        retry(1);
        verify(pushAnalysisCreationService).create(analysisJob.id(), 7L, analysisJobStateAccess.findOwned(analysisJob.id(), 7L).expiresAt());
        retry(3);
        verify(notificationStreamService, times(3)).isConnected(7L);
        verifyNoMoreInteractions(pushAnalysisCreationService);
    }

    @Test
    void doesNotPublishFinalBeforeAChannelIsSafelyStored() {
        AnalysisJob analysisJob = registeredJob(1);
        when(notificationStreamService.isConnected(7L)).thenReturn(true);
        doThrow(new DataAccessResourceFailureException("selection")).when(analysisNotificationState).selectNotificationChannel(any(), eq(AnalysisNotificationChannel.SSE));
        receive("ai-0", failure);
        verify(analysisFinishedEventService).publishImageFinished(7L, analysisJob.id(), "image-0", AnalysisStatus.FAILED, 1, 0, 1);
        verifyNoMoreInteractions(analysisFinishedEventService);
        verifyNoInteractions(pushAnalysisCreationService);
        verify(analysisNotificationState, never()).acknowledgeNotification(any());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void stopsRetryingAnImageAfterThreeFailuresWithoutBlockingFinalNotification(int imageCount) {
        AnalysisJob analysisJob = registeredJob(imageCount);
        when(notificationStreamService.isConnected(7L)).thenReturn(true);
        doThrow(new DataAccessResourceFailureException("pub/sub")).when(analysisFinishedEventService)
                .publishImageFinished(any(), any(), eq("image-0"), any(), anyInt(), anyInt(), anyInt());
        receive("ai-0", failure);
        retry(5);
        if (imageCount == 2) { receive("ai-1", failure); }
        retry(2);
        verify(analysisFinishedEventService, times(3)).publishImageFinished(7L, analysisJob.id(), "image-0", AnalysisStatus.FAILED, imageCount, 0, 1);
        verify(analysisFinishedEventService).publish(7L, analysisJob.id(), AnalysisStatus.FAILED, imageCount, 0, imageCount,
                analysisJobStateAccess.findOwned(analysisJob.id(), 7L).expiresAt());
        verifyNoInteractions(pushAnalysisCreationService);
        assertThat(analysisJobStateAccess.findOwned(analysisJob.id(), 7L).imageEventPublished(analysisJob.analysisImages().getFirst().requestId())).isFalse();
    }

    @Test
    void publishesSseOutsideDatabaseTransactions() {
        registeredJob(1);
        when(notificationStreamService.isConnected(7L)).thenReturn(true);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return null;
        }).when(analysisFinishedEventService).publishImageFinished(any(), any(), any(), any(), anyInt(), anyInt(), anyInt());
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return null;
        }).when(analysisFinishedEventService).publish(any(), any(), any(), anyInt(), anyInt(), anyInt(), any());
        receive("ai-0", failure);
        verify(analysisFinishedEventService).publish(any(), any(), any(), anyInt(), anyInt(), anyInt(), any());
    }

    @Test
    void handsOffImmediatelyIfSseDisconnectsBeforeTheNextAttempt() {
        AnalysisJob analysisJob = registeredJob(1);
        when(notificationStreamService.isConnected(7L)).thenReturn(true, false);
        doThrow(new DataAccessResourceFailureException("pub/sub")).when(analysisFinishedEventService).publish(any(), any(), any(), anyInt(), anyInt(), anyInt(), any());
        receive("ai-0", failure);
        verifyNoInteractions(pushAnalysisCreationService);
        retry(1);
        verify(pushAnalysisCreationService).create(analysisJob.id(), 7L, analysisJobStateAccess.findOwned(analysisJob.id(), 7L).expiresAt());
        retry(3);
        verify(analysisFinishedEventService).publish(any(), any(), any(), anyInt(), anyInt(), anyInt(), any());
        verify(notificationStreamService, times(2)).isConnected(7L);
    }

    private AnalysisJob registeredJob(int images) {
        List<AnalysisImage> pendingImages = java.util.stream.IntStream.range(0, images)
                .mapToObj(index -> AnalysisImage.pending("image-" + index, "ab".repeat(32))).toList();
        AnalysisJob analysisJob = analysisJobAcceptance.accept(7L, "AUTO", pendingImages);
        for (int index = 0; index < images; index++) {
            requestIdsByAnalysisId.put("ai-" + index, analysisJob.analysisImages().get(index).requestId());
        }
        return analysisJob;
    }

    private void retry(int times) {
        for (int round = 0; round < times; round++) {
            clock.set(clock.instant().plusSeconds(1).toString());
            analysisCompletionNotifier.deliverPending();
        }
    }

    private void receive(String analysisId, AnalysisCompletion completion) {
        persist(analysisId, completion);
        analysisCompletionNotifier.deliverPending();
    }

    private void persist(String analysisId, AnalysisCompletion completion) {
        analysisCompletionPersistence.apply(new EventEnvelope<>(UUID.randomUUID().toString(),
                new AnalysisCompletedPayload(clock.instant(),
                requestIdsByAnalysisId.get(analysisId), 7L, com.dameokja.backend.analysis.support.AnalysisRefrigeratorFixture.ensureCurrent(jdbcTemplate, 7L),
                analysisId, completion.status(), completion.completedAt().toInstant(), completion.result(), completion.error())));
    }

}
