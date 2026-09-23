package com.dameokja.backend.ingredient.infrastructure;

import static com.dameokja.backend.ingredient.infrastructure.IngredientSortKey.asc;
import static com.dameokja.backend.ingredient.infrastructure.IngredientSortKey.desc;

import com.dameokja.backend.ingredient.domain.IngredientSortType;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import java.time.LocalDate;
import java.util.List;

final class IngredientKeyset {
    // utf8mb4_0900 콜레이션(UCA)에서는 한글 자모·음절이 'ㄱ'~'ㅣ' 사이에 모여 있고 영문·숫자는 그 앞에 온다.
    private static final String HANGUL_FIRST = "ㄱ";
    private static final String HANGUL_LAST = "ㅣ";
    private static final int FIRST_GROUP = 0;
    private static final int SECOND_GROUP = 1;
    private static final int FIRST_LETTER_POSITION = 1;
    private static final int FIRST_LETTER_LENGTH = 1;

    private final CriteriaBuilder builder;
    private final LocalDate baseDate;
    private final List<IngredientSortKey<?>> keys;

    IngredientKeyset(CriteriaBuilder builder, IngredientSortType sortType, LocalDate baseDate) {
        this.builder = builder;
        this.baseDate = baseDate;
        this.keys = keysOf(sortType);
    }

    List<Order> orders(IngredientSortColumns row) {
        return keys.stream()
                .map(key -> key.order(builder, row))
                .toList();
    }

    Predicate after(IngredientSortColumns row, IngredientSortColumns cursor) {
        return after(row, cursor, 0);
    }

    // 앞선 키가 모두 커서와 같고 index번째 키가 커서보다 뒤인 행을 재귀로 OR 연결한다.
    private Predicate after(IngredientSortColumns row, IngredientSortColumns cursor, int index) {
        IngredientSortKey<?> key = keys.get(index);
        Predicate beyond = key.after(builder, row, cursor);
        if (index == keys.size() - 1) {
            return beyond;
        }
        Predicate tieThenBeyond = builder.and(key.sameAs(builder, row, cursor), after(row, cursor, index + 1));
        return builder.or(beyond, tieThenBeyond);
    }

    // 모든 정렬은 만료 그룹이 먼저 온다. 유통기한 오름차순은 정렬 자체로 만료 재고가 앞에 오므로 그룹 키를 생략한다.
    private List<IngredientSortKey<?>> keysOf(IngredientSortType sortType) {
        return switch (sortType) {
            case EXPIRATION_ASC -> List.of(asc(IngredientSortColumns::expirationDate), desc(IngredientSortColumns::createdAt),
                    asc(this::nameGroup), asc(IngredientSortColumns::name), asc(IngredientSortColumns::ingredientId));
            case CREATED_DESC -> List.of(asc(this::expiredGroup), desc(IngredientSortColumns::createdAt),
                    asc(IngredientSortColumns::expirationDate), asc(this::nameGroup), asc(IngredientSortColumns::name),
                    asc(IngredientSortColumns::ingredientId));
            case NAME_ASC -> List.of(asc(this::expiredGroup), asc(this::nameGroup), asc(IngredientSortColumns::name),
                    asc(IngredientSortColumns::expirationDate), desc(IngredientSortColumns::createdAt),
                    asc(IngredientSortColumns::ingredientId));
        };
    }

    private Expression<Integer> expiredGroup(IngredientSortColumns columns) {
        return builder.<Integer>selectCase()
                .when(builder.lessThan(columns.expirationDate(), baseDate), FIRST_GROUP)
                .otherwise(SECOND_GROUP);
    }

    private Expression<Integer> nameGroup(IngredientSortColumns columns) {
        Expression<String> firstLetter = builder.substring(columns.name(), FIRST_LETTER_POSITION, FIRST_LETTER_LENGTH);
        return builder.<Integer>selectCase()
                .when(builder.between(firstLetter, HANGUL_FIRST, HANGUL_LAST), FIRST_GROUP)
                .otherwise(SECOND_GROUP);
    }
}
