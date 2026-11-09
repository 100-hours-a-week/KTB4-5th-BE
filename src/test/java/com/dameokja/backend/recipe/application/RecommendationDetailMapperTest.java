package com.dameokja.backend.recipe.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.dameokja.backend.recipe.infrastructure.ai.AiRecipeRecommendationResponse.Recipe;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class RecommendationDetailMapperTest {
    private final JsonMapper jsonMapper = JsonMapper.builder().findAndAddModules().build();

    @ParameterizedTest
    @CsvSource(value = {"null,125.5,3,376.5", "12,125.5,3,12", "0,125.5,3,0", "null,null,3,null", "null,125.5,null,null"}, nullValues = "null")
    void displaysRequiredAmountOrScalesOriginalAmountWhenMissing(BigDecimal required, BigDecimal original, Integer servings, BigDecimal expected) throws Exception {
        ObjectNode snapshot = recipeSnapshot();
        ((ObjectNode) snapshot.path("ingredientRequirements").get(0)).put("requiredAmount", required);
        ((ObjectNode) snapshot.path("ingredients").get(0)).put("amountValue", original);
        snapshot.put("targetServings", servings);

        RecommendationDetailResult detail = detail(snapshot);

        if (expected == null) { assertThat(detail.ingredients().getFirst().requiredAmount()).isNull(); }
        else { assertThat(detail.ingredients().getFirst().requiredAmount()).isEqualByComparingTo(expected); }
        assertThat(detail.targetServings()).isEqualTo(servings);
    }

    @Test
    void displaysScaledAmountWhenRequirementEntryIsMissing() throws Exception {
        ObjectNode snapshot = recipeSnapshot();
        snapshot.putArray("ingredientRequirements");
        snapshot.put("targetServings", 3);
        ((ObjectNode) snapshot.path("ingredients").get(0)).put("amountValue", new BigDecimal("125.5"));

        RecommendationDetailResult.RecipeIngredient ingredient = detail(snapshot).ingredients().getFirst();

        assertThat(ingredient.requiredAmount()).isEqualByComparingTo("376.5");
        assertThat(ingredient.unit()).isEqualTo("G");
    }

    private ObjectNode recipeSnapshot() throws Exception {
        return (ObjectNode) jsonMapper.readTree(getClass().getResourceAsStream("/recipe-ai/response.json")).path("recommendations").get(0);
    }

    private RecommendationDetailResult detail(ObjectNode snapshot) {
        return RecommendationDetailMapper.from(jsonMapper.treeToValue(snapshot, Recipe.class), Map.of(), LocalDate.of(2026, 11, 9));
    }
}
