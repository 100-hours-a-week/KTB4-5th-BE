package com.dameokja.backend.recipe.application;

import java.time.LocalDateTime;

public record RecommendationSubmissionResult(Long sequence, String eventId, String requestId, Long refrigeratorId, LocalDateTime acceptedAt) {}
