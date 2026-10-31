package com.dameokja.backend.recipe.application;

public record CookingApplicationRequest(Long recommendationId, String recipeId, String detailEtag, String idempotencyKey) {}
