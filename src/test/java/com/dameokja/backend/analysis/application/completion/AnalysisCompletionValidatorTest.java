package com.dameokja.backend.analysis.application.completion;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.dameokja.backend.analysis.domain.AnalysisCompletion;
import com.dameokja.backend.analysis.domain.AnalysisCompletionFailure;
import com.dameokja.backend.analysis.domain.AnalysisCompletionItem;
import com.dameokja.backend.analysis.domain.AnalysisCompletionSuccess;
import com.dameokja.backend.analysis.domain.AnalysisDocumentType;
import com.dameokja.backend.analysis.domain.AnalysisExpirationDateType;
import com.dameokja.backend.analysis.domain.AnalysisStatus;
import com.dameokja.backend.analysis.domain.RecognitionStatus;
import com.dameokja.backend.ingredient.domain.MeasureType;
import com.dameokja.backend.ingredient.domain.WeightUnit;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class AnalysisCompletionValidatorTest {
    private final AnalysisCompletionValidator validator = new AnalysisCompletionValidator();
    private static final AnalysisCompletionItem.Expiration UNKNOWN = new AnalysisCompletionItem.Expiration(null, AnalysisExpirationDateType.UNKNOWN, null);
    private static final LocalDate DATE = LocalDate.of(2026, 10, 14);

    @ParameterizedTest
    @MethodSource("validCompletions")
    void acceptsSupportedPartialAndTerminalResults(AnalysisCompletion completion) {
        assertThatCode(() -> validator.validate(completion)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @MethodSource("invalidCompletions")
    void rejectsInconsistentFieldCombinations(AnalysisCompletion completion) {
        assertThatThrownBy(() -> validator.validate(completion)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource(value = {"COUNT,G", "COUNT,ML", "COUNT,NONE", "COUNT,NULL", "WEIGHT,G", "WEIGHT,ML",
            "WEIGHT,NONE", "WEIGHT,NULL", "NULL,G", "NULL,ML", "NULL,NONE", "NULL,NULL"}, nullValues = "NULL")
    void acceptsZeroAmountsRegardlessOfMeasurementCombination(MeasureType measureType, WeightUnit unit) {
        assertThatCode(() -> validator.validate(completedItem(measureType, 0, BigDecimal.ZERO, unit, UNKNOWN)))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @CsvSource({"64,true", "65,false", "129,false"})
    void limitsItemIdsToTheAiFeedbackContract(int length, boolean valid) {
        AnalysisCompletionItem item = new AnalysisCompletionItem("i".repeat(length), RecognitionStatus.NEEDS_REVIEW,
                null, null, null, null, null, new AnalysisCompletionItem.Weight(null, null), UNKNOWN, List.of());
        AnalysisCompletion completion = completed(AnalysisDocumentType.PRODUCT, List.of(item));
        if (valid) {
            assertThatCode(() -> validator.validate(completion)).doesNotThrowAnyException();
        } else {
            assertThatThrownBy(() -> validator.validate(completion)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    static Stream<AnalysisCompletion> validCompletions() {
        return Stream.of(completed(AnalysisDocumentType.OTHER, List.of()),
                completed(AnalysisDocumentType.PRODUCT, List.of(item(null, null, null, null, UNKNOWN))),
                completed(AnalysisDocumentType.RECEIPT, List.of(item(MeasureType.COUNT, 1, null, WeightUnit.NONE, UNKNOWN))),
                completed(AnalysisDocumentType.RECEIPT, List.of(item(MeasureType.WEIGHT, null, null, null, UNKNOWN))),
                completed(AnalysisDocumentType.RECEIPT, List.of(item(MeasureType.COUNT, 0, BigDecimal.ZERO, WeightUnit.NONE, UNKNOWN))),
                completed(AnalysisDocumentType.RECEIPT, List.of(item(MeasureType.WEIGHT, 0, BigDecimal.ZERO, WeightUnit.G, UNKNOWN))),
                completedItem(MeasureType.COUNT, 1, BigDecimal.ONE, WeightUnit.G, UNKNOWN),
                completedItem(MeasureType.WEIGHT, 1, BigDecimal.ONE, WeightUnit.G, UNKNOWN),
                completedItem(null, null, null, WeightUnit.NONE, UNKNOWN),
                completed(AnalysisDocumentType.PRODUCT, List.of(item(MeasureType.WEIGHT, null, BigDecimal.ONE, WeightUnit.G,
                        new AnalysisCompletionItem.Expiration(null, AnalysisExpirationDateType.ESTIMATED_CONSUMPTION_WINDOW,
                                new AnalysisCompletionItem.EstimatedRange(DATE, DATE.plusDays(2)))))),
                completed(AnalysisDocumentType.PRODUCT, List.of(item(MeasureType.COUNT, 100, null, WeightUnit.NONE,
                        new AnalysisCompletionItem.Expiration(DATE, AnalysisExpirationDateType.USE_BY, null)))), failed("MODEL_UNAVAILABLE", "분석 실패", 0));
    }

    static Stream<AnalysisCompletion> invalidCompletions() {
        AnalysisCompletionItem count = item(MeasureType.COUNT, 1, null, WeightUnit.NONE, UNKNOWN);
        return Stream.of(completed(null, List.of()), completed(AnalysisDocumentType.OTHER, List.of(count)),
                completed(AnalysisDocumentType.RECEIPT, List.of(count, count)),
                completedItem(MeasureType.COUNT, 101, null, WeightUnit.NONE, UNKNOWN),
                completedItem(MeasureType.COUNT, -1, null, WeightUnit.NONE, UNKNOWN),
                completedItem(MeasureType.WEIGHT, null, BigDecimal.ONE.negate(), WeightUnit.G, UNKNOWN),
                completedItem(null, -1, BigDecimal.ZERO, null, UNKNOWN),
                completedItem(null, null, null, null, new AnalysisCompletionItem.Expiration(null, AnalysisExpirationDateType.USE_BY, null)),
                completedItem(null, null, null, null, new AnalysisCompletionItem.Expiration(DATE, AnalysisExpirationDateType.UNKNOWN, null)),
                completedItem(null, null, null, null, new AnalysisCompletionItem.Expiration(null, AnalysisExpirationDateType.ESTIMATED_CONSUMPTION_WINDOW,
                        new AnalysisCompletionItem.EstimatedRange(DATE.plusDays(1), DATE))),
                failed(" ", "분석 실패", null), failed("MODEL_UNAVAILABLE", "", null), failed("MODEL_UNAVAILABLE", "분석 실패", -1));
    }

    private static AnalysisCompletion completedItem(MeasureType measureType, Integer quantity, BigDecimal value, WeightUnit unit, AnalysisCompletionItem.Expiration expiration) {
        return completed(AnalysisDocumentType.RECEIPT, List.of(item(measureType, quantity, value, unit, expiration)));
    }

    private static AnalysisCompletionItem item(MeasureType measureType, Integer quantity, BigDecimal value, WeightUnit unit, AnalysisCompletionItem.Expiration expiration) {
        return new AnalysisCompletionItem("item-1", RecognitionStatus.NEEDS_REVIEW, null, null, null, measureType, quantity,
                new AnalysisCompletionItem.Weight(value, unit), expiration, List.of());
    }

    private static AnalysisCompletion completed(AnalysisDocumentType documentType, List<AnalysisCompletionItem> items) {
        return new AnalysisCompletion(AnalysisStatus.COMPLETED, OffsetDateTime.parse("2026-10-14T10:00:00+09:00"),
                new AnalysisCompletionSuccess(documentType, items), null);
    }

    private static AnalysisCompletion failed(String code, String message, Integer retryAfterMs) {
        return new AnalysisCompletion(AnalysisStatus.FAILED, OffsetDateTime.parse("2026-10-14T10:00:00+09:00"), null,
                new AnalysisCompletionFailure(code, message, false, retryAfterMs));
    }
}
