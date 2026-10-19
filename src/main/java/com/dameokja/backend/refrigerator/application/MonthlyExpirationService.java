package com.dameokja.backend.refrigerator.application;

import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.ingredient.application.IngredientExpirationService;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.refrigerator.infrastructure.RefrigeratorRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MonthlyExpirationService {
    private final RefrigeratorRepository refrigeratorRepository;
    private final RefrigeratorAccessService refrigeratorAccessService;
    private final IngredientExpirationService ingredientExpirationService;
    private final Clock clock;

    public int getCount(Long userId, Long refrigeratorId) {
        Refrigerator refrigerator = refrigeratorAccessService.validateReadAccess(userId, refrigeratorId);
        return currentCount(refrigerator);
    }

    public int currentCount(Refrigerator refrigerator) {
        LocalDate date = BusinessTime.today(clock);
        int pending = ingredientExpirationService.countInWindow(refrigerator.getId(),
                refrigerator.unaccountedExpirationFrom(date), date);
        return Math.addExact(refrigerator.monthlyExpiredCount(date), pending);
    }

    /**
     * 삭제 전에 아직 배치가 반영하지 못한 만료분을 같은 트랜잭션에 보존합니다.
     * @param refrigerator 호출자가 쓰기 잠금을 획득한 냉장고
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void accountPending(Refrigerator refrigerator) {
        LocalDate date = BusinessTime.today(clock);
        int pending = ingredientExpirationService.countInWindowForUpdate(refrigerator.getId(),
                refrigerator.unaccountedExpirationFrom(date), date);
        refrigerator.recordExpirations(date, pending);
    }

    /**
     * 냉장고별 배치 집계를 저장합니다. 앞선 일반 조회가 없는 트랜잭션에서 호출해야 합니다.
     * @param refrigeratorId 집계할 냉장고 ID
     */
    @Transactional
    public void synchronize(Long refrigeratorId) {
        refrigeratorRepository.findByIdForUpdate(refrigeratorId)
                .filter(refrigerator -> refrigerator.getDeletedAt() == null)
                .ifPresent(this::accountPendingForBatch);
    }

    private void accountPendingForBatch(Refrigerator refrigerator) {
        LocalDate date = BusinessTime.today(clock);
        // 배치는 첫 일반 조회가 냉장고 잠금 뒤에 실행되므로 재고 행 잠금 없이 최신 상태를 센다.
        int pending = ingredientExpirationService.countInWindow(refrigerator.getId(),
                refrigerator.unaccountedExpirationFrom(date), date);
        refrigerator.recordExpirations(date, pending);
    }

    public List<Long> findPendingIds(Long afterId, int batchSize) {
        return refrigeratorRepository.findExpirationAccountingTargets(afterId, BusinessTime.today(clock), Limit.of(batchSize));
    }
}
