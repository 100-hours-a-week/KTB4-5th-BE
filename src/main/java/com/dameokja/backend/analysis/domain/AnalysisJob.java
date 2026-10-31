package com.dameokja.backend.analysis.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

public record AnalysisJob(String id, Long userId, String inputHint, List<AnalysisImage> analysisImages,
        OffsetDateTime submittedAt, OffsetDateTime expiresAt, Boolean notificationPending,
        @JsonInclude(JsonInclude.Include.NON_NULL) AnalysisNotificationChannel notificationChannel,
        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> publishedImageEvents) {
    public AnalysisJob {
        analysisImages = List.copyOf(analysisImages);
        notificationPending = Boolean.TRUE.equals(notificationPending);
        publishedImageEvents = publishedImageEvents == null ? List.of() : List.copyOf(publishedImageEvents);
    }

    public AnalysisJob(String id, Long userId, String inputHint, List<AnalysisImage> analysisImages,
            OffsetDateTime submittedAt, OffsetDateTime expiresAt, Boolean notificationPending) {
        this(id, userId, inputHint, analysisImages, submittedAt, expiresAt, notificationPending, null, List.of());
    }

    public AnalysisJob(String id, Long userId, String inputHint, List<AnalysisImage> analysisImages,
            OffsetDateTime submittedAt, OffsetDateTime expiresAt, Boolean notificationPending, AnalysisNotificationChannel notificationChannel) {
        this(id, userId, inputHint, analysisImages, submittedAt, expiresAt, notificationPending, notificationChannel, List.of());
    }

    public AnalysisJob selectNotificationChannel(AnalysisNotificationChannel channel) {
        if (!notificationPending || notificationChannel != null) { return this; }
        return new AnalysisJob(id, userId, inputHint, analysisImages, submittedAt, expiresAt, true, Objects.requireNonNull(channel), publishedImageEvents);
    }

    // SSE로 끝내 알리지 못하면 푸시로 바꾼다. 이미 푸시면 그대로 둔다.
    public AnalysisJob fallBackToPush() {
        if (!notificationPending || notificationChannel == AnalysisNotificationChannel.PUSH) { return this; }
        return new AnalysisJob(id, userId, inputHint, analysisImages, submittedAt, expiresAt, true, AnalysisNotificationChannel.PUSH, publishedImageEvents);
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
        return new AnalysisJob(id, userId, inputHint, analysisImages, submittedAt, expiresAt, notificationPending, notificationChannel, publishedImageEvents);
    }

    public AnalysisJob complete(String analysisId, AnalysisCompletion completion, OffsetDateTime resultExpiresAt) {
        if (analysisId == null || analysisImages.stream().noneMatch(image -> analysisId.equals(image.aiAnalysisId()))) {
            throw new IllegalArgumentException("작업에 속하지 않은 AI 분석입니다.");
        }
        return withImages(analysisImages.stream()
                .map(image -> analysisId.equals(image.aiAnalysisId()) ? image.complete(completion) : image).toList(), resultExpiresAt);
    }

    public AnalysisJob fail(String requestId, AnalysisCompletion failure, OffsetDateTime resultExpiresAt) {
        if (analysisImages.stream().noneMatch(image -> image.requestId().equals(requestId))) {
            throw new IllegalArgumentException("작업에 속하지 않은 이미지 요청입니다.");
        }
        return withImages(analysisImages.stream()
                .map(image -> image.requestId().equals(requestId) ? image.fail(failure) : image).toList(), resultExpiresAt);
    }

    private AnalysisJob withImages(List<AnalysisImage> images, OffsetDateTime resultExpiresAt) {
        boolean newlyCompleted = images.stream().allMatch(image -> image.completion() != null)
                && analysisImages.stream().anyMatch(image -> image.completion() == null);
        OffsetDateTime deadline = newlyCompleted ? Objects.requireNonNull(resultExpiresAt) : expiresAt;
        return new AnalysisJob(id, userId, inputHint, images, submittedAt, deadline, notificationPending || newlyCompleted, notificationChannel, publishedImageEvents);
    }

    public boolean finished() { return analysisImages.stream().allMatch(image -> image.completion() != null); }

    public AnalysisJob acknowledgeNotification() {
        return new AnalysisJob(id, userId, inputHint, analysisImages, submittedAt, expiresAt, false, notificationChannel, publishedImageEvents);
    }

    // 이미 발행을 기록한 이미지 이벤트는 중복 완료 이벤트 적용으로 다시 발행하지 않는다.
    public boolean imageEventPublished(String requestId) { return publishedImageEvents.contains(requestId); }

    public AnalysisJob publishImageEvent(String requestId) {
        if (analysisImages.stream().noneMatch(image -> image.requestId().equals(requestId) && image.completion() != null)) {
            throw new IllegalArgumentException("종료되지 않은 이미지의 완료 이벤트입니다.");
        }
        if (imageEventPublished(requestId)) { return this; }
        return new AnalysisJob(id, userId, inputHint, analysisImages, submittedAt, expiresAt, notificationPending, notificationChannel,
                Stream.concat(publishedImageEvents.stream(), Stream.of(requestId)).toList());
    }


}
