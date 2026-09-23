package com.dameokja.backend.ingredient.application.list;

import com.dameokja.backend.ingredient.domain.Ingredient;
import com.dameokja.backend.ingredient.domain.IngredientCursor;
import com.dameokja.backend.ingredient.domain.IngredientExpiryGroup;
import com.dameokja.backend.ingredient.domain.IngredientSortType;
import com.dameokja.backend.ingredient.infrastructure.IngredientRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class IngredientPageReader {
    private final IngredientRepository ingredientRepository;

    // 만료 그룹을 먼저 읽고, limit을 다 채우지 못하면 같은 요청에서 비만료 그룹을 처음부터 이어 읽는다.
    List<Ingredient> read(IngredientListCursor cursor, int limit) {
        List<Ingredient> rows = new ArrayList<>(find(cursor, cursor.group(), cursor.position(), limit));
        if (cursor.group() == IngredientExpiryGroup.EXPIRED && rows.size() < limit) {
            rows.addAll(find(cursor, IngredientExpiryGroup.NOT_EXPIRED, null, limit - rows.size()));
        }
        return rows;
    }

    private List<Ingredient> find(IngredientListCursor cursor, IngredientExpiryGroup group,
                                  IngredientCursor position, int limit) {
        Long refrigeratorId = cursor.refrigeratorId();
        boolean expired = group == IngredientExpiryGroup.EXPIRED;
        // 만료 그룹은 기준일 전날까지, 비만료 그룹은 기준일(당일 포함)부터. null은 그쪽 경계가 없다는 뜻이다.
        LocalDate expirationFrom = expired ? null : cursor.baseDate();
        LocalDate expirationTo = expired ? cursor.baseDate().minusDays(1) : null;
        LocalDate expirationDate = position == null ? null : position.expirationDate();
        LocalDateTime createdAt = position == null ? null : position.createdAt();
        String name = position == null ? null : position.name();
        Long ingredientId = position == null ? null : position.ingredientId();
        Limit rowLimit = Limit.of(limit);
        SortedPageQuery query = queryOf(cursor.sortType());
        return query.find(refrigeratorId, expirationFrom, expirationTo, expirationDate, createdAt, name, ingredientId, rowLimit);
    }

    // switch 식은 정렬 유형이 추가됐는데 case가 빠지면 컴파일 에러를 내므로 Map 대신 사용한다.
    private SortedPageQuery queryOf(IngredientSortType sortType) {
        return switch (sortType) {
            case EXPIRATION_ASC -> ingredientRepository::findExpirationAscPage;
            case CREATED_DESC -> ingredientRepository::findCreatedDescPage;
            case NAME_ASC -> ingredientRepository::findNameAscPage;
        };
    }

    // 정렬별 조회 메서드는 모두 같은 인자를 받으므로, 어떤 메서드를 쓸지만 고르고 호출은 한 번에 한다.
    @FunctionalInterface
    private interface SortedPageQuery {
        List<Ingredient> find(Long refrigeratorId, LocalDate expirationFrom, LocalDate expirationTo, LocalDate expirationDate,
                              LocalDateTime createdAt, String name, Long ingredientId, Limit limit);
    }
}
