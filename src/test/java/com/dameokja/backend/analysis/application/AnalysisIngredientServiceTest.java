package com.dameokja.backend.analysis.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.dameokja.backend.analysis.domain.*;
import com.dameokja.backend.analysis.infrastructure.*;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.global.exception.GlobalExceptionCode;
import com.dameokja.backend.ingredient.application.create.*;
import com.dameokja.backend.ingredient.domain.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.client.RestClientException;

class AnalysisIngredientServiceTest {
    private final AnalysisJobStore store = mock(AnalysisJobStore.class);
    private final IngredientCreateService creator = mock(IngredientCreateService.class);
    private final AiAnalysisClient ai = mock(AiAnalysisClient.class);
    private final AnalysisIngredientService service = new AnalysisIngredientService(store, creator, ai);
    private final AnalysisIngredientCommand.Item count = new AnalysisIngredientCommand.Item("item", "우유", IngredientCategory.DAIRY,
            StorageType.REFRIGERATED, MeasureType.COUNT, BigDecimal.TWO, null, LocalDate.parse("2026-10-20"));
    private final IngredientWriteResult result = new IngredientWriteResult(List.of(), 2, 0, 2, 100);

    @Test
    void registersBeforeFeedbackAndDeletesWholeJobUsingImageAiIds() {
        AnalysisIngredientCommand.Item weight = new AnalysisIngredientCommand.Item("item", "우유", IngredientCategory.DAIRY,
                StorageType.REFRIGERATED, MeasureType.WEIGHT, null, new AnalysisIngredientCommand.Weight(BigDecimal.TEN, WeightUnit.ML), count.expiration());
        when(store.findOwned("job", 7L)).thenReturn(job(image("receipt", AnalysisDocumentType.RECEIPT), image("product", AnalysisDocumentType.PRODUCT)));
        when(creator.create(eq(7L), eq(1L), any())).thenReturn(result);
        assertThat(service.create(7L, 1L, request(List.of(count), List.of(weight)))).isSameAs(result);
        org.mockito.InOrder order = inOrder(creator, ai, store);
        order.verify(creator).create(eq(7L), eq(1L), argThat(commands -> commands.getFirst().registrationSource() == RegistrationSource.RECEIPT
                && commands.getLast().registrationSource() == RegistrationSource.FOOD_IMAGE));
        order.verify(ai).feedback(eq("ai-receipt"), argThat(f -> f.itemId().equals("item") && f.action().equals("CORRECTED")
                && f.consentForModelImprovement() && f.confirmed().quantity() == 2 && f.confirmed().weightUnit() == WeightUnit.NONE
                && f.confirmed().expirationSource().equals("USER_CONFIRMED")));
        order.verify(ai).feedback(eq("ai-product"), argThat(f -> f.confirmed().quantity() == null && f.confirmed().weightValue().equals(BigDecimal.TEN)
                && f.confirmed().weightUnit() == WeightUnit.ML));
        order.verify(store).delete("job");
    }

    @Test
    void preservesResultAndStillSendsRemainingFeedbackOnFailure() {
        when(store.findOwned("job", 7L)).thenReturn(job(image("receipt", AnalysisDocumentType.RECEIPT), image("product", AnalysisDocumentType.PRODUCT)));
        when(creator.create(eq(7L), eq(1L), any())).thenReturn(result);
        when(ai.feedback(eq("ai-receipt"), any())).thenThrow(new RestClientException("unavailable"));
        assertThat(service.create(7L, 1L, request(List.of(count), List.of(count)))).isSameAs(result);
        verify(ai).feedback(eq("ai-product"), any());
        verify(store, never()).delete(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"OTHER", "PENDING", "FAILED", "UNKNOWN_IMAGE", "UNKNOWN_ITEM", "DUPLICATE_IMAGE", "DUPLICATE_ITEM", "EMPTY", "TOO_MANY"})
    void rejectsInvalidConnectionsBeforeCreatingInventory(String scenario) {
        AnalysisImage receipt = image("receipt", scenario.equals("OTHER") ? AnalysisDocumentType.OTHER : AnalysisDocumentType.RECEIPT);
        if (scenario.equals("PENDING")) { receipt = new AnalysisImage("receipt", "hash", "r", "ai-receipt", null); }
        if (scenario.equals("FAILED")) { receipt = new AnalysisImage("receipt", "hash", "r", "ai-receipt",
                new AnalysisCompletion(AnalysisStatus.FAILED, OffsetDateTime.now(), null, new AnalysisCompletionFailure("FAIL", "실패", true, 1000))); }
        when(store.findOwned("job", 7L)).thenReturn(job(receipt));
        List<AnalysisIngredientCommand.Item> items = scenario.equals("DUPLICATE_ITEM") ? List.of(count, count) : List.of(count);
        if (scenario.equals("EMPTY")) { items = List.of(); }
        if (scenario.equals("TOO_MANY")) { items = java.util.stream.IntStream.range(0, 21).mapToObj(i -> new AnalysisIngredientCommand.Item(
                "item" + i, count.name(), count.category(), count.storageType(), count.measureType(), count.quantity(), null, count.expiration())).toList(); }
        String key = scenario.equals("UNKNOWN_IMAGE") ? "missing" : "receipt";
        if (scenario.equals("UNKNOWN_ITEM")) { items = List.of(new AnalysisIngredientCommand.Item("missing", count.name(), count.category(),
                count.storageType(), count.measureType(), count.quantity(), null, count.expiration())); }
        AnalysisIngredientCommand.Image image = new AnalysisIngredientCommand.Image(key, items);
        AnalysisIngredientCommand command = new AnalysisIngredientCommand("job", scenario.equals("DUPLICATE_IMAGE") ? List.of(image, image) : List.of(image));
        assertThatThrownBy(() -> service.create(7L, 1L, command)).isInstanceOf(CustomException.class)
                .extracting("exceptionCode.code").isEqualTo("IMAGE-400-002");
        verifyNoInteractions(creator, ai);
        verify(store, never()).delete(any());
    }

