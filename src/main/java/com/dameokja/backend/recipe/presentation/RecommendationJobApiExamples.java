package com.dameokja.backend.recipe.presentation;

final class RecommendationJobApiExamples {
    static final String REQUEST = """
            {"mode":"INCLUDE_EXPIRED","preferenceTags":["한식"],"limit":5,"targetServings":2,
             "strategies":["SIMPLE_COOKING"],"excludedIngredientNames":["우유"],"pantryIngredientNames":["소금"],"recentRecipeIds":["RDA:123"]}
            """;
    static final String ACCEPTED = """
            {"code":"RECOMMEND-202-001","message":"추천 생성 시작","data":{"jobId":"88e01c45-26db-43a3-bbf1-a953e71fc945",
             "status":"QUEUED","mode":"INCLUDE_EXPIRED","pollAfterMs":1000,
             "dailyGeneration":{"limit":10,"remaining":9,"resetAt":"2026-09-18T00:00:00+09:00"}}}
            """;
    static final String CURRENT = """
            {"code":"RECOMMEND-200-003","message":"이미 최신 추천입니다.","data":{"jobId":null,"status":"ALREADY_UP_TO_DATE",
             "recommendationId":"123","dailyGeneration":{"limit":10,"remaining":9,"resetAt":"2026-09-18T00:00:00+09:00"}}}
            """;
    private RecommendationJobApiExamples() {}
}
