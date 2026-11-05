package com.dameokja.backend.analysis.application.notification;

import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.domain.AnalysisNotificationChannel;
import com.dameokja.backend.analysis.domain.AnalysisStatus;
import com.dameokja.backend.analysis.infrastructure.AnalysisDatabasePendingNotificationStore;
import com.dameokja.backend.analysis.infrastructure.AnalysisDatabasePendingNotificationStore.Lease;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.notification.application.AnalysisFinishedEventService;
import com.dameokja.backend.notification.application.NotificationStreamService;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
public class AnalysisDatabaseCompletionNotifier {
    private static final int PENDING_BATCH_SIZE = 20;
    private final AnalysisNotificationState state;
    private final AnalysisDatabasePendingNotificationStore store;
    private final AnalysisFinishedEventService events;
    private final NotificationStreamService streams;
    private final int sseMaxAttempts;

    public AnalysisDatabaseCompletionNotifier(AnalysisNotificationState state, AnalysisDatabasePendingNotificationStore store,
            AnalysisFinishedEventService events, NotificationStreamService streams,
            @Value("${analysis.notification.sse-max-attempts:3}") int sseMaxAttempts) {
        if (sseMaxAttempts < 1) {
            throw new IllegalArgumentException("SSE 최대 시도 횟수는 1 이상이어야 합니다.");
        }
        this.state = state;
        this.store = store;
        this.events = events;
        this.streams = streams;
        this.sseMaxAttempts = sseMaxAttempts;
    }

    @Transactional(propagation = Propagation.NEVER)
    public synchronized void deliverPending() {
        for (int index = 0; index < PENDING_BATCH_SIZE && !Thread.currentThread().isInterrupted(); index++) {
            Optional<Lease> next = store.leaseNext();
            if (next.isEmpty()) { return; }
            Lease lease = next.orElseThrow();
            deliver(lease);
            store.completeAttempt(lease);
        }
    }

    private void deliver(Lease lease) {
        try {
            state.find(lease.analysisJobId()).ifPresent(job -> publishImageEvents(job, lease));
            state.find(lease.analysisJobId()).filter(AnalysisJob::notificationPending)
                    .ifPresent(job -> notifyCompletion(job, lease));
        } catch (DataAccessException | CustomException exception) {
            log.warn("분석 완료 알림을 보내지 못해 다시 시도합니다. analysisJobId={}", lease.analysisJobId(), exception);
        }
    }

    private void publishImageEvents(AnalysisJob job, Lease lease) {
        if (job.notificationChannel() == AnalysisNotificationChannel.PUSH) { return; }
        for (AnalysisImage image : job.analysisImages()) {
            if (image.completion() == null || job.imageEventPublished(image.requestId())) { continue; }
            try {
                publishImageEvent(job, image, lease);
            } catch (DataAccessException | CustomException exception) {
                log.warn("개별 이미지 SSE 발행 실패. analysisJobId={}, requestId={}", job.id(), image.requestId(), exception);
            }
        }
    }

    private void publishImageEvent(AnalysisJob job, AnalysisImage image, Lease lease) {
        if (!store.claimImageEventAttempt(lease, image.requestId())) { return; }
        events.publishImageFinished(job.userId(), job.id(), image.objectKey(), image.completion().status(),
                job.analysisImages().size(), countImages(job, AnalysisStatus.COMPLETED), countImages(job, AnalysisStatus.FAILED));
        state.recordImageEventPublished(lease, image.requestId());
    }

    private void notifyCompletion(AnalysisJob job, Lease lease) {
        if (job.notificationChannel() == AnalysisNotificationChannel.PUSH) {
            state.createPushNotification(lease);
            return;
        }
        int attempt = store.claimSseAttempt(lease);
        if (attempt == 0) {
            handOffToPush(lease);
            return;
        }
        try {
            if (streams.isConnected(job.userId())) {
                state.selectNotificationChannel(lease, AnalysisNotificationChannel.SSE);
                publishCompletion(job);
                state.acknowledgeNotification(lease);
                return;
            }
        } catch (DataAccessException | CustomException exception) {
            log.warn("분석 완료 SSE 처리 실패. analysisJobId={}, attempt={}", job.id(), attempt, exception);
        }
        if (attempt >= sseMaxAttempts) { handOffToPush(lease); }
    }

    private void handOffToPush(Lease lease) {
        state.fallBackToPush(lease);
        state.createPushNotification(lease);
    }

    private int countImages(AnalysisJob job, AnalysisStatus status) {
        return (int) job.analysisImages().stream()
                .filter(image -> image.completion() != null && image.completion().status() == status).count();
    }

    private void publishCompletion(AnalysisJob job) {
        int completed = countImages(job, AnalysisStatus.COMPLETED);
        int failed = countImages(job, AnalysisStatus.FAILED);
        AnalysisStatus status = failed == 0 ? AnalysisStatus.COMPLETED : completed == 0 ? AnalysisStatus.FAILED : AnalysisStatus.PARTIALLY_COMPLETED;
        events.publish(job.userId(), job.id(), status, job.analysisImages().size(), completed, failed, job.expiresAt());
    }
}
