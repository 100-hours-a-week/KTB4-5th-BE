package com.dameokja.backend.recipe.presentation;

final class RecommendationListApiExamples {
    static final String EMPTY = """
            {"code":"RECOMMEND-200-001","message":"추천 레시피 조회 성공","data":{"recommendationId":null,"status":"EMPTY",
             "generatedAt":null,"activeJobId":null,"generationTrigger":null,"generationStatus":null,"hasNewRecommendation":false,
             "dailyGeneration":{"limit":10,"remaining":10,"resetAt":"2026-11-01T00:00:00+09:00"},"recipes":[]}}
            """;
    private RecommendationListApiExamples() {}
}
