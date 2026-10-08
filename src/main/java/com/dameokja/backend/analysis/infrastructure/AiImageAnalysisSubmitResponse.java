package com.dameokja.backend.analysis.infrastructure;

import java.time.OffsetDateTime;

public record AiImageAnalysisSubmitResponse(String analysisId, String status, OffsetDateTime submittedAt, int pollAfterMs) {}
