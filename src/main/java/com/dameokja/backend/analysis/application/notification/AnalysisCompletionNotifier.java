package com.dameokja.backend.analysis.application.notification;

import com.dameokja.backend.analysis.domain.AnalysisCompletion;
import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.domain.AnalysisNotificationChannel;
import com.dameokja.backend.analysis.domain.AnalysisStatus;
import com.dameokja.backend.analysis.infrastructure.AnalysisPendingNotificationStore;
import com.dameokja.backend.analysis.infrastructure.AnalysisPendingNotificationStore.Lease;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.notification.application.AnalysisFinishedEventService;
import com.dameokja.backend.notification.application.NotificationStreamService;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

// 완료 결과와 함께 저장된 DB 알림 대기를 선점하고, 트랜잭션 밖에서 SSE를 발행한다.
@Slf4j
@Component
public class AnalysisCompletionNotifier {
    private static final int PENDING_BATCH_SIZE = 20;
    private final AnalysisNotificationState analysisNotificationState;
    private final AnalysisPendingNotificationStore analysisPendingNotificationStore;
    private final AnalysisFinishedEventService analysisFinishedEventService;
    private final NotificationStreamService notificationStreamService;
    private final int sseMaxAttempts;

    public AnalysisCompletionNotifier(AnalysisNotificationState analysisNotificationState, AnalysisPendingNotificationStore analysisPendingNotificationStore,
            AnalysisFinishedEventService analysisFinishedEventService, NotificationStreamService notificationStreamService,
            @Value("${analysis.notification.sse-max-attempts:3}") int sseMaxAttempts) {
        if (sseMaxAttempts < 1) {
            throw new IllegalArgumentException("SSE 최대 시도 횟수는 1 이상이어야 합니다.");
        }
        this.analysisNotificationState = analysisNotificationState;
        this.analysisPendingNotificationStore = analysisPendingNotificationStore;
        this.analysisFinishedEventService = analysisFinishedEventService;
        this.notificationStreamService = notificationStreamService;
        this.sseMaxAttempts = sseMaxAttempts;
    }

    public synchronized void deliverPending() {
        for (int notificationIndex = 0; notificationIndex < PENDING_BATCH_SIZE; notificationIndex++) {
            Optional<Lease> notificationLease = analysisPendingNotificationStore.leaseNext();
            if (notificationLease.isEmpty()) { return; }
            Lease lease = notificationLease.orElseThrow();
            deliver(lease);
            analysisPendingNotificationStore.completeAttempt(lease);
        }
    }

    private void deliver(Lease lease) {
        String analysisJobId = lease.analysisJobId();
        try {
            analysisNotificationState.find(analysisJobId).ifPresent(analysisJob -> publishUnsentImageEvents(analysisJob, lease));
            Optional<AnalysisJob> analysisJob = analysisNotificationState.find(analysisJobId);
            if (analysisJob.isPresent() && analysisJob.orElseThrow().notificationPending()) {
                notifyCompletion(analysisJob.orElseThrow(), lease);
            }
        } catch (DataAccessException | CustomException exception) {
            log.warn("분석 완료 알림을 보내지 못해 다시 시도합니다. analysisJobId={}", analysisJobId, exception);
        }
    }

    private void publishUnsentImageEvents(AnalysisJob analysisJob, Lease lease) {
        if (analysisJob.notificationChannel() == AnalysisNotificationChannel.PUSH) { return; }
        for (AnalysisImage analysisImage : analysisJob.analysisImages()) {
            if (analysisImage.completion() != null && !analysisJob.imageEventPublished(analysisImage.requestId())) {
                try {
                    publishImageEvent(analysisJob, analysisImage.requestId(), analysisImage.completion(), lease);
                } catch (DataAccessException | CustomException exception) {
                    log.warn("개별 이미지 SSE 발행 실패. analysisJobId={}, requestId={}", analysisJob.id(), analysisImage.requestId(), exception);
                }
            }
        }
    }

    // 발행 뒤 기록한다. 그 사이 종료되면 재발행될 수 있으므로 클라이언트는 저장된 결과를 기준으로 갱신한다.
    private void publishImageEvent(AnalysisJob analysisJob, String requestId, AnalysisCompletion completion, Lease lease) {
        if (!analysisPendingNotificationStore.claimImageEventAttempt(lease, requestId)) { return; }
        String objectKey = analysisJob.analysisImages().stream().filter(analysisImage -> requestId.equals(analysisImage.requestId()))
                .findFirst().orElseThrow().objectKey();
        analysisFinishedEventService.publishImageFinished(analysisJob.userId(), analysisJob.id(), objectKey, completion.status(),
                analysisJob.analysisImages().size(), countImages(analysisJob, AnalysisStatus.COMPLETED), countImages(analysisJob, AnalysisStatus.FAILED));
        analysisNotificationState.recordImageEventPublished(lease, requestId);
    }

    private void notifyCompletion(AnalysisJob analysisJob, Lease lease) {
        if (analysisJob.notificationChannel() == AnalysisNotificationChannel.PUSH) {
            analysisNotificationState.createPushNotification(lease);
            return;
        }
        int attemptNumber = analysisPendingNotificationStore.claimSseAttempt(lease);
        if (attemptNumber == 0) {
            handOffToPush(lease);
            return;
        }
        boolean connected;
        try {
            connected = notificationStreamService.isConnected(analysisJob.userId());
        } catch (DataAccessException | CustomException exception) {
            handleSseFailure(lease, attemptNumber, exception);
            return;
        }
        if (!connected) {
            handOffToPush(lease);
            return;
        }
        publishCompletionOrRetry(analysisJob, lease, attemptNumber);
    }

    private void publishCompletionOrRetry(AnalysisJob analysisJob, Lease lease, int attemptNumber) {
        try {
            analysisNotificationState.selectNotificationChannel(lease, AnalysisNotificationChannel.SSE);
            publishCompletion(analysisJob);
            analysisNotificationState.acknowledgeNotification(lease);
        } catch (DataAccessException | CustomException exception) {
            handleSseFailure(lease, attemptNumber, exception);
        }
    }

    private void handleSseFailure(Lease lease, int attemptNumber, RuntimeException exception) {
        log.warn("분석 완료 SSE 처리 실패. analysisJobId={}, attempt={}", lease.analysisJobId(), attemptNumber, exception);
        if (attemptNumber >= sseMaxAttempts) { handOffToPush(lease); }
    }

    private void handOffToPush(Lease lease) {
        analysisNotificationState.fallBackToPush(lease);
        analysisNotificationState.createPushNotification(lease);
    }

    private int countImages(AnalysisJob analysisJob, AnalysisStatus status) {
        return (int) analysisJob.analysisImages().stream().filter(analysisImage -> analysisImage.completion() != null && analysisImage.completion().status() == status).count();
    }

    private void publishCompletion(AnalysisJob analysisJob) {
        int completedImageCount = countImages(analysisJob, AnalysisStatus.COMPLETED);
        int failedImageCount = countImages(analysisJob, AnalysisStatus.FAILED);
        AnalysisStatus status = failedImageCount == 0 ? AnalysisStatus.COMPLETED : completedImageCount == 0 ? AnalysisStatus.FAILED : AnalysisStatus.PARTIALLY_COMPLETED;
        analysisFinishedEventService.publish(analysisJob.userId(), analysisJob.id(), status, analysisJob.analysisImages().size(), completedImageCount, failedImageCount, analysisJob.expiresAt());
    }
}
