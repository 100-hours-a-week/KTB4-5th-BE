package com.dameokja.backend.ingredient.domain;

import java.time.LocalDate;

/**
 * 재고 목록 필터칩. 상태 필터는 유통기한 그룹과 범위로, 보관 필터는 보관 방식으로 조회 조건을 좁힌다.
 * 상태 경계는 IngredientStatus와 같다.
 */
public enum IngredientFilter {
    NORMAL,
    EXPIRING_SOON,
    EXPIRED,
    REFRIGERATED,
    FROZEN;

    public boolean includes(IngredientExpiryGroup group) {
        return switch (this) {
            case EXPIRED -> group == IngredientExpiryGroup.EXPIRED;
            case EXPIRING_SOON, NORMAL -> group == IngredientExpiryGroup.NOT_EXPIRED;
            case REFRIGERATED, FROZEN -> true;
        };
    }

    // 비만료 그룹에서 조회할 유통기한 하한. 정상은 임박 기간(D-0~D-3) 다음 날부터다.
    public LocalDate notExpiredFrom(LocalDate baseDate) {
        return this == NORMAL ? baseDate.plusDays(IngredientStatus.EXPIRING_SOON_DAYS + 1L) : baseDate;
    }

    // 비만료 그룹에서 조회할 유통기한 상한. null이면 상한이 없다.
    public LocalDate notExpiredTo(LocalDate baseDate) {
        return this == EXPIRING_SOON ? baseDate.plusDays(IngredientStatus.EXPIRING_SOON_DAYS) : null;
    }

    // 보관 방식 조건. null이면 보관 방식으로 거르지 않는다.
    public StorageType storageType() {
        return switch (this) {
            case REFRIGERATED -> StorageType.REFRIGERATED;
            case FROZEN -> StorageType.FROZEN;
            case NORMAL, EXPIRING_SOON, EXPIRED -> null;
        };
    }
}
