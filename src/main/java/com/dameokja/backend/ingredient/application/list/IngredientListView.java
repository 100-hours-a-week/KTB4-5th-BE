package com.dameokja.backend.ingredient.application.list;

import com.dameokja.backend.ingredient.presentation.response.IngredientListResponse;

/** 목록 응답과 캐시 결과. cacheStatus는 캐시를 쓰지 않으면 null이다. */
public record IngredientListView(IngredientListResponse response, String cacheStatus) {
    public static final String HIT = "HIT";
    public static final String MISS = "MISS";

    public static IngredientListView uncached(IngredientListResponse response) {
        return new IngredientListView(response, null);
    }
}
