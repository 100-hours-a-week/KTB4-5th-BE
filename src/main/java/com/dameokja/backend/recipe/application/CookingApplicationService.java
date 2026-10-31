package com.dameokja.backend.recipe.application;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.ingredient.application.IngredientConsumptionService;
import com.dameokja.backend.recipe.application.RecommendationDetailResult.RecipeIngredient;
import com.dameokja.backend.recipe.application.RecommendationDetailResult.Stock;
import com.dameokja.backend.recipe.domain.CookingRecord;
import com.dameokja.backend.recipe.exception.RecommendationExceptionCode;
import com.dameokja.backend.recipe.infrastructure.CookingRecordRepository;
import com.dameokja.backend.refrigerator.application.RefrigeratorAccessService;
import com.dameokja.backend.user.application.access.UserAccessService;
import com.dameokja.backend.user.domain.User;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CookingApplicationService {
    private final UserAccessService userAccessService;
    private final RefrigeratorAccessService refrigeratorAccessService;
    private final RecommendationDetailService recommendationDetailService;
    private final RecommendationDetailEtag recommendationDetailEtag;
    private final IngredientConsumptionService ingredientConsumptionService;
    private final CookingRecordRepository cookingRecordRepository;
    private final Clock clock;

    @Transactional
    public Optional<RecommendationDetailResult> apply(Long userId, Long refrigeratorId, CookingApplicationRequest request) {
        User user = userAccessService.lockActive(userId);
        CookingRecord previous = cookingRecordRepository.findByIdempotencyKey(request.idempotencyKey()).orElse(null);
        if (previous != null) {
            if (!previous.matchesRequest(userId, refrigeratorId, request.recommendationId().toString(), request.recipeId(), request.detailEtag())) {
                throw new CustomException(RecommendationExceptionCode.IDEMPOTENCY_CONFLICT);
            }
            return Optional.empty();
        }
        RecommendationDetailResult detail = recommendationDetailService.getForCooking(userId, refrigeratorId, request.recommendationId(), request.recipeId());
        if (!recommendationDetailEtag.of(detail).equals(request.detailEtag())) { throw new CustomException(RecommendationExceptionCode.DETAIL_CHANGED); }
        ingredientConsumptionService.consume(refrigeratorId, consumptionAmounts(detail));
        user.recordCooking();
        saveRecord(user, refrigeratorId, request, detail);
        return Optional.of(detail);
    }

    private Map<Long, BigDecimal> consumptionAmounts(RecommendationDetailResult detail) {
        Map<Long, BigDecimal> amounts = new LinkedHashMap<>();
        for (RecipeIngredient ingredient : detail.ingredients()) {
            for (Stock stock : ingredient.stocks()) {
                if ("CALCULATED".equals(stock.allocationStatus()) && stock.usedAmount() != null && stock.usedAmount().signum() > 0) {
                    amounts.merge(Long.valueOf(stock.ingredientId()), stock.usedAmount(), BigDecimal::add);
                }
            }
        }
        return amounts;
    }

    private void saveRecord(User user, Long refrigeratorId, CookingApplicationRequest request, RecommendationDetailResult detail) {
        CookingRecord record = CookingRecord.builder()
                .user(user)
                .refrigerator(refrigeratorAccessService.getReferenceById(refrigeratorId))
                .recommendationId(request.recommendationId().toString())
                .recipeId(request.recipeId())
                .recipeNameSnapshot(detail.name())
                .idempotencyKey(request.idempotencyKey())
                .detailEtag(request.detailEtag())
                .cookedAt(BusinessTime.now(clock))
                .build();
        try { cookingRecordRepository.saveAndFlush(record); }
        catch (DataIntegrityViolationException exception) { throw translateConflict(exception); }
    }

    private RuntimeException translateConflict(DataIntegrityViolationException exception) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof ConstraintViolationException constraintViolation) {
                String constraintName = constraintViolation.getConstraintName();
                if (constraintName != null && (constraintName.equals("uk_cooking_idempotency") || constraintName.endsWith(".uk_cooking_idempotency"))) {
                    return new CustomException(RecommendationExceptionCode.IDEMPOTENCY_CONFLICT);
                }
            }
            cause = cause.getCause();
        }
        return exception;
    }
}
