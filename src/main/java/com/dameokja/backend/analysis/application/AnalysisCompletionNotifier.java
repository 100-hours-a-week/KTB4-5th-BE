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
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AnalysisCompletionNotifier {
    private final AnalysisJobStore analysisJobStore;
    private final PushAnalysisCreationService pushes;
    private final AnalysisFinishedEventService analysisFinishedEventService;
    private final NotificationStreamService notificationStreamService;

    public void notifyFinished(AnalysisJob analysisJob, String requestId, AnalysisCompletion completion) {
        notifyImageFinished(analysisJob, requestId, completion);
        if (analysisJob.notificationPending()) {
            notifyCompletion(analysisJob);
        }
    }

    private void notifyImageFinished(AnalysisJob analysisJob, String requestId, AnalysisCompletion completion) {
        String objectKey = analysisJob.analysisImages().stream().filter(analysisImage -> requestId.equals(analysisImage.requestId()))
                .findFirst().orElseThrow().objectKey();
        try {
            if (!analysisJobStore.claimImageEvent(analysisJob.id(), requestId)) { return; }
        } catch (DataAccessException | CustomException exception) {
            throw new CustomException(AnalysisExceptionCode.CALLBACK_UNAVAILABLE);
        }
        try {
            analysisFinishedEventService.publishImageFinished(analysisJob.userId(), analysisJob.id(), objectKey, completion.status(),
                    analysisJob.analysisImages().size(), countImages(analysisJob, AnalysisStatus.COMPLETED), countImages(analysisJob, AnalysisStatus.FAILED));
        } catch (DataAccessException | CustomException exception) {
            releaseImageEvent(analysisJob.id(), requestId);
            throw new CustomException(AnalysisExceptionCode.CALLBACK_UNAVAILABLE);
        }
    }

    // 해제까지 실패하면 이벤트는 유실되지만, 작업 완료 알림은 따로 보내므로 사용자는 결과를 받는다.
    private void releaseImageEvent(String id, String requestId) {
        try {
            analysisJobStore.releaseImageEvent(id, requestId);
        } catch (DataAccessException | CustomException exception) {
            log.warn("이미지 완료 이벤트 선점을 해제하지 못했습니다. analysisJobId={} requestId={}", id, requestId, exception);
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

    // 경로를 먼저 저장해야 전송·확인 실패 후 연결 상태가 바뀌어도 SSE와 푸시를 함께 보내지 않는다.
    private void notifyCompletion(AnalysisJob analysisJob) {
        try {
            AnalysisJob notificationSelectedJob = analysisJob;
            if (notificationSelectedJob.notificationChannel() == null) {
                AnalysisNotificationChannel channel = notificationStreamService.isConnected(analysisJob.userId()) ? AnalysisNotificationChannel.SSE : AnalysisNotificationChannel.PUSH;
                notificationSelectedJob = analysisJobStore.selectNotificationChannel(analysisJob.id(), channel);
            }
            if (!notificationSelectedJob.notificationPending()) { return; }
            if (notificationSelectedJob.notificationChannel() == AnalysisNotificationChannel.SSE) {
                publishCompletion(notificationSelectedJob);
            } else {
                pushes.create(notificationSelectedJob.id(), notificationSelectedJob.userId(), notificationSelectedJob.expiresAt());
            }
            analysisJobStore.acknowledgeNotification(analysisJob.id());
        } catch (DataAccessException | CustomException exception) {
            throw new CustomException(AnalysisExceptionCode.CALLBACK_UNAVAILABLE);
        }
    }
}
