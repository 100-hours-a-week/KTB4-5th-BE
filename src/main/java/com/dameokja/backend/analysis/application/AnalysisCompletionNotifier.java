package com.dameokja.backend.analysis.application;

import com.dameokja.backend.analysis.domain.AnalysisCompletion;
import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.domain.AnalysisNotificationChannel;
import com.dameokja.backend.analysis.domain.AnalysisStatus;
import com.dameokja.backend.analysis.exception.AnalysisExceptionCode;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStore;
import com.dameokja.backend.analysis.infrastructure.AnalysisPendingNotificationStore;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.notification.application.AnalysisFinishedEventService;
import com.dameokja.backend.notification.application.NotificationStreamService;
import com.dameokja.backend.push.application.PushAnalysisCreationService;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

// 결과 저장 뒤 알림은 AI 콜백 재시도에 기대지 않는다. 먼저 알림 대기 목록에 올리고, 끝까지 보낸 뒤에만 내린다.
@Slf4j
@Component
public class AnalysisCompletionNotifier {
    private static final int PENDING_BATCH_SIZE = 20;
    private final AnalysisJobStore analysisJobStore;
    private final AnalysisPendingNotificationStore analysisPendingNotificationStore;
    private final PushAnalysisCreationService pushes;
    private final AnalysisFinishedEventService analysisFinishedEventService;
    private final NotificationStreamService notificationStreamService;
    private final Clock clock;
    private final Duration retryDelay;
    private final int sseRetries;

    public AnalysisCompletionNotifier(AnalysisJobStore analysisJobStore, AnalysisPendingNotificationStore analysisPendingNotificationStore, PushAnalysisCreationService pushes,
            AnalysisFinishedEventService analysisFinishedEventService, NotificationStreamService notificationStreamService, Clock clock,
            @Value("${analysis.notification.retry-delay:5s}") Duration retryDelay, @Value("${analysis.notification.sse-retries:3}") int sseRetries) {
        if (retryDelay == null || retryDelay.toMillis() < 1 || sseRetries < 0) {
            throw new IllegalArgumentException("알림 재시도 간격은 1밀리초 이상, SSE 재시도 횟수는 0 이상이어야 합니다.");
        }
        this.analysisJobStore = analysisJobStore;
        this.analysisPendingNotificationStore = analysisPendingNotificationStore;
        this.pushes = pushes;
        this.analysisFinishedEventService = analysisFinishedEventService;
        this.notificationStreamService = notificationStreamService;
        this.clock = clock;
        this.retryDelay = retryDelay;
        this.sseRetries = sseRetries;
    }

    // 알림 대기 목록에 올리지 못하면 결과만 저장되고 알림을 보낼 주체가 없다. 그래서 전달 행을 닫기 전에 올리고,
    // 실패하면 전달 행을 열어 둔 채 콜백 재시도나 기한 확인이 다시 처리하게 한다.
    public void addPending(String analysisJobId) {
        try {
            analysisPendingNotificationStore.schedule(analysisJobId, clock.instant().plus(retryDelay));
        } catch (DataAccessException exception) {
            throw new CustomException(AnalysisExceptionCode.CALLBACK_UNAVAILABLE);
        }
    }

    public void notifyFinished(AnalysisJob analysisJob, String requestId, AnalysisCompletion completion) {
        deliver(analysisJob.id(), () -> publishImageEvent(analysisJob, requestId, completion));
    }

    public void deliverPending() {
        for (String id : analysisPendingNotificationStore.claimDue(clock.instant(), retryDelay, PENDING_BATCH_SIZE)) {
            deliver(id, () -> analysisJobStore.find(id).ifPresent(this::publishUnsentImageEvents));
        }
    }

