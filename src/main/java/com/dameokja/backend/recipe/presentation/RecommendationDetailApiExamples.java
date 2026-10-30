package com.dameokja.backend.recipe.presentation;

final class RecommendationDetailApiExamples {
    static final String DETAIL = """
            {"code":"RECOMMEND-200-002","message":"레시피 상세 조회 성공","data":{"recipeId":"FOOD_SAFETY_KOREA:1024","name":"두부 조림",
             "imageUrl":null,"cookingTimeMinutes":null,"expiringIngredientCount":1,"targetServings":2,
             "ingredients":[{"recipeIngredientId":"1","name":"두부","amountText":"300g","requiredAmount":300,"unit":"G",
             "stocks":[{"ingredientId":"101","ownedAmount":400,"usedAmount":300,"unit":"G","expirationDate":"2026-11-02","allocationStatus":"CALCULATED"}]}],
             "steps":[{"stepNo":1,"instruction":"두부를 조립니다."}]}}
            """;
    private RecommendationDetailApiExamples() {}
}
