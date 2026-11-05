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
    @Autowired AnalysisNotificationState state;
    @Autowired AnalysisDatabasePendingNotificationStore store;
    @Autowired AnalysisJobAcceptance acceptance;
    @Autowired AnalysisCompletionPersistence completions;
    @Autowired NotificationPreferenceService preferences;
    private final AnalysisFinishedEventService events = mock(AnalysisFinishedEventService.class);
    private final NotificationStreamService streams = mock(NotificationStreamService.class);
    private final List<AnalysisJob> createdJobs = new ArrayList<>();
    private Fixture owner;

    @Override
    @BeforeEach
    @AfterEach
    protected void cleanDatabaseAndResetTime() {
        for (AnalysisJob job : createdJobs) {
            jdbcTemplate.update("DELETE FROM analysis_outbox WHERE analysis_job_id = ?", job.id());
            jdbcTemplate.update("DELETE FROM analysis_job_requests WHERE analysis_job_id = ?", job.id());
            jdbcTemplate.update("DELETE FROM analysis_job_states WHERE analysis_job_id = ?", job.id());
        }
        if (owner != null) {
            jdbcTemplate.update("DELETE FROM push_notifications WHERE user_id = ?", owner.userId());
            jdbcTemplate.update("DELETE FROM user_devices WHERE user_id = ?", owner.userId());
        }
        createdJobs.clear();
        owner = null;
        reset(events, streams);
        super.cleanDatabaseAndResetTime();
    }

    @Test
    void publishesPartialThenFinalEventsOutsideTransactionsAndAcknowledgesAfterPublishing() {
        AnalysisJob job = accept(2);
        when(streams.isConnected(owner.userId())).thenReturn(true);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return null;
        }).when(events).publishImageFinished(any(), any(), any(), any(), anyInt(), anyInt(), anyInt());
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(state.find(job.id()).orElseThrow().notificationPending()).isTrue();
            return null;
        }).when(events).publish(any(), any(), any(), anyInt(), anyInt(), anyInt(), any());
        complete(job, 0);
        notifier().deliverPending();
        verify(events).publishImageFinished(owner.userId(), job.id(), "image-0", AnalysisStatus.FAILED, 2, 0, 1);
        verifyNoInteractions(streams);
        complete(job, 1);
        notifier().deliverPending();
        AnalysisJob result = state.find(job.id()).orElseThrow();
        InOrder order = inOrder(events);
        order.verify(events).publishImageFinished(owner.userId(), job.id(), "image-0", AnalysisStatus.FAILED, 2, 0, 1);
        order.verify(events).publishImageFinished(owner.userId(), job.id(), "image-1", AnalysisStatus.FAILED, 2, 0, 2);
        order.verify(events).publish(owner.userId(), job.id(), AnalysisStatus.FAILED, 2, 0, 2, result.expiresAt());
        assertThat(result.notificationPending()).isFalse();
        assertThat(result.notificationChannel()).isEqualTo(AnalysisNotificationChannel.SSE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"offline", "presence", "publish"})
    void fallsBackToPushOnlyAfterThreeFailedSseAttempts(String failure) {
        AnalysisJob job = accept(1);
        enablePush();
        if (failure.equals("presence")) {
            when(streams.isConnected(owner.userId())).thenThrow(new DataAccessResourceFailureException("redis"));
        } else if (failure.equals("publish")) {
            when(streams.isConnected(owner.userId())).thenReturn(true);
            doThrow(new DataAccessResourceFailureException("publish")).when(events).publish(any(), any(), any(), anyInt(), anyInt(), anyInt(), any());
        }
        complete(job, 0);
        for (int attempt = 1; attempt <= 3; attempt++) {
            notifier().deliverPending();
            assertThat(pushCount()).isEqualTo(attempt == 3 ? 1 : 0);
            assertThat(state.find(job.id()).orElseThrow().notificationPending()).isEqualTo(attempt < 3);
            advance();
        }
        assertThat(state.find(job.id()).orElseThrow().notificationChannel()).isEqualTo(AnalysisNotificationChannel.PUSH);
    }

    @Test
    void retriesFailedImagePublicationWithoutLosingLaterCompletion() {
        AnalysisJob job = accept(2);
        doThrow(new DataAccessResourceFailureException("publish")).doNothing().when(events)
                .publishImageFinished(any(), any(), eq("image-0"), any(), anyInt(), anyInt(), anyInt());
        complete(job, 0);
        notifier().deliverPending();
        assertThat(state.find(job.id()).orElseThrow().imageEventPublished(job.analysisImages().getFirst().requestId())).isFalse();
        complete(job, 1);
        advance();
        when(streams.isConnected(owner.userId())).thenReturn(true);
        notifier().deliverPending();
        AnalysisJob result = state.find(job.id()).orElseThrow();
        assertThat(result.publishedImageEvents()).containsExactlyInAnyOrderElementsOf(job.analysisImages().stream().map(AnalysisImage::requestId).toList());
        verify(events, times(2)).publishImageFinished(eq(owner.userId()), eq(job.id()), eq("image-0"), any(), anyInt(), anyInt(), anyInt());
        verify(events).publishImageFinished(owner.userId(), job.id(), "image-1", AnalysisStatus.FAILED, 2, 0, 2);
        assertThat(result.notificationPending()).isFalse();
    }

    @Test
    void stopsBeforeLeasingWhenInterrupted() {
        AnalysisJob job = accept(1);
        complete(job, 0);
        Thread.currentThread().interrupt();
        try {
            notifier().deliverPending();
            assertThat(state.find(job.id()).orElseThrow().notificationPending()).isTrue();
            verifyNoInteractions(events, streams);
        } finally {
            Thread.interrupted();
        }
        when(streams.isConnected(owner.userId())).thenReturn(true);
        notifier().deliverPending();
        assertThat(state.find(job.id()).orElseThrow().notificationPending()).isFalse();
    }

    @Test
    void expiredLeaseCannotAcknowledgeAfterFinalPublication() {
        AnalysisJob job = accept(1);
        complete(job, 0);
        when(streams.isConnected(owner.userId())).thenReturn(true);
        doAnswer(invocation -> {
            clock.set(clock.instant().plusSeconds(30).toString());
            Thread.currentThread().interrupt();
            return null;
        }).doNothing().when(events).publish(any(), any(), any(), anyInt(), anyInt(), anyInt(), any());
        try {
            notifier().deliverPending();
        } finally {
            Thread.interrupted();
        }
        assertThat(state.find(job.id()).orElseThrow().notificationPending()).isTrue();
        assertThat(pushCount()).isZero();
        notifier().deliverPending();
        assertThat(state.find(job.id()).orElseThrow().notificationPending()).isFalse();
        assertThat(pushCount()).isZero();
    }

    private AnalysisDatabaseCompletionNotifier notifier() {
        return new AnalysisDatabaseCompletionNotifier(state, store, events, streams, 3);
    }

    private AnalysisJob accept(int count) {
        owner = ownerFixture("DB전달");
        AnalysisJob job = acceptance.accept(owner.userId(), "AUTO", IntStream.range(0, count)
                .mapToObj(index -> AnalysisImage.pending("image-" + index, "ab".repeat(32))).toList());
        createdJobs.add(job);
        return job;
    }

    private void complete(AnalysisJob job, int index) {
        String requestId = job.analysisImages().get(index).requestId();
        completions.apply(EventEnvelope.create(new AnalysisCompletedPayload(clock.instant(), requestId, owner.userId(), owner.refrigeratorId(),
                "ai-" + job.id() + "-" + index, AnalysisStatus.FAILED, clock.instant(), null, new AnalysisCompletionFailure("MODEL_UNAVAILABLE", "분석 실패", true, null))));
    }

    private void enablePush() {
        preferences.create(userRepository.findById(owner.userId()).orElseThrow(), true);
        jdbcTemplate.update("INSERT INTO user_devices(endpoint,p256dh_key,auth_secret_encrypted,encryption_key_version,vapid_key_version,created_at,updated_at,user_id) "
                + "VALUES (?,?,?,'v1','v1',NOW(6),NOW(6),?)", "https://push.example.com/" + owner.userId(), "key", "secret", owner.userId());
    }

    private int pushCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM push_notifications WHERE user_id = ?", Integer.class, owner.userId());
    }

    private void advance() { clock.set(clock.instant().plusSeconds(5).toString()); }
}
