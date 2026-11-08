package com.dameokja.backend.recipe.application;

import java.time.OffsetDateTime;

public record RecommendationDailyGeneration(int limit, int remaining, OffsetDateTime resetAt) {}
