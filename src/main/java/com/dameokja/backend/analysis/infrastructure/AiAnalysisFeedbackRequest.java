package com.dameokja.backend.analysis.infrastructure;

import com.dameokja.backend.ingredient.domain.IngredientCategory;
import com.dameokja.backend.ingredient.domain.MeasureType;
import com.dameokja.backend.ingredient.domain.StorageType;
import com.dameokja.backend.ingredient.domain.WeightUnit;
import java.math.BigDecimal;
import java.time.LocalDate;

public record AiAnalysisFeedbackRequest(String itemId, String action, Confirmed confirmed, boolean consentForModelImprovement) {
    // AI 계약에는 아직 수정 여부별 action이 없어 확정값은 모두 CORRECTED로 보낸다.
    public static AiAnalysisFeedbackRequest corrected(String itemId, Confirmed confirmed) {
        return new AiAnalysisFeedbackRequest(itemId, "CORRECTED", confirmed, true);
    }

    public record Confirmed(String name, IngredientCategory category, StorageType storageType, MeasureType measureType, Integer quantity,
            BigDecimal weightValue, WeightUnit weightUnit, LocalDate expirationDate, String expirationSource) {
        public static Confirmed userConfirmed(String name, IngredientCategory category, StorageType storageType, MeasureType measureType,
                Integer quantity, BigDecimal weightValue, WeightUnit weightUnit, LocalDate expirationDate) {
            return new Confirmed(name, category, storageType, measureType, quantity, weightValue, weightUnit, expirationDate, "USER_CONFIRMED");
        }
    }
}
