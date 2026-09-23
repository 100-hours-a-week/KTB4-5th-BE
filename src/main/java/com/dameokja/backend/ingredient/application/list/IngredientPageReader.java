package com.dameokja.backend.ingredient.application.list;

import com.dameokja.backend.ingredient.domain.Ingredient;
import com.dameokja.backend.ingredient.domain.IngredientCursor;
import com.dameokja.backend.ingredient.domain.IngredientExpiryGroup;
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
        LocalDate baseDate = cursor.baseDate();
        LocalDate expirationDate = position == null ? null : position.expirationDate();
        LocalDateTime createdAt = position == null ? null : position.createdAt();
        String name = position == null ? null : position.name();
        Long ingredientId = position == null ? null : position.ingredientId();
        Limit rowLimit = Limit.of(limit);
        return switch (cursor.sortType()) {
            case EXPIRATION_ASC -> ingredientRepository.findExpirationAscPage(
                    refrigeratorId, expired, baseDate, expirationDate, createdAt, name, ingredientId, rowLimit);
            case CREATED_DESC -> ingredientRepository.findCreatedDescPage(
                    refrigeratorId, expired, baseDate, expirationDate, createdAt, name, ingredientId, rowLimit);
            case NAME_ASC -> ingredientRepository.findNameAscPage(
                    refrigeratorId, expired, baseDate, expirationDate, createdAt, name, ingredientId, rowLimit);
        };
    }
}
