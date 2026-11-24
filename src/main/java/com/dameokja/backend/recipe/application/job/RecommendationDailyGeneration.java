package com.dameokja.backend.recipe.application.job;

import java.time.OffsetDateTime;

public record RecommendationDailyGeneration(int limit, int remaining, OffsetDateTime resetAt) {}
