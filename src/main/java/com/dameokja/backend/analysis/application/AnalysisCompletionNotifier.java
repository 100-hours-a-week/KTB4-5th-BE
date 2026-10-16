package com.dameokja.backend.analysis.application;

import com.dameokja.backend.analysis.domain.AnalysisCompletion;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.domain.AnalysisNotificationChannel;
import com.dameokja.backend.analysis.domain.AnalysisStatus;
import com.dameokja.backend.analysis.exception.AnalysisExceptionCode;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStore;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.notification.application.AnalysisFinishedEventService;
import com.dameokja.backend.notification.application.NotificationStreamService;
import com.dameokja.backend.push.application.PushAnalysisCreationService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AnalysisCompletionNotifier {
    private final AnalysisJobStore store;
    private final PushAnalysisCreationService pushes;
    private final AnalysisFinishedEventService analysisFinishedEventService;
    private final NotificationStreamService notificationStreamService;

    public void notifyFinished(AnalysisJob job, String requestId, AnalysisCompletion completion) {
        notifyImageFinished(job, requestId, completion);
        if (job.notificationPending()) {
            notifyCompletion(job);
        }
    }

    private void notifyImageFinished(AnalysisJob job, String requestId, AnalysisCompletion completion) {
        String objectKey = job.analysisImages().stream().filter(image -> requestId.equals(image.requestId()))
                .findFirst().orElseThrow().objectKey();
        try {
            analysisFinishedEventService.publishImageFinished(job.userId(), job.id(), objectKey, completion.status(),
                    job.analysisImages().size(), countImages(job, AnalysisStatus.COMPLETED), countImages(job, AnalysisStatus.FAILED));
        } catch (DataAccessException | CustomException exception) {
            throw new CustomException(AnalysisExceptionCode.CALLBACK_UNAVAILABLE);
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

    // 경로를 먼저 저장해야 전송·확인 실패 후 연결 상태가 바뀌어도 SSE와 푸시를 함께 보내지 않는다.
    private void notifyCompletion(AnalysisJob job) {
        try {
            AnalysisJob notificationSelectedJob = job;
            if (notificationSelectedJob.notificationChannel() == null) {
                AnalysisNotificationChannel channel = notificationStreamService.isConnected(job.userId()) ? AnalysisNotificationChannel.SSE : AnalysisNotificationChannel.PUSH;
                notificationSelectedJob = store.selectNotificationChannel(job.id(), channel);
            }
            if (!notificationSelectedJob.notificationPending()) { return; }
            if (notificationSelectedJob.notificationChannel() == AnalysisNotificationChannel.SSE) {
                publishCompletion(notificationSelectedJob);
            } else {
                pushes.create(notificationSelectedJob.id(), notificationSelectedJob.userId(), notificationSelectedJob.expiresAt());
            }
            store.acknowledgeNotification(job.id());
        } catch (DataAccessException | CustomException exception) {
            throw new CustomException(AnalysisExceptionCode.CALLBACK_UNAVAILABLE);
        }
    }
}
