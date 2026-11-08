package com.dameokja.backend.analysis.application.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.dameokja.backend.analysis.application.completion.AnalysisCompletionPersistence;
import com.dameokja.backend.analysis.application.job.AnalysisJobAcceptance;
import com.dameokja.backend.analysis.domain.AnalysisCompletionFailure;
import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.domain.AnalysisNotificationChannel;
import com.dameokja.backend.analysis.domain.AnalysisStatus;
import com.dameokja.backend.analysis.infrastructure.AnalysisDatabasePendingNotificationStore;
import com.dameokja.backend.analysis.infrastructure.kafka.AnalysisCompletedPayload;
import com.dameokja.backend.global.common.EventEnvelope;
import com.dameokja.backend.notification.application.AnalysisFinishedEventService;
import com.dameokja.backend.notification.application.NotificationPreferenceService;
import com.dameokja.backend.notification.application.NotificationStreamService;
import com.dameokja.backend.support.ServiceIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AnalysisDatabaseCompletionNotifierTest extends ServiceIntegrationTest {
    @Autowired AnalysisNotificationState analysisNotificationState;
    @Autowired AnalysisDatabasePendingNotificationStore analysisDatabasePendingNotificationStore;
    @Autowired AnalysisJobAcceptance analysisJobAcceptance;
    @Autowired AnalysisCompletionPersistence analysisCompletionPersistence;
    @Autowired NotificationPreferenceService notificationPreferenceService;
    private final AnalysisFinishedEventService analysisFinishedEventService = mock(AnalysisFinishedEventService.class);
    private final NotificationStreamService notificationStreamService = mock(NotificationStreamService.class);
    private final List<AnalysisJob> createdAnalysisJobs = new ArrayList<>();
    private Fixture requestingUserFixture;

    @Override
    @BeforeEach
    @AfterEach
    protected void cleanDatabaseAndResetTime() {
        for (AnalysisJob analysisJob : createdAnalysisJobs) {
            jdbcTemplate.update("DELETE FROM analysis_outbox WHERE analysis_job_id = ?", analysisJob.id());
            jdbcTemplate.update("DELETE FROM analysis_job_requests WHERE analysis_job_id = ?", analysisJob.id());
            jdbcTemplate.update("DELETE FROM analysis_job_states WHERE analysis_job_id = ?", analysisJob.id());
        }
        if (requestingUserFixture != null) {
            jdbcTemplate.update("DELETE FROM push_notifications WHERE user_id = ?", requestingUserFixture.userId());
            jdbcTemplate.update("DELETE FROM user_devices WHERE user_id = ?", requestingUserFixture.userId());
        }
        createdAnalysisJobs.clear();
        requestingUserFixture = null;
        reset(analysisFinishedEventService, notificationStreamService);
        super.cleanDatabaseAndResetTime();
    }

    @Test
    void publishesPartialThenFinalEventsOutsideTransactionsAndAcknowledgesAfterPublishing() {
        AnalysisJob analysisJob = acceptAnalysisJob(2);
        when(notificationStreamService.isConnected(requestingUserFixture.userId())).thenReturn(true);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return null;
        }).when(analysisFinishedEventService).publishImageFinished(any(), any(), any(), any(), anyInt(), anyInt(), anyInt());
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(analysisNotificationState.find(analysisJob.id()).orElseThrow().notificationPending()).isTrue();
            return null;
        }).when(analysisFinishedEventService).publish(any(), any(), any(), anyInt(), anyInt(), anyInt(), any());
        completeAnalysisImage(analysisJob, 0);
        createNotifier().deliverPending();
        verify(analysisFinishedEventService).publishImageFinished(requestingUserFixture.userId(), analysisJob.id(), "image-0", AnalysisStatus.FAILED, 2, 0, 1);
        verifyNoInteractions(notificationStreamService);
        completeAnalysisImage(analysisJob, 1);
        createNotifier().deliverPending();
        AnalysisJob updatedAnalysisJob = analysisNotificationState.find(analysisJob.id()).orElseThrow();
        InOrder order = inOrder(analysisFinishedEventService);
        order.verify(analysisFinishedEventService).publishImageFinished(requestingUserFixture.userId(), analysisJob.id(), "image-0", AnalysisStatus.FAILED, 2, 0, 1);
        order.verify(analysisFinishedEventService).publishImageFinished(requestingUserFixture.userId(), analysisJob.id(), "image-1", AnalysisStatus.FAILED, 2, 0, 2);
        order.verify(analysisFinishedEventService).publish(requestingUserFixture.userId(), analysisJob.id(), AnalysisStatus.FAILED, 2, 0, 2, updatedAnalysisJob.expiresAt());
        assertThat(updatedAnalysisJob.notificationPending()).isFalse();
        assertThat(updatedAnalysisJob.notificationChannel()).isEqualTo(AnalysisNotificationChannel.SSE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"offline", "presence", "publish"})
    void fallsBackToPushOnlyAfterThreeFailedSseAttempts(String sseFailureType) {
        AnalysisJob analysisJob = acceptAnalysisJob(1);
        enablePush();
        if (sseFailureType.equals("presence")) {
            when(notificationStreamService.isConnected(requestingUserFixture.userId())).thenThrow(new DataAccessResourceFailureException("redis"));
        } else if (sseFailureType.equals("publish")) {
            when(notificationStreamService.isConnected(requestingUserFixture.userId())).thenReturn(true);
            doThrow(new DataAccessResourceFailureException("publish")).when(analysisFinishedEventService).publish(any(), any(), any(), anyInt(), anyInt(), anyInt(), any());
        }
        completeAnalysisImage(analysisJob, 0);
        for (int sseAttemptNumber = 1; sseAttemptNumber <= 3; sseAttemptNumber++) {
            createNotifier().deliverPending();
            assertThat(pushCount()).isEqualTo(sseAttemptNumber == 3 ? 1 : 0);
            assertThat(analysisNotificationState.find(analysisJob.id()).orElseThrow().notificationPending()).isEqualTo(sseAttemptNumber < 3);
            advanceRetryDelay();
        }
        assertThat(analysisNotificationState.find(analysisJob.id()).orElseThrow().notificationChannel()).isEqualTo(AnalysisNotificationChannel.PUSH);
    }

    @Test
    void retriesFailedImagePublicationWithoutLosingLaterCompletion() {
        AnalysisJob analysisJob = acceptAnalysisJob(2);
        doThrow(new DataAccessResourceFailureException("publish")).doNothing().when(analysisFinishedEventService)
                .publishImageFinished(any(), any(), eq("image-0"), any(), anyInt(), anyInt(), anyInt());
        completeAnalysisImage(analysisJob, 0);
        createNotifier().deliverPending();
        assertThat(analysisNotificationState.find(analysisJob.id()).orElseThrow().imageEventPublished(analysisJob.analysisImages().getFirst().requestId())).isFalse();
        completeAnalysisImage(analysisJob, 1);
        advanceRetryDelay();
        when(notificationStreamService.isConnected(requestingUserFixture.userId())).thenReturn(true);
        createNotifier().deliverPending();
        AnalysisJob updatedAnalysisJob = analysisNotificationState.find(analysisJob.id()).orElseThrow();
        assertThat(updatedAnalysisJob.publishedImageEvents()).containsExactlyInAnyOrderElementsOf(analysisJob.analysisImages().stream().map(AnalysisImage::requestId).toList());
        verify(analysisFinishedEventService, times(2)).publishImageFinished(eq(requestingUserFixture.userId()), eq(analysisJob.id()), eq("image-0"), any(), anyInt(), anyInt(), anyInt());
        verify(analysisFinishedEventService).publishImageFinished(requestingUserFixture.userId(), analysisJob.id(), "image-1", AnalysisStatus.FAILED, 2, 0, 2);
        assertThat(updatedAnalysisJob.notificationPending()).isFalse();
    }

    @Test
    void stopsBeforeLeasingWhenInterrupted() {
        AnalysisJob analysisJob = acceptAnalysisJob(1);
        completeAnalysisImage(analysisJob, 0);
        Thread.currentThread().interrupt();
        try {
            createNotifier().deliverPending();
            assertThat(analysisNotificationState.find(analysisJob.id()).orElseThrow().notificationPending()).isTrue();
            verifyNoInteractions(analysisFinishedEventService, notificationStreamService);
        } finally {
            Thread.interrupted();
        }
        when(notificationStreamService.isConnected(requestingUserFixture.userId())).thenReturn(true);
        createNotifier().deliverPending();
        assertThat(analysisNotificationState.find(analysisJob.id()).orElseThrow().notificationPending()).isFalse();
    }

    @Test
    void expiredLeaseCannotAcknowledgeAfterFinalPublication() {
        AnalysisJob analysisJob = acceptAnalysisJob(1);
        completeAnalysisImage(analysisJob, 0);
        when(notificationStreamService.isConnected(requestingUserFixture.userId())).thenReturn(true);
        doAnswer(invocation -> {
            clock.set(clock.instant().plusSeconds(30).toString());
            Thread.currentThread().interrupt();
            return null;
        }).doNothing().when(analysisFinishedEventService).publish(any(), any(), any(), anyInt(), anyInt(), anyInt(), any());
        try {
            createNotifier().deliverPending();
        } finally {
            Thread.interrupted();
        }
        assertThat(analysisNotificationState.find(analysisJob.id()).orElseThrow().notificationPending()).isTrue();
        assertThat(pushCount()).isZero();
        createNotifier().deliverPending();
        assertThat(analysisNotificationState.find(analysisJob.id()).orElseThrow().notificationPending()).isFalse();
        assertThat(pushCount()).isZero();
    }

    private AnalysisDatabaseCompletionNotifier createNotifier() {
        return new AnalysisDatabaseCompletionNotifier(analysisNotificationState, analysisDatabasePendingNotificationStore, analysisFinishedEventService, notificationStreamService, 3);
    }

    private AnalysisJob acceptAnalysisJob(int imageCount) {
        requestingUserFixture = ownerFixture("DB전달");
        AnalysisJob analysisJob = analysisJobAcceptance.accept(requestingUserFixture.userId(), "AUTO", IntStream.range(0, imageCount)
                .mapToObj(imageIndex -> AnalysisImage.pending("image-" + imageIndex, "ab".repeat(32))).toList());
        createdAnalysisJobs.add(analysisJob);
        return analysisJob;
    }

    private void completeAnalysisImage(AnalysisJob analysisJob, int imageIndex) {
        String analysisRequestId = analysisJob.analysisImages().get(imageIndex).requestId();
        analysisCompletionPersistence.apply(EventEnvelope.create(new AnalysisCompletedPayload(clock.instant(), analysisRequestId,
                requestingUserFixture.userId(), requestingUserFixture.refrigeratorId(),
                "ai-" + analysisJob.id() + "-" + imageIndex, AnalysisStatus.FAILED, clock.instant(), null, new AnalysisCompletionFailure("MODEL_UNAVAILABLE", "분석 실패", true, null))));
    }

    private void enablePush() {
        notificationPreferenceService.create(userRepository.findById(requestingUserFixture.userId()).orElseThrow(), true);
        jdbcTemplate.update("INSERT INTO user_devices(endpoint,p256dh_key,auth_secret_encrypted,encryption_key_version,vapid_key_version,created_at,updated_at,user_id) "
                + "VALUES (?,?,?,'v1','v1',NOW(6),NOW(6),?)", "https://push.example.com/" + requestingUserFixture.userId(), "key", "secret", requestingUserFixture.userId());
    }

    private int pushCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM push_notifications WHERE user_id = ?", Integer.class, requestingUserFixture.userId());
    }

    private void advanceRetryDelay() { clock.set(clock.instant().plusSeconds(5).toString()); }
}
