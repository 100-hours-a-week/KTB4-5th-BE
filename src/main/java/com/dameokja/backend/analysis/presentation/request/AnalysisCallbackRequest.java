package com.dameokja.backend.analysis.presentation.request;

import com.dameokja.backend.analysis.domain.AiAnalysisIdentifiers;
import com.dameokja.backend.analysis.domain.AnalysisCompletion;
import com.dameokja.backend.analysis.domain.AnalysisCompletionFailure;
import com.dameokja.backend.analysis.domain.AnalysisCompletionSuccess;
import com.dameokja.backend.analysis.domain.AnalysisStatus;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;

public record AnalysisCallbackRequest(@NotBlank @Size(max = AiAnalysisIdentifiers.MAX_LENGTH) String analysisId, @NotNull AnalysisStatus status,
        @NotNull OffsetDateTime completedAt, @JsonProperty(required = true) AnalysisCompletionSuccess result,
        @JsonProperty(required = true) AnalysisCompletionFailure error) {
    public AnalysisCompletion completion() { return new AnalysisCompletion(status, completedAt, result, error); }
}
