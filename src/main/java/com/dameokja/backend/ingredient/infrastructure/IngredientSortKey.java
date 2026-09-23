package com.dameokja.backend.ingredient.infrastructure;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import java.util.function.Function;

record IngredientSortKey<T extends Comparable<? super T>>(
        Function<IngredientSortColumns, Expression<T>> expression,
        boolean ascending) {

    static <T extends Comparable<? super T>> IngredientSortKey<T> asc(Function<IngredientSortColumns, Expression<T>> expression) {
        return new IngredientSortKey<>(expression, true);
    }

    static <T extends Comparable<? super T>> IngredientSortKey<T> desc(Function<IngredientSortColumns, Expression<T>> expression) {
        return new IngredientSortKey<>(expression, false);
    }

    Order order(CriteriaBuilder builder, IngredientSortColumns row) {
        Expression<T> value = expression.apply(row);
        return ascending ? builder.asc(value) : builder.desc(value);
    }

    Predicate sameAs(CriteriaBuilder builder, IngredientSortColumns row, IngredientSortColumns cursor) {
        return builder.equal(expression.apply(row), expression.apply(cursor));
    }

    Predicate after(CriteriaBuilder builder, IngredientSortColumns row, IngredientSortColumns cursor) {
        Expression<T> rowValue = expression.apply(row);
        Expression<T> cursorValue = expression.apply(cursor);
        return ascending ? builder.greaterThan(rowValue, cursorValue) : builder.lessThan(rowValue, cursorValue);
    }
}
