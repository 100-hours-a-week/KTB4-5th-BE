package com.dameokja.backend.ingredient.domain;

public enum IngredientStatus {
    EXPIRED,
    EXPIRING_SOON,
    NORMAL;

    static final int EXPIRING_SOON_DAYS = 3;

    public static IngredientStatus of(long daysUntilExpiration) {
        if (daysUntilExpiration < 0) {
            return EXPIRED;
        }
        return daysUntilExpiration <= EXPIRING_SOON_DAYS ? EXPIRING_SOON : NORMAL;
    }
}
