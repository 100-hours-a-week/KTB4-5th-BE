package com.dameokja.backend.recipe.presentation;

final class CookingApplyApiExamples {
    private CookingApplyApiExamples() {}
    static final String REQUEST = """
            {"recommendationId":"123","recipeId":"FOOD_SAFETY_KOREA:1024"}
            """;
    static final String APPLIED = """
            {"code":"RECOMMEND-200-004","message":"추천 레시피 적용 성공",
             "data":{"recipeId":"FOOD_SAFETY_KOREA:1024","useIngredients":[{"ingredientId":"101","useAmount":300,"unit":"G"}]}}
            """;
}
