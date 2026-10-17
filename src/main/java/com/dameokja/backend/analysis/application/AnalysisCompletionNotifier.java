package com.dameokja.backend.analysis.application;

import com.dameokja.backend.analysis.domain.AnalysisCompletion;
import com.dameokja.backend.analysis.domain.AnalysisImage;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.domain.AnalysisNotificationChannel;
import com.dameokja.backend.analysis.domain.AnalysisStatus;
import com.dameokja.backend.analysis.exception.AnalysisExceptionCode;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStore;
import com.dameokja.backend.analysis.infrastructure.AnalysisNotificationRetryStore;
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

// 결과 저장 뒤 알림은 AI 콜백 재시도에 기대지 않는다. 먼저 재처리 예정에 올리고, 끝까지 보낸 뒤에만 내린다.
@Slf4j
@Component
public class AnalysisCompletionNotifier {
    private static final int RETRY_BATCH_SIZE = 20;
    private final AnalysisJobStore store;
    private final AnalysisNotificationRetryStore retries;
    private final PushAnalysisCreationService pushes;
    private final AnalysisFinishedEventService analysisFinishedEventService;
    private final NotificationStreamService notificationStreamService;
    private final Clock clock;
    private final Duration retryDelay;
    private final int sseRetries;

    public AnalysisCompletionNotifier(AnalysisJobStore store, AnalysisNotificationRetryStore retries, PushAnalysisCreationService pushes,
            AnalysisFinishedEventService analysisFinishedEventService, NotificationStreamService notificationStreamService, Clock clock,
            @Value("${analysis.notification.retry-delay:5s}") Duration retryDelay, @Value("${analysis.notification.sse-retries:3}") int sseRetries) {
        if (retryDelay == null || retryDelay.toMillis() < 1 || sseRetries < 0) {
            throw new IllegalArgumentException("알림 재시도 간격은 1밀리초 이상, SSE 재시도 횟수는 0 이상이어야 합니다.");
        }
        this.store = store;
        this.retries = retries;
        this.pushes = pushes;
        this.analysisFinishedEventService = analysisFinishedEventService;
        this.notificationStreamService = notificationStreamService;
        this.clock = clock;
        this.retryDelay = retryDelay;
        this.sseRetries = sseRetries;
    }

    // 재처리 예정에 올리지 못하면 결과만 저장되고 알림을 보낼 주체가 없으므로, 그때만 콜백 재시도를 요청한다.
    public void notifyFinished(AnalysisJob job, String requestId, AnalysisCompletion completion) {
        try {
            retries.schedule(job.id(), clock.instant().plus(retryDelay));
        } catch (DataAccessException exception) {
            throw new CustomException(AnalysisExceptionCode.CALLBACK_UNAVAILABLE);
        }
        deliver(job.id(), () -> publishImageEvent(job, requestId, completion));
    }

    public void retryDue() {
        for (String id : retries.claimDue(clock.instant(), retryDelay, RETRY_BATCH_SIZE)) {
            deliver(id, () -> store.find(id).ifPresent(this::publishUnsentImageEvents));
        }
    }

    private void deliver(String id, Runnable imageEvents) {
        try {
            imageEvents.run();
            Optional<AnalysisJob> job = store.find(id);
            if (job.isPresent() && job.get().notificationPending()) {
                notifyCompletion(job.get());
            }
            if (job.isEmpty() || delivered(store.find(id))) {
                retries.finish(id);
            }
        } catch (DataAccessException | CustomException exception) {
            log.warn("분석 완료 알림을 보내지 못해 다시 시도합니다. analysisJobId={}", id, exception);
        }
    }

    private boolean delivered(Optional<AnalysisJob> job) {
        return job.map(current -> !current.notificationPending() && current.analysisImages().stream()
                .allMatch(image -> image.completion() == null || current.imageEventPublished(image.requestId()))).orElse(true);
    }

    private void publishUnsentImageEvents(AnalysisJob job) {
        for (AnalysisImage image : job.analysisImages()) {
            if (image.completion() != null && !job.imageEventPublished(image.requestId())) {
                publishImageEvent(job, image.requestId(), image.completion());
            }
        }
    }

    // 먼저 발행 이력을 선점해 같은 결과가 여러 번 적용돼도 한 번만 보낸다. 보내지 못하면 이력을 되돌려 재처리가 다시 보낸다.
    private void publishImageEvent(AnalysisJob job, String requestId, AnalysisCompletion completion) {
        if (!store.claimImageEvent(job.id(), requestId)) { return; }
        String objectKey = job.analysisImages().stream().filter(image -> requestId.equals(image.requestId()))
                .findFirst().orElseThrow().objectKey();
        try {
            analysisFinishedEventService.publishImageFinished(job.userId(), job.id(), objectKey, completion.status(),
                    job.analysisImages().size(), countImages(job, AnalysisStatus.COMPLETED), countImages(job, AnalysisStatus.FAILED));
        } catch (DataAccessException | CustomException exception) {
            store.releaseImageEvent(job.id(), requestId);
            throw exception;
        }
    }

    // SSE가 연결되지 않았거나 발행이 실패하면 재시도하고, 재시도를 다 쓰면 푸시로 바꾼다. 경로를 저장한 뒤 보내 SSE와 푸시를 함께 보내지 않는다.
    private void notifyCompletion(AnalysisJob job) {
        AnalysisJob current = job;
        if (current.notificationChannel() != AnalysisNotificationChannel.PUSH) {
            if (publishedOverSse(current)) {
                store.acknowledgeNotification(current.id());
                return;
            }
            if (retries.recordSseMiss(current.id()) <= sseRetries) { return; }
            current = store.fallBackToPush(current.id());
        }
        if (current.notificationChannel() == null) {
            current = store.selectNotificationChannel(current.id(), AnalysisNotificationChannel.PUSH);
        }
        pushes.create(current.id(), current.userId(), current.expiresAt());
        store.acknowledgeNotification(current.id());
    }

    private boolean publishedOverSse(AnalysisJob job) {
        try {
            if (!notificationStreamService.isConnected(job.userId())) { return false; }
            AnalysisJob selected = job.notificationChannel() == null ? store.selectNotificationChannel(job.id(), AnalysisNotificationChannel.SSE) : job;
            publishCompletion(selected);
            return true;
        } catch (DataAccessException | CustomException exception) {
            log.warn("SSE 분석 완료 알림을 보내지 못했습니다. analysisJobId={}", job.id(), exception);
            return false;
        }
    }

    private int countImages(AnalysisJob job, AnalysisStatus status) {
        return (int) job.analysisImages().stream().filter(image -> image.completion() != null && image.completion().status() == status).count();
    }

    private void publishCompletion(AnalysisJob job) {
        int completedImageCount = countImages(job, AnalysisStatus.COMPLETED);
        int failedImageCount = countImages(job, AnalysisStatus.FAILED);
        AnalysisStatus status = failedImageCount == 0 ? AnalysisStatus.COMPLETED : completedImageCount == 0 ? AnalysisStatus.FAILED : AnalysisStatus.PARTIALLY_COMPLETED;
        analysisFinishedEventService.publish(job.userId(), job.id(), status, job.analysisImages().size(), completedImageCount, failedImageCount, job.expiresAt());
    }
}
