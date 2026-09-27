package com.dameokja.backend.ingredient.application.update;

import static com.dameokja.backend.ingredient.exception.IngredientExceptionCode.NOT_FOUND;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.ingredient.application.create.IngredientWriteItem;
import com.dameokja.backend.ingredient.domain.Ingredient;
import com.dameokja.backend.ingredient.domain.IngredientDetails;
import com.dameokja.backend.ingredient.domain.Measurement;
import com.dameokja.backend.ingredient.infrastructure.IngredientRepository;
import com.dameokja.backend.refrigerator.application.RefrigeratorAccessService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class IngredientUpdateService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");

    private final RefrigeratorAccessService refrigeratorAccessService;
    private final IngredientRepository ingredientRepository;
    private final IngredientUpdatePolicy ingredientUpdatePolicy;
    private final Clock clock;

    public IngredientUpdateResult update(Long userId, Long ingredientId, String ifMatch, IngredientUpdateFields fields) {
        LocalDate businessDate = LocalDate.now(clock.withZone(BUSINESS_ZONE));
        Ingredient ingredient = lockIngredientForUpdate(userId, ingredientId);
        validateVersion(ingredient, ifMatch);

        IngredientDetails updateDetails = ingredientUpdatePolicy.createDetails(ingredient, fields, businessDate);
        ingredient.updateDetails(updateDetails);
        List<IngredientWriteItem> mergedItems = mergeDuplicates(ingredient, updateDetails);
        ingredientRepository.flush();
        return new IngredientUpdateResult(ingredient, businessDate, mergedItems);
    }

    // 등록과 같은 순서(냉장고 → 재고)로 잠가 동시 등록·수정이 서로의 잠금을 기다리며 멈추지 않게 한다.
    private Ingredient lockIngredientForUpdate(Long userId, Long ingredientId) {
        Long refrigeratorId = ingredientRepository.findRefrigeratorIdById(ingredientId)
                .orElseThrow(() -> new CustomException(NOT_FOUND));
        refrigeratorAccessService.lockForWrite(userId, refrigeratorId);
        return ingredientRepository.findByIdForUpdate(ingredientId).orElseThrow(() -> new CustomException(NOT_FOUND));
    }

    private void validateVersion(Ingredient ingredient, String ifMatch) {
        IngredientEtag.validate(ifMatch);
        IngredientEtag.requireMatch(ingredient, ifMatch);
    }

    // 수정 결과와 같은 품목이 이미 있으면 그 수량을 수정한 재고에 더하고 기존 행은 삭제한다.
    private List<IngredientWriteItem> mergeDuplicates(Ingredient ingredient, IngredientDetails details) {
        List<Ingredient> duplicates = findDuplicates(ingredient, details);
        if (duplicates.isEmpty()) {
            return List.of();
        }
        Measurement beforeMerge = ingredient.getMeasurement();
        Measurement added = sumMeasurements(duplicates);
        ingredient.mergeMeasurement(added);
        ingredientRepository.deleteAll(duplicates);
        return List.of(IngredientWriteItem.merged(ingredient, beforeMerge, added));
    }

    private List<Ingredient> findDuplicates(Ingredient ingredient, IngredientDetails details) {
        return ingredientRepository.findMergeCandidates(ingredient.getRefrigerator().getId(), details).stream()
                .filter(candidate -> !candidate.getId().equals(ingredient.getId()))
                .toList();
    }

    private Measurement sumMeasurements(List<Ingredient> ingredients) {
        return ingredients.stream()
                .map(Ingredient::getMeasurement)
                .reduce(Measurement::add)
                .orElseThrow();
    }
}
