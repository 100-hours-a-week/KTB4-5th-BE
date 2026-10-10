package com.dameokja.backend.ingredient.application.list;

import com.dameokja.backend.ingredient.domain.Ingredient;
import com.dameokja.backend.ingredient.domain.IngredientCategory;
import com.dameokja.backend.ingredient.domain.IngredientCursor;
import com.dameokja.backend.ingredient.domain.IngredientFilter;
import com.dameokja.backend.ingredient.domain.IngredientSortType;
import com.dameokja.backend.ingredient.domain.StorageType;
import com.dameokja.backend.ingredient.infrastructure.IngredientRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class IngredientPageReader {
    private final IngredientRepository ingredientRepository;

    // 필터에 맞는 전체 재고를 선택한 정렬 기준과 커서 위치로 한 번에 조회한다.
    List<Ingredient> read(IngredientListCursor cursor, int limit) {
        IngredientFilter filter = cursor.filter();
        LocalDate expirationFrom = filter == null ? null : filter.expirationFrom(cursor.baseDate());
        LocalDate expirationTo = filter == null ? null : filter.expirationTo(cursor.baseDate());
        IngredientCursor position = cursor.position();
        LocalDate expirationDate = position == null ? null : position.expirationDate();
        LocalDateTime createdAt = position == null ? null : position.createdAt();
        String name = position == null ? null : position.name();
        Long ingredientId = position == null ? null : position.ingredientId();
        SortedPageQuery query = queryOf(cursor.sortType());
        return query.find(cursor.refrigeratorId(), expirationFrom, expirationTo, cursor.storageType(), cursor.category(), cursor.keywordPattern(),
                expirationDate, createdAt, name, ingredientId, Limit.of(limit));
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
        List<Ingredient> find(Long refrigeratorId, LocalDate expirationFrom, LocalDate expirationTo, StorageType storageType, IngredientCategory category,
                              String keywordPattern, LocalDate expirationDate, LocalDateTime createdAt, String name, Long ingredientId, Limit limit);
    }
}
