package com.dameokja.backend.ingredient.domain;

import java.time.LocalDate;

/**
 * 재고 목록 필터칩. 상태 필터는 유통기한 범위로, 보관 필터는 보관 방식으로 조회 조건을 좁힌다.
 * 상태 경계는 IngredientStatus와 같다.
 */
public enum IngredientFilter {
    NORMAL,
    EXPIRING_SOON,
    EXPIRED,
    REFRIGERATED,
    FROZEN;

    // 정상은 임박 기간(D-0~D-3) 다음 날부터, 임박은 오늘부터다.
    public LocalDate expirationFrom(LocalDate baseDate) {
        return switch (this) {
            case NORMAL -> baseDate.plusDays(IngredientStatus.EXPIRING_SOON_DAYS + 1L);
            case EXPIRING_SOON -> baseDate;
            case EXPIRED, REFRIGERATED, FROZEN -> null;
        };
    }

    // 만료는 어제까지, 임박은 D-3까지다. null이면 상한이 없다.
    public LocalDate expirationTo(LocalDate baseDate) {
        return switch (this) {
            case EXPIRED -> baseDate.minusDays(1);
            case EXPIRING_SOON -> baseDate.plusDays(IngredientStatus.EXPIRING_SOON_DAYS);
            case NORMAL, REFRIGERATED, FROZEN -> null;
        };
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
