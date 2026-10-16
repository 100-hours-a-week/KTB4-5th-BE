package com.dameokja.backend.analysis.application;

import com.dameokja.backend.analysis.domain.AnalysisCompletion;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.domain.AnalysisNotificationChannel;
import com.dameokja.backend.analysis.domain.AnalysisStatus;
import com.dameokja.backend.analysis.exception.AnalysisExceptionCode;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStore;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.exception.GlobalExceptionCode;
import com.dameokja.backend.notification.application.AnalysisFinishedEventService;
import com.dameokja.backend.notification.application.NotificationStreamService;
import com.dameokja.backend.push.application.PushAnalysisCreationService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AnalysisCallbackService {
    private final AnalysisJobStore store;
    private final AnalysisCompletionValidator validator;
    private final PushAnalysisCreationService pushes;
    private final AnalysisFinishedEventService events;
    private final NotificationStreamService connections;

    public void receive(String analysisId, AnalysisCompletion completion) {
        try {
            validator.validate(completion);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new CustomException(GlobalExceptionCode.BAD_REQUEST);
        }
        AnalysisJob job;
        try {
            job = store.complete(analysisId, completion);
        } catch (CustomException exception) {
            if (exception.getExceptionCode() == AnalysisExceptionCode.RESULT_CONFLICT) {
                throw exception;
            }
            throw new CustomException(AnalysisExceptionCode.CALLBACK_UNAVAILABLE);
        } catch (DataAccessException exception) {
            throw new CustomException(AnalysisExceptionCode.CALLBACK_UNAVAILABLE);
        }
        notifyImageFinished(job, analysisId, completion);
        if (job.notificationPending()) {
            notifyCompletion(job);
        }
    }

    private void notifyImageFinished(AnalysisJob job, String analysisId, AnalysisCompletion completion) {
        String objectKey = job.analysisImages().stream().filter(image -> analysisId.equals(image.aiAnalysisId()))
                .findFirst().orElseThrow().objectKey();
        try {
            events.publishImageFinished(job.userId(), job.id(), objectKey, completion.status(),
                    job.analysisImages().size(), countImages(job, AnalysisStatus.COMPLETED), countImages(job, AnalysisStatus.FAILED));
        } catch (DataAccessException | CustomException exception) {
            throw new CustomException(AnalysisExceptionCode.CALLBACK_UNAVAILABLE);
        }
    }

    private int countImages(AnalysisJob job, AnalysisStatus status) {
        return (int) job.analysisImages().stream().filter(image -> image.completion() != null && image.completion().status() == status).count();
    }

    private void publishCompletion(AnalysisJob job) {
        int completed = countImages(job, AnalysisStatus.COMPLETED);
        int failed = countImages(job, AnalysisStatus.FAILED);
        AnalysisStatus status = failed == 0 ? AnalysisStatus.COMPLETED : completed == 0 ? AnalysisStatus.FAILED : AnalysisStatus.PARTIALLY_COMPLETED;
        events.publish(job.userId(), job.id(), status, job.analysisImages().size(), completed, failed, job.expiresAt());
    }

    // 경로를 먼저 저장해야 전송·확인 실패 후 연결 상태가 바뀌어도 SSE와 푸시를 함께 보내지 않는다.
    private void notifyCompletion(AnalysisJob job) {
        try {
            AnalysisJob selected = job;
            if (selected.notificationChannel() == null) {
                AnalysisNotificationChannel channel = connections.isConnected(job.userId()) ? AnalysisNotificationChannel.SSE : AnalysisNotificationChannel.PUSH;
                selected = store.selectNotificationChannel(job.id(), channel);
            }
            if (!selected.notificationPending()) { return; }
            if (selected.notificationChannel() == AnalysisNotificationChannel.SSE) {
                publishCompletion(selected);
            } else {
                pushes.create(selected.id(), selected.userId(), selected.expiresAt());
            }
            store.acknowledgeNotification(job.id());
        } catch (DataAccessException | CustomException exception) {
            throw new CustomException(AnalysisExceptionCode.CALLBACK_UNAVAILABLE);
        }
    }
}
