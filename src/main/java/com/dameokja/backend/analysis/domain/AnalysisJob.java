package com.dameokja.backend.analysis.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

public record AnalysisJob(String id, Long userId, String inputHint, List<AnalysisImage> analysisImages,
        OffsetDateTime submittedAt, OffsetDateTime expiresAt, Boolean notificationPending,
        @JsonInclude(JsonInclude.Include.NON_NULL) AnalysisNotificationChannel notificationChannel) {
    public AnalysisJob {
        analysisImages = List.copyOf(analysisImages);
        // 배포 전에 저장된 작업에는 알림 대기 필드가 없다.
        notificationPending = Boolean.TRUE.equals(notificationPending);
    }

    public AnalysisJob(String id, Long userId, String inputHint, List<AnalysisImage> analysisImages,
            OffsetDateTime submittedAt, OffsetDateTime expiresAt, Boolean notificationPending) {
        this(id, userId, inputHint, analysisImages, submittedAt, expiresAt, notificationPending, null);
    }

    public AnalysisJob selectNotificationChannel(AnalysisNotificationChannel channel) {
        if (!notificationPending || notificationChannel != null) { return this; }
        return new AnalysisJob(id, userId, inputHint, analysisImages, submittedAt, expiresAt, true, Objects.requireNonNull(channel));
    }

    public AnalysisJob register(String requestId, String analysisId) {
        if (analysisImages.stream().noneMatch(analysisImage -> analysisImage.requestId().equals(requestId))) {
            throw new IllegalArgumentException("작업에 속하지 않은 이미지 요청입니다.");
        }
        if (analysisImages.stream().anyMatch(image -> analysisId != null && analysisId.equals(image.aiAnalysisId()) && !requestId.equals(image.requestId()))) {
            throw new IllegalStateException("다른 이미지에 접수된 AI 작업 ID입니다.");
        }
        List<AnalysisImage> analysisImages = this.analysisImages.stream()
                .map(analysisImage -> analysisImage.requestId().equals(requestId) ? analysisImage.register(analysisId) : analysisImage).toList();
        return new AnalysisJob(id, userId, inputHint, analysisImages, submittedAt, expiresAt, notificationPending, notificationChannel);
    }

    public AnalysisJob complete(String analysisId, AnalysisCompletion completion, OffsetDateTime resultExpiresAt) {
        if (analysisId == null || analysisImages.stream().noneMatch(image -> analysisId.equals(image.aiAnalysisId()))) {
            throw new IllegalArgumentException("작업에 속하지 않은 AI 분석입니다.");
        }
        List<AnalysisImage> images = analysisImages.stream()
                .map(image -> analysisId.equals(image.aiAnalysisId()) ? image.complete(completion) : image).toList();
        boolean newlyCompleted = images.stream().allMatch(image -> image.completion() != null)
                && analysisImages.stream().anyMatch(image -> image.completion() == null);
        OffsetDateTime deadline = newlyCompleted ? Objects.requireNonNull(resultExpiresAt) : expiresAt;
        return new AnalysisJob(id, userId, inputHint, images, submittedAt, deadline, notificationPending || newlyCompleted, notificationChannel);
    }

    public AnalysisJob acknowledgeNotification() {
        return new AnalysisJob(id, userId, inputHint, analysisImages, submittedAt, expiresAt, false, notificationChannel);
    }
}
