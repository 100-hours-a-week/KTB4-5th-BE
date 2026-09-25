package com.dameokja.backend.ingredient.application.expire;

import static com.dameokja.backend.ingredient.exception.IngredientExceptionCode.INVALID_INPUT;

import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.ingredient.domain.Ingredient;
import com.dameokja.backend.ingredient.infrastructure.IngredientRepository;
import com.dameokja.backend.refrigerator.application.RefrigeratorAccessService;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class IngredientBulkExpireService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Seoul");
    private static final int MAX_SELECTION = 100; // 냉장고 한 개의 최대 재고 수

    private final RefrigeratorAccessService refrigeratorAccessService;
    private final IngredientRepository ingredientRepository;
    private final Clock clock;

    // 선택한 재고 중 이 냉장고에 있고 유통기한이 오늘보다 이전인 재고만 삭제한다.
    // 이미 삭제됐거나 그사이 유통기한이 바뀐 재고는 건너뛴다. 오늘 만료(D-0)는 아직 임박이라 제외한다.
    public int expire(Long userId, Long refrigeratorId, List<Long> ingredientIds) {
        List<Long> targetIds = validateIngredientIds(ingredientIds);
        LocalDate businessDate = LocalDate.now(clock.withZone(BUSINESS_ZONE));
        Refrigerator refrigerator = refrigeratorAccessService.lockForWrite(userId, refrigeratorId);
        List<Ingredient> expiredIngredients = ingredientRepository
                .findByRefrigerator_IdAndIdInAndExpirationDateBefore(refrigeratorId, targetIds, businessDate);

        ingredientRepository.deleteAll(expiredIngredients);
        refrigerator.countExpiredDeletions(expiredIngredients.size());
        ingredientRepository.flush();
        return expiredIngredients.size();
    }

    private List<Long> validateIngredientIds(List<Long> ingredientIds) {
        if (ingredientIds == null || ingredientIds.isEmpty() || ingredientIds.stream().anyMatch(Objects::isNull)) {
            throw new CustomException(INVALID_INPUT);
        }
        List<Long> distinctIds = ingredientIds.stream().distinct().toList();
        if (distinctIds.size() > MAX_SELECTION) {
            throw new CustomException(INVALID_INPUT);
        }
        return distinctIds;
    }
}
