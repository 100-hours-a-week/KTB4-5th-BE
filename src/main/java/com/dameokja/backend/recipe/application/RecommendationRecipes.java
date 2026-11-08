package com.dameokja.backend.recipe.application;

import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationResponse;
import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationResponse.Recipe;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RecommendationRecipes {
    static final int MAX_RECIPES = 10;

    private RecommendationRecipes() {}

    // AI 취향 판정 보정 전까지 확인 후보로 임시 보충한다. 보정 후 이 로직을 제거해 원복할 수 있도록 원본 응답은 보존한다.
    // 목록·상세·요리 적용은 동일한 보충 목록을 사용한다.
    static List<Recipe> from(AiRecipeRecommendationResponse snapshot) {
        List<Recipe> candidates = new ArrayList<>(snapshot.recommendations());
        if (snapshot.confirmationCandidates() != null) { candidates.addAll(snapshot.confirmationCandidates()); }
        Map<String, Recipe> selected = new LinkedHashMap<>();
        for (Recipe recipe : candidates) {
            if (selected.size() == MAX_RECIPES) { break; }
            selected.putIfAbsent(recipe.source() + ":" + recipe.sourceRecipeId(), recipe);
        }
        return List.copyOf(selected.values());
    }
}
