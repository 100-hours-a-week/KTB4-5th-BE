package com.dameokja.backend.ingredient.infrastructure;

import com.dameokja.backend.ingredient.domain.Ingredient;
import java.util.List;

public interface IngredientListRepository {
    List<Ingredient> findListPage(IngredientListQuery query);
}
