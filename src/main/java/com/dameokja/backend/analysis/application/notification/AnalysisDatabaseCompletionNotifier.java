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
    private final AnalysisNotificationState analysisNotificationState;
    private final AnalysisDatabasePendingNotificationStore analysisDatabasePendingNotificationStore;
    private final AnalysisFinishedEventService analysisFinishedEventService;
    private final NotificationStreamService notificationStreamService;
    private final int sseMaxAttempts;

    public AnalysisDatabaseCompletionNotifier(AnalysisNotificationState analysisNotificationState, AnalysisDatabasePendingNotificationStore analysisDatabasePendingNotificationStore,
            AnalysisFinishedEventService analysisFinishedEventService, NotificationStreamService notificationStreamService,
            @Value("${analysis.notification.sse-max-attempts:3}") int sseMaxAttempts) {
        if (sseMaxAttempts < 1) {
            throw new IllegalArgumentException("SSE 최대 시도 횟수는 1 이상이어야 합니다.");
        }
        this.analysisNotificationState = analysisNotificationState;
        this.analysisDatabasePendingNotificationStore = analysisDatabasePendingNotificationStore;
        this.analysisFinishedEventService = analysisFinishedEventService;
        this.notificationStreamService = notificationStreamService;
        this.sseMaxAttempts = sseMaxAttempts;
    }

    @Transactional(propagation = Propagation.NEVER)
    public synchronized void deliverPending() {
        for (int notificationIndex = 0; notificationIndex < PENDING_BATCH_SIZE && !Thread.currentThread().isInterrupted(); notificationIndex++) {
            Optional<Lease> pendingNotificationLease = analysisDatabasePendingNotificationStore.leaseNext();
            if (pendingNotificationLease.isEmpty()) { return; }
            Lease notificationLease = pendingNotificationLease.orElseThrow();
            deliver(notificationLease);
            analysisDatabasePendingNotificationStore.completeAttempt(notificationLease);
        }
    }

    private void deliver(Lease notificationLease) {
        try {
            analysisNotificationState.find(notificationLease.analysisJobId()).ifPresent(analysisJob -> publishImageEvents(analysisJob, notificationLease));
            analysisNotificationState.find(notificationLease.analysisJobId()).filter(AnalysisJob::notificationPending)
                    .ifPresent(analysisJob -> notifyCompletion(analysisJob, notificationLease));
        } catch (DataAccessException | CustomException exception) {
            log.warn("분석 완료 알림을 보내지 못해 다시 시도합니다. analysisJobId={}", notificationLease.analysisJobId(), exception);
        }
    }

    private void publishImageEvents(AnalysisJob analysisJob, Lease notificationLease) {
        if (analysisJob.notificationChannel() == AnalysisNotificationChannel.PUSH) { return; }
        for (AnalysisImage analysisImage : analysisJob.analysisImages()) {
            if (analysisImage.completion() == null || analysisJob.imageEventPublished(analysisImage.requestId())) { continue; }
            try {
                publishImageEvent(analysisJob, analysisImage, notificationLease);
            } catch (DataAccessException | CustomException exception) {
                log.warn("개별 이미지 SSE 발행 실패. analysisJobId={}, requestId={}", analysisJob.id(), analysisImage.requestId(), exception);
            }
        }
    }

    private void publishImageEvent(AnalysisJob analysisJob, AnalysisImage analysisImage, Lease notificationLease) {
        if (!analysisDatabasePendingNotificationStore.claimImageEventAttempt(notificationLease, analysisImage.requestId())) { return; }
        analysisFinishedEventService.publishImageFinished(analysisJob.userId(), analysisJob.id(), analysisImage.objectKey(), analysisImage.completion().status(),
                analysisJob.analysisImages().size(), countImages(analysisJob, AnalysisStatus.COMPLETED), countImages(analysisJob, AnalysisStatus.FAILED));
        analysisNotificationState.recordImageEventPublished(notificationLease, analysisImage.requestId());
    }

    private void notifyCompletion(AnalysisJob analysisJob, Lease notificationLease) {
        if (analysisJob.notificationChannel() == AnalysisNotificationChannel.PUSH) {
            analysisNotificationState.createPushNotification(notificationLease);
            return;
        }
        int sseAttemptNumber = analysisDatabasePendingNotificationStore.claimSseAttempt(notificationLease);
        if (sseAttemptNumber == 0) {
            handOffToPush(notificationLease);
            return;
        }
        try {
            if (notificationStreamService.isConnected(analysisJob.userId())) {
                analysisNotificationState.selectNotificationChannel(notificationLease, AnalysisNotificationChannel.SSE);
                publishCompletion(analysisJob);
                analysisNotificationState.acknowledgeNotification(notificationLease);
                return;
            }
        } catch (DataAccessException | CustomException exception) {
            log.warn("분석 완료 SSE 처리 실패. analysisJobId={}, attempt={}", analysisJob.id(), sseAttemptNumber, exception);
        }
        if (sseAttemptNumber >= sseMaxAttempts) { handOffToPush(notificationLease); }
    }

    private void handOffToPush(Lease notificationLease) {
        analysisNotificationState.fallBackToPush(notificationLease);
        analysisNotificationState.createPushNotification(notificationLease);
    }

    private int countImages(AnalysisJob analysisJob, AnalysisStatus analysisStatus) {
        return (int) analysisJob.analysisImages().stream()
                .filter(analysisImage -> analysisImage.completion() != null && analysisImage.completion().status() == analysisStatus).count();
    }

    private void publishCompletion(AnalysisJob analysisJob) {
        int completedImageCount = countImages(analysisJob, AnalysisStatus.COMPLETED);
        int failedImageCount = countImages(analysisJob, AnalysisStatus.FAILED);
        AnalysisStatus analysisStatus = failedImageCount == 0 ? AnalysisStatus.COMPLETED : completedImageCount == 0 ? AnalysisStatus.FAILED : AnalysisStatus.PARTIALLY_COMPLETED;
        analysisFinishedEventService.publish(analysisJob.userId(), analysisJob.id(), analysisStatus, analysisJob.analysisImages().size(),
                completedImageCount, failedImageCount, analysisJob.expiresAt());
    }
}
