package com.dameokja.backend.notification.application;

import com.dameokja.backend.analysis.domain.AnalysisStatus;
import com.dameokja.backend.notification.domain.AnalysisFinished;
import com.dameokja.backend.notification.domain.AnalysisImageFinished;
import com.dameokja.backend.notification.infrastructure.AnalysisFinishedRedisPubSub;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AnalysisFinishedEventService {
    private final AnalysisFinishedRedisPubSub pubSub;

    public void publish(Long userId, String analysisId, AnalysisStatus status, int totalImageCount,
                        int completedImageCount, int failedImageCount, OffsetDateTime expiresAt) {
        pubSub.publish(AnalysisFinished.of(userId, analysisId, status, totalImageCount, completedImageCount, failedImageCount, expiresAt));
    }

    public void publishImageFinished(Long userId, String analysisId, String imageObjectKey, AnalysisStatus imageStatus,
                                     int totalImageCount, int completedImageCount, int failedImageCount) {
        pubSub.publish(new AnalysisImageFinished(userId, new AnalysisImageFinished.Payload(
                analysisId, imageObjectKey, imageStatus, totalImageCount, completedImageCount, failedImageCount, null)));
    }
}
