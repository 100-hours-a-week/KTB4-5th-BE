package com.dameokja.backend.analysis.application.registration;

import static org.assertj.core.api.Assertions.assertThat;

import com.dameokja.backend.analysis.domain.*;
import com.dameokja.backend.analysis.infrastructure.ai.AiAnalysisFeedbackRequest;
import com.dameokja.backend.ingredient.domain.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

class AnalysisFeedbackItemsTest {
    private final LocalDate date = LocalDate.of(2026, 10, 31);

    @ParameterizedTest
    @CsvSource({"SAME,CONFIRMED", "NAME,CORRECTED", "CATEGORY,CORRECTED", "STORAGE,CORRECTED", "MEASURE,CORRECTED", "QUANTITY,CORRECTED",
            "WEIGHT,CORRECTED", "UNIT,CORRECTED", "EXPIRATION,CORRECTED", "MISSING_DATE,CORRECTED", "ADDED,ADDED"})
    void classifiesEveryFinalFieldAndOmitsUnusedJsonFields(String changed, String expectedAction) {
        AnalysisCompletionItem original = new AnalysisCompletionItem("item", RecognitionStatus.RECOGNIZED, "우유", IngredientCategory.DAIRY,
                StorageType.REFRIGERATED, MeasureType.WEIGHT, null, new AnalysisCompletionItem.Weight(new BigDecimal("1000.0"), WeightUnit.ML),
                changed.equals("MISSING_DATE") ? null : new AnalysisCompletionItem.Expiration(date, AnalysisExpirationDateType.USE_BY, null), List.of());
        AiAnalysisFeedbackRequest.Confirmed value = AiAnalysisFeedbackRequest.Confirmed.userConfirmed(
                changed.equals("NAME") ? "수정우유" : "우유", changed.equals("CATEGORY") ? IngredientCategory.TOFU_BEAN : IngredientCategory.DAIRY,
                changed.equals("STORAGE") ? StorageType.FROZEN : StorageType.REFRIGERATED, changed.equals("MEASURE") ? MeasureType.COUNT : MeasureType.WEIGHT,
                changed.equals("QUANTITY") ? 2 : null, changed.equals("WEIGHT") ? BigDecimal.TEN : new BigDecimal("1000.00"),
                changed.equals("UNIT") ? WeightUnit.G : WeightUnit.ML, changed.equals("EXPIRATION") ? date.plusDays(1) : date);
        AiAnalysisFeedbackRequest.Item item = AnalysisFeedbackItems.confirmed(changed.equals("ADDED") ? null : "item", original, value);
        assertThat(item.action()).isEqualTo(expectedAction);
        JsonMapper mapper = JsonMapper.builder().build();
        assertThat(mapper.readTree(mapper.writeValueAsString(item)).has("confirmed")).isEqualTo(!changed.equals("SAME"));
        assertThat(mapper.readTree(mapper.writeValueAsString(item)).has("itemId")).isEqualTo(!changed.equals("ADDED"));
        assertThat(mapper.readTree(mapper.writeValueAsString(new AiAnalysisFeedbackRequest.Item("item", "DELETED", null))).has("confirmed")).isFalse();
    }
}
