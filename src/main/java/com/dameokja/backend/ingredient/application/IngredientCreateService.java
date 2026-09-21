package com.dameokja.backend.ingredient.application;

import static com.dameokja.backend.ingredient.exception.IngredientExceptionCode.CAPACITY_EXCEEDED;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.ingredient.domain.Ingredient;
import com.dameokja.backend.ingredient.domain.IngredientDetails;
import com.dameokja.backend.ingredient.domain.Measurement;
import com.dameokja.backend.ingredient.infrastructure.IngredientRepository;
import com.dameokja.backend.refrigerator.application.RefrigeratorAccessService;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class IngredientCreateService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");

    private final RefrigeratorAccessService refrigeratorAccessService;
    private final IngredientRepository ingredientRepository;
    private final IngredientPolicy ingredientPolicy;
    private final Clock clock;

    public IngredientWriteResult create(
            Long userId, Long refrigeratorId,
            List<IngredientCreateCommand> commands) {
        LocalDate businessDate = LocalDate.now(clock.withZone(BUSINESS_ZONE));
        Refrigerator refrigerator = refrigeratorAccessService.lockForWrite(userId, refrigeratorId);
        long existingIngredientCount = ingredientRepository.countByRefrigeratorId(refrigeratorId);
        List<IngredientWriteItem> writeItems = createOrMergeIngredients(refrigerator, commands, businessDate, existingIngredientCount);

        return createResult(refrigerator, writeItems, existingIngredientCount);
    }

    private List<IngredientWriteItem> createOrMergeIngredients(Refrigerator refrigerator,
            List<IngredientCreateCommand> commands, LocalDate date, long existingCount) {
        List<IngredientWriteItem> results = new ArrayList<>();
        long newlyCreatedCount = 0;

        for (IngredientCreateCommand command : commands) {
            IngredientWriteItem result = createOrMergeIngredient(refrigerator, command, date, existingCount + newlyCreatedCount);
            results.add(result);
            newlyCreatedCount += result.created() ? 1 : 0;
        }

        return List.copyOf(results);
    }

    private IngredientWriteItem createOrMergeIngredient(Refrigerator refrigerator,
            IngredientCreateCommand command, LocalDate date, long usedSlots) {
        IngredientDetails requestedDetails = toDetails(command);
        IngredientDetails details = ingredientPolicy.validateForCreate(requestedDetails, date);
        List<Ingredient> mergeCandidates = ingredientRepository.findMergeCandidates(refrigerator.getId(), details);

        if (!mergeCandidates.isEmpty()) {
            Ingredient mergeTarget = mergeCandidates.getFirst();
            return merge(mergeTarget, details);
        }

        Ingredient ingredient = createNew(refrigerator, command, details, usedSlots);
        return IngredientWriteItem.created(ingredient);
    }

    private IngredientWriteResult createResult(Refrigerator refrigerator, List<IngredientWriteItem> writeItems,
            long existingIngredientCount) {
        int createdCount = (int) writeItems.stream().filter(IngredientWriteItem::created).count();
        int mergedCount = writeItems.size() - createdCount;
        long totalIngredientCount = existingIngredientCount + createdCount;
        int capacity = refrigerator.getCapacity();

        return new IngredientWriteResult(writeItems, createdCount, mergedCount, totalIngredientCount, capacity);
    }

    private Ingredient createNew(Refrigerator refrigerator, IngredientCreateCommand command,
            IngredientDetails details, long usedSlots) {
        if (usedSlots >= refrigerator.getCapacity()) {
            throw new CustomException(CAPACITY_EXCEEDED);
        }
        Ingredient ingredient = new Ingredient(refrigerator, details, command.registrationSource());
        return ingredientRepository.saveAndFlush(ingredient);
    }

    // 합산은 기존 카테고리·이미지·등록 경로·created_at을 그대로 두고 측정값만 더한다.
    private IngredientWriteItem merge(Ingredient target, IngredientDetails details) {
        Measurement beforeMerge = target.getMeasurement();
        Measurement addedByRequest = details.measurement();
        target.mergeMeasurement(addedByRequest);
        ingredientRepository.flush();
        return IngredientWriteItem.merged(target, beforeMerge, addedByRequest);
    }

    private IngredientDetails toDetails(IngredientCreateCommand command) {
        Measurement measurement = Measurement.of(command.measureType(), command.quantity(),
                command.weightValue(), command.weightUnit());
        return new IngredientDetails(command.name(), command.category(), command.storageType(),
                measurement, command.expirationDate());
    }
}
