package com.dameokja.backend.ingredient.infrastructure;

import com.dameokja.backend.ingredient.domain.Ingredient;
import com.dameokja.backend.ingredient.domain.IngredientCursor;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Root;
import java.time.LocalDate;
import java.time.LocalDateTime;

record IngredientSortColumns(
        Expression<LocalDate> expirationDate,
        Expression<LocalDateTime> createdAt,
        Expression<String> name,
        Expression<Long> ingredientId) {

    static IngredientSortColumns of(Root<Ingredient> root) {
        return new IngredientSortColumns(root.get("expirationDate"), root.get("createdAt"),
                root.get("name"), root.get("id"));
    }

    static IngredientSortColumns of(CriteriaBuilder builder, IngredientCursor cursor) {
        return new IngredientSortColumns(builder.literal(cursor.expirationDate()), builder.literal(cursor.createdAt()),
                builder.literal(cursor.name()), builder.literal(cursor.ingredientId()));
    }
}
