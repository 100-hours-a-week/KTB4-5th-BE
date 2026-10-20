package com.dameokja.backend.analysis.presentation.response;

import com.dameokja.backend.analysis.application.job.AnalysisImageResult;
import com.dameokja.backend.analysis.application.job.AnalysisJobResult;
import com.dameokja.backend.analysis.domain.AnalysisJob;
import com.dameokja.backend.analysis.domain.AnalysisStatus;
import com.dameokja.backend.analysis.domain.RecognitionStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

public record AnalysisJobSummaryResponse(String analysisId, AnalysisStatus status, OffsetDateTime submittedAt,
        @Schema(description = "모든 이미지 종료 전 null. 모든 이미지가 종료된 시점부터 기본 7일 후", nullable = true) OffsetDateTime expiresAt,
        int totalImageCount, int completedImageCount, int failedImageCount, List<Image> images) {
    public static AnalysisJobSummaryResponse from(AnalysisJobResult analysisJobResult) {
        AnalysisJob job = analysisJobResult.analysisJob();
        List<AnalysisImageResult> images = analysisJobResult.analysisImageResults();
        return new AnalysisJobSummaryResponse(job.id(), analysisJobResult.status(), job.submittedAt(), job.expiresAt(), images.size(),
                count(images, AnalysisStatus.COMPLETED), count(images, AnalysisStatus.FAILED), images.stream().map(Image::from).toList());
    }

    private static int count(List<AnalysisImageResult> images, AnalysisStatus status) {
        return (int) images.stream().filter(image -> image.status() == status).count();
    }

    public record Image(String imageObjectKey, AnalysisStatus status,
            @Schema(description = "성공한 이미지의 항목 상태 집계. 인식 항목이 없으면 UNRECOGNIZED, 실패·진행 중이면 null", nullable = true)
            RecognitionStatus recognitionStatus,
            @Schema(description = "성공한 이미지의 인식 항목 수. 실패하면 0, 진행 중이면 null", nullable = true) Integer itemCount) {
        private static Image from(AnalysisImageResult image) {
            Integer itemCount = image.status().isInProgress() ? null : image.items().size();
            return new Image(image.imageObjectKey(), image.status(), image.recognitionStatus(), itemCount);
        }
    }
}