    private void deliver(String id, Runnable publishImageEvents) {
        try {
            publishImageEvents.run();
            Optional<AnalysisJob> analysisJob = analysisJobStore.find(id);
            if (analysisJob.isPresent() && analysisJob.get().notificationPending()) {
                notifyCompletion(analysisJob.get());
            }
            if (analysisJob.isEmpty() || delivered(analysisJobStore.find(id))) {
                analysisPendingNotificationStore.finish(id);
            }
        } catch (DataAccessException | CustomException exception) {
            log.warn("분석 완료 알림을 보내지 못해 다시 시도합니다. analysisJobId={}", id, exception);
        }
    }

    private boolean delivered(Optional<AnalysisJob> analysisJob) {
        return analysisJob.map(latestJob -> !latestJob.notificationPending() && latestJob.analysisImages().stream()
                .allMatch(analysisImage -> analysisImage.completion() == null || latestJob.imageEventPublished(analysisImage.requestId()))).orElse(true);
    }

    private void publishUnsentImageEvents(AnalysisJob analysisJob) {
        for (AnalysisImage analysisImage : analysisJob.analysisImages()) {
            if (analysisImage.completion() != null && !analysisJob.imageEventPublished(analysisImage.requestId())) {
                publishImageEvent(analysisJob, analysisImage.requestId(), analysisImage.completion());
            }
        }
    }

    // 먼저 발행 이력을 선점해 같은 결과가 여러 번 적용돼도 한 번만 보낸다. 보내지 못하면 이력을 되돌려 재처리가 다시 보낸다.
    private void publishImageEvent(AnalysisJob analysisJob, String requestId, AnalysisCompletion completion) {
        if (!analysisJobStore.claimImageEvent(analysisJob.id(), requestId)) { return; }
        String objectKey = analysisJob.analysisImages().stream().filter(analysisImage -> requestId.equals(analysisImage.requestId()))
                .findFirst().orElseThrow().objectKey();
        try {
            analysisFinishedEventService.publishImageFinished(analysisJob.userId(), analysisJob.id(), objectKey, completion.status(),
                    analysisJob.analysisImages().size(), countImages(analysisJob, AnalysisStatus.COMPLETED), countImages(analysisJob, AnalysisStatus.FAILED));
        } catch (DataAccessException | CustomException exception) {
            analysisJobStore.releaseImageEvent(analysisJob.id(), requestId);
            throw exception;
        }
    }

    // SSE가 연결되지 않았거나 발행이 실패하면 재시도하고, 재시도를 다 쓰면 푸시로 바꾼다. 경로를 저장한 뒤 보내 SSE와 푸시를 함께 보내지 않는다.
    private void notifyCompletion(AnalysisJob analysisJob) {
        AnalysisJob latestJob = analysisJob;
        if (latestJob.notificationChannel() != AnalysisNotificationChannel.PUSH) {
            if (publishedOverSse(latestJob)) {
                analysisJobStore.acknowledgeNotification(latestJob.id());
                return;
            }
            if (analysisPendingNotificationStore.recordSseMiss(latestJob.id()) <= sseRetries) { return; }
            latestJob = analysisJobStore.fallBackToPush(latestJob.id());
        }
        if (latestJob.notificationChannel() == null) {
            latestJob = analysisJobStore.selectNotificationChannel(latestJob.id(), AnalysisNotificationChannel.PUSH);
        }
        pushes.create(latestJob.id(), latestJob.userId(), latestJob.expiresAt());
        analysisJobStore.acknowledgeNotification(latestJob.id());
    }

    private boolean publishedOverSse(AnalysisJob analysisJob) {
        try {
            if (!notificationStreamService.isConnected(analysisJob.userId())) { return false; }
            AnalysisJob channelSelectedJob = analysisJob.notificationChannel() == null ? analysisJobStore.selectNotificationChannel(analysisJob.id(), AnalysisNotificationChannel.SSE) : analysisJob;
            publishCompletion(channelSelectedJob);
            return true;
        } catch (DataAccessException | CustomException exception) {
            log.warn("SSE 분석 완료 알림을 보내지 못했습니다. analysisJobId={}", analysisJob.id(), exception);
            return false;
        }
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
