package com.dameokja.backend.ingredient.infrastructure;

import com.dameokja.backend.ingredient.domain.Ingredient;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.List;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
class IngredientListRepositoryImpl implements IngredientListRepository {
    private final EntityManager entityManager;

    @Override
    public List<Ingredient> findListPage(IngredientListQuery query) {
        CriteriaBuilder builder = entityManager.getCriteriaBuilder();
        CriteriaQuery<Ingredient> criteria = builder.createQuery(Ingredient.class);
        Root<Ingredient> root = criteria.from(Ingredient.class);
        IngredientSortColumns row = IngredientSortColumns.of(root);
        IngredientKeyset keyset = new IngredientKeyset(builder, query.sortType(), query.baseDate());

        criteria.select(root)
                .where(conditionOf(builder, root, keyset, query))
                .orderBy(keyset.orders(row));
        return entityManager.createQuery(criteria)
                .setMaxResults(query.limit())
                .getResultList();
    }

    private Predicate conditionOf(CriteriaBuilder builder, Root<Ingredient> root,
                                  IngredientKeyset keyset, IngredientListQuery query) {
        Predicate inRefrigerator = builder.equal(root.get("refrigerator").get("id"), query.refrigeratorId());
        if (query.cursor() == null) {
            return inRefrigerator;
        }
        IngredientSortColumns cursor = IngredientSortColumns.of(builder, query.cursor());
        return builder.and(inRefrigerator, keyset.after(IngredientSortColumns.of(root), cursor));
    }
}
