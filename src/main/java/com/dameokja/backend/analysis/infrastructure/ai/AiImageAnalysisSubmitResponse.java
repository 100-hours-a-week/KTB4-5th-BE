package com.dameokja.backend.analysis.infrastructure.ai;

import java.time.OffsetDateTime;

public record AiImageAnalysisSubmitResponse(String analysisId, String status, OffsetDateTime submittedAt, int pollAfterMs) {}
