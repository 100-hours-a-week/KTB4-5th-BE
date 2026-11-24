package com.dameokja.backend.recipe.application.cooking;

public record CookingApplicationRequest(Long recommendationId, String recipeId, String detailEtag, String idempotencyKey) {}