    @Test
    void doesNotSendFeedbackOnOwnershipOrInventoryFailureAndToleratesCleanupFailure() {
        when(store.findOwned("job", 7L)).thenThrow(new CustomException(GlobalExceptionCode.FORBIDDEN));
        assertThatThrownBy(() -> service.create(7L, 1L, request(List.of(count)))).isInstanceOf(CustomException.class);
        verifyNoInteractions(creator, ai);
        doReturn(job(image("receipt", AnalysisDocumentType.RECEIPT))).when(store).findOwned("job", 7L);
        when(creator.create(eq(7L), eq(1L), any())).thenThrow(new IllegalStateException("rollback"));
        assertThatThrownBy(() -> service.create(7L, 1L, request(List.of(count)))).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(ai);
        verify(store, never()).delete(any());
        doReturn(result).when(creator).create(eq(7L), eq(1L), any());
        doThrow(new IllegalStateException("redis unavailable")).when(store).delete("job");
        assertThat(service.create(7L, 1L, request(List.of(count)))).isSameAs(result);
    }

    @Test
    void acceptsTwentyItemsAndRejectsFractionalQuantity() {
        List<AnalysisIngredientCommand.Item> items = java.util.stream.IntStream.range(0, 20).mapToObj(i -> new AnalysisIngredientCommand.Item(
                "item" + i, count.name(), count.category(), count.storageType(), count.measureType(), count.quantity(), null, count.expiration())).toList();
        when(store.findOwned("job", 7L)).thenReturn(job(image("receipt", AnalysisDocumentType.RECEIPT)));
        when(creator.create(eq(7L), eq(1L), any())).thenReturn(result);
        assertThat(service.create(7L, 1L, request(items))).isSameAs(result);
        verify(ai, times(20)).feedback(eq("ai-receipt"), any());
        AnalysisIngredientCommand.Item fraction = new AnalysisIngredientCommand.Item("item", count.name(), count.category(), count.storageType(),
                count.measureType(), new BigDecimal("1.5"), null, count.expiration());
        assertThatThrownBy(() -> fraction.toCommand(RegistrationSource.RECEIPT)).isInstanceOf(CustomException.class);
    }

    @Test
    void treatsZeroQuantityOfWeightItemAsUnused() {
        AnalysisIngredientCommand.Item weight = new AnalysisIngredientCommand.Item("item", "우유", IngredientCategory.DAIRY,
                StorageType.REFRIGERATED, MeasureType.WEIGHT, BigDecimal.ZERO, new AnalysisIngredientCommand.Weight(BigDecimal.TEN, WeightUnit.ML), count.expiration());
        IngredientCreateCommand command = weight.toCommand(RegistrationSource.FOOD_IMAGE);
        assertThat(command.quantity()).isNull();
        assertThat(command.weightValue()).isEqualTo(BigDecimal.TEN);
        AnalysisIngredientCommand.Item weightWithQuantity = new AnalysisIngredientCommand.Item("item", "우유", IngredientCategory.DAIRY,
                StorageType.REFRIGERATED, MeasureType.WEIGHT, BigDecimal.ONE, weight.weight(), count.expiration());
        assertThat(weightWithQuantity.toCommand(RegistrationSource.FOOD_IMAGE).quantity()).isEqualTo(1);
    }

    @SafeVarargs
    private AnalysisIngredientCommand request(List<AnalysisIngredientCommand.Item>... items) {
        List<AnalysisIngredientCommand.Image> images = java.util.stream.IntStream.range(0, items.length)
                .mapToObj(i -> new AnalysisIngredientCommand.Image(i == 0 ? "receipt" : "product", items[i])).toList();
        return new AnalysisIngredientCommand("job", images);
    }

    private AnalysisImage image(String key, AnalysisDocumentType type) {
        List<AnalysisCompletionItem> items = java.util.stream.IntStream.range(-1, 21).mapToObj(i -> new AnalysisCompletionItem(i == -1 ? "item" : "item" + i,
                RecognitionStatus.RECOGNIZED, "우유", IngredientCategory.DAIRY, StorageType.REFRIGERATED, MeasureType.COUNT, 2, null, null, List.of())).toList();
        return new AnalysisImage(key, "hash", "request-" + key, "ai-" + key,
                new AnalysisCompletion(AnalysisStatus.COMPLETED, OffsetDateTime.now(), new AnalysisCompletionSuccess(type, items), null));
    }

    private AnalysisJob job(AnalysisImage... images) {
        return new AnalysisJob("job", 7L, "AUTO", List.of(images), OffsetDateTime.now(), null, false);
    }
}
