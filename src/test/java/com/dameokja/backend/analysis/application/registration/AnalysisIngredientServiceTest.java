package com.dameokja.backend.analysis.application.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.dameokja.backend.analysis.application.feedback.AnalysisFeedbackQueue;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class AnalysisIngredientServiceTest {
    private final AnalysisJobStore store = mock(AnalysisJobStore.class);
    private final IngredientCreateService creator = mock(IngredientCreateService.class);
    private final AnalysisFeedbackQueue queue = mock(AnalysisFeedbackQueue.class);
    private final AnalysisIngredientService service = new AnalysisIngredientService(store, creator, queue,
            new TransactionTemplate(mock(PlatformTransactionManager.class)));
    private final AnalysisIngredientCommand.Item count = new AnalysisIngredientCommand.Item("item", "우유", IngredientCategory.DAIRY,
            StorageType.REFRIGERATED, MeasureType.COUNT, BigDecimal.TWO, null, LocalDate.parse("2026-10-20"));
    private final IngredientWriteResult result = writeResult(1);

    @Test
    void registersAndQueuesConfirmedFeedbackThenDeletesResult() {
        IngredientWriteResult result = writeResult(2);
        AnalysisIngredientCommand.Item weight = new AnalysisIngredientCommand.Item("item", "우유", IngredientCategory.DAIRY,
                StorageType.REFRIGERATED, MeasureType.WEIGHT, null, new AnalysisIngredientCommand.Weight(BigDecimal.TEN, WeightUnit.ML), count.expiration());
        when(store.findOwned("job", 7L)).thenReturn(job(image("receipt", AnalysisDocumentType.RECEIPT), image("product", AnalysisDocumentType.PRODUCT)));
        when(creator.create(eq(7L), eq(1L), any())).thenReturn(result);
        assertThat(service.create(7L, 1L, request(List.of(count), List.of(weight)))).isSameAs(result);
        org.mockito.InOrder order = inOrder(creator, queue, store);
        order.verify(creator).create(eq(7L), eq(1L), argThat(commands -> commands.getFirst().registrationSource() == RegistrationSource.RECEIPT
                && commands.getLast().registrationSource() == RegistrationSource.FOOD_IMAGE));
        order.verify(queue).enqueue(eq("ai-receipt"), argThat(f -> f.refrigeratorId().equals(1L) && f.items().size() == 1
                && f.items().getFirst().itemId().equals("item") && f.items().getFirst().action().equals("CORRECTED")
                && f.consentForModelImprovement() && f.items().getFirst().confirmed().quantity() == 2 && f.items().getFirst().confirmed().weightUnit() == WeightUnit.NONE
                && f.items().getFirst().confirmed().expirationSource().equals("USER_CONFIRMED")));
        order.verify(queue).enqueue(eq("ai-product"), argThat(f -> f.items().getFirst().confirmed().quantity() == null && f.items().getFirst().confirmed().weightValue().equals(BigDecimal.TEN)
                && f.items().getFirst().confirmed().weightUnit() == WeightUnit.ML));
        order.verify(store).delete("job");
    }

    @ParameterizedTest
    @CsvSource({"1,true", "2,false", ",true"})
    void allowsOnlyTheOriginalRefrigeratorWhileKeepingLegacyResults(Long analysisRefrigeratorId, boolean allowed) {
        AnalysisJob job = new AnalysisJob("job", 7L, "AUTO", List.of(image("receipt", AnalysisDocumentType.RECEIPT)),
                OffsetDateTime.now(), null, false, null, List.of(), analysisRefrigeratorId);
        when(store.findOwned("job", 7L)).thenReturn(job);
        if (!allowed) {
            assertThatThrownBy(() -> service.create(7L, 1L, request(List.of(count)))).isInstanceOf(CustomException.class)
                    .extracting("exceptionCode.code").isEqualTo("IMAGE-400-002");
            verifyNoInteractions(creator, queue);
            verify(store, never()).delete(any());
            return;
        }
        when(creator.create(eq(7L), eq(1L), any())).thenReturn(result);
        assertThat(service.create(7L, 1L, request(List.of(count)))).isSameAs(result);
        verify(queue).enqueue(eq("ai-receipt"), argThat(feedback -> feedback.refrigeratorId().equals(1L)));
        verify(store).delete("job");
    }

    @Test
    void keepsResultWhenFeedbackCannotBeQueuedBecauseRegistrationRollsBack() {
        when(store.findOwned("job", 7L)).thenReturn(job(image("receipt", AnalysisDocumentType.RECEIPT)));
        when(creator.create(eq(7L), eq(1L), any())).thenReturn(result);
        doThrow(new IllegalStateException("db unavailable")).when(queue).enqueue(any(), any());
        assertThatThrownBy(() -> service.create(7L, 1L, request(List.of(count)))).isInstanceOf(IllegalStateException.class);
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
        verifyNoInteractions(creator, queue);
        verify(store, never()).delete(any());
    }

    @Test
    void doesNotQueueFeedbackOnOwnershipOrInventoryFailureAndToleratesCleanupFailure() {
        when(store.findOwned("job", 7L)).thenThrow(new CustomException(GlobalExceptionCode.FORBIDDEN));
        assertThatThrownBy(() -> service.create(7L, 1L, request(List.of(count)))).isInstanceOf(CustomException.class);
        verifyNoInteractions(creator, queue);
        doReturn(job(image("receipt", AnalysisDocumentType.RECEIPT))).when(store).findOwned("job", 7L);
        when(creator.create(eq(7L), eq(1L), any())).thenThrow(new IllegalStateException("rollback"));
        assertThatThrownBy(() -> service.create(7L, 1L, request(List.of(count)))).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(queue);
        verify(store, never()).delete(any());
        doReturn(result).when(creator).create(eq(7L), eq(1L), any());
        doThrow(new IllegalStateException("redis unavailable")).when(store).delete("job");
        assertThat(service.create(7L, 1L, request(List.of(count)))).isSameAs(result);
    }

    @Test
    void acceptsTwentyItemsAndRejectsFractionalQuantity() {
        IngredientWriteResult result = writeResult(20);
        List<AnalysisIngredientCommand.Item> items = java.util.stream.IntStream.range(0, 20).mapToObj(i -> new AnalysisIngredientCommand.Item(
                "item" + i, count.name(), count.category(), count.storageType(), count.measureType(), count.quantity(), null, count.expiration())).toList();
        when(store.findOwned("job", 7L)).thenReturn(job(withItems(image("receipt", AnalysisDocumentType.RECEIPT),
                items.stream().map(item -> original(item.itemId())).toList())));
        when(creator.create(eq(7L), eq(1L), any())).thenReturn(result);
        assertThat(service.create(7L, 1L, request(items))).isSameAs(result);
        verify(queue).enqueue(eq("ai-receipt"), argThat(feedback -> {
            assertThat(feedback.refrigeratorId()).isEqualTo(1L);
            assertThat(feedback.items()).extracting(com.dameokja.backend.analysis.infrastructure.ai.AiAnalysisFeedbackRequest.Item::itemId)
                    .containsExactlyElementsOf(items.stream().map(AnalysisIngredientCommand.Item::itemId).toList());
            return true;
        }));
        verifyNoMoreInteractions(queue);
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

    @ParameterizedTest
    @CsvSource({"64,64", "65,4", "10,65", "10,129"})
    void validatesCachedIdentifiersBeforeWritingInventoryOrFeedback(int analysisIdLength, int itemIdLength) {
        String aiAnalysisId = "a".repeat(analysisIdLength);
        String itemId = "i".repeat(itemIdLength);
        AnalysisImage cached = image("receipt", AnalysisDocumentType.RECEIPT);
        AnalysisCompletionItem item = new AnalysisCompletionItem(itemId, RecognitionStatus.RECOGNIZED, count.name(), count.category(),
                count.storageType(), count.measureType(), 2, null, null, List.of());
        AnalysisCompletion completion = new AnalysisCompletion(AnalysisStatus.COMPLETED, OffsetDateTime.now(),
                new AnalysisCompletionSuccess(AnalysisDocumentType.RECEIPT, List.of(item)), null);
        when(store.findOwned("job", 7L)).thenReturn(job(new AnalysisImage(cached.objectKey(), cached.sha256(), cached.requestId(), aiAnalysisId, completion)));
        AnalysisIngredientCommand.Item requested = new AnalysisIngredientCommand.Item(itemId, count.name(), count.category(), count.storageType(),
                count.measureType(), count.quantity(), null, count.expiration());
        if (analysisIdLength == 64 && itemIdLength == 64) {
            when(creator.create(eq(7L), eq(1L), any())).thenReturn(result);
            assertThat(service.create(7L, 1L, request(List.of(requested)))).isSameAs(result);
            verify(queue).enqueue(eq(aiAnalysisId), argThat(feedback -> feedback.items().getFirst().itemId().equals(itemId)));
        } else {
            assertThatThrownBy(() -> service.create(7L, 1L, request(List.of(requested)))).isInstanceOf(CustomException.class)
                    .extracting("exceptionCode.code").isEqualTo("IMAGE-400-002");
            verifyNoInteractions(creator, queue);
            verify(store, never()).delete(any());
        }
    }

    @Test
    void sendsDeletedItemsForAnEntireUnselectedCompletedImage() {
        when(store.findOwned("job", 7L)).thenReturn(job(image("receipt", AnalysisDocumentType.RECEIPT), image("product", AnalysisDocumentType.PRODUCT)));
        when(creator.create(eq(7L), eq(1L), any())).thenReturn(result);
        service.create(7L, 1L, request(List.of(count)));
        verify(queue).enqueue(eq("ai-product"), argThat(feedback -> feedback.consentForModelImprovement()
                && feedback.items().stream().allMatch(item -> item.action().equals("DELETED") && item.confirmed() == null)));
    }

    @Test
    void combinesAddedConfirmedCorrectedAndDeletedWithoutLosingQuantities() {
        AnalysisImage receipt = withItems(image("receipt", AnalysisDocumentType.RECEIPT), List.of(original("same"), original("changed"), original("deleted")));
        when(store.findOwned("job", 7L)).thenReturn(job(receipt));
        IngredientWriteResult result = writeResult(3);
        when(creator.create(eq(7L), eq(1L), any())).thenReturn(result);
        List<AnalysisIngredientCommand.Item> items = java.util.Arrays.asList("same", "changed", null).stream()
                .map(id -> new AnalysisIngredientCommand.Item(id, count.name(), count.category(), count.storageType(), count.measureType(),
                        "changed".equals(id) ? BigDecimal.TEN : count.quantity(), null, count.expiration())).toList();
        service.create(7L, 1L, request(items));
        verify(queue).enqueue(eq("ai-receipt"), argThat(feedback -> {
            assertThat(feedback.items()).extracting(com.dameokja.backend.analysis.infrastructure.ai.AiAnalysisFeedbackRequest.Item::action)
                    .containsExactly("CONFIRMED", "CORRECTED", "ADDED", "DELETED");
            assertThat(feedback.items().get(1).confirmed().quantity()).isEqualTo(10);
            assertThat(feedback.items().get(2).itemId()).isNull();
            assertThat(feedback.items().getFirst().confirmed()).isNull();
            return true;
        }));
    }

    @ParameterizedTest
    @ValueSource(strings = {"INVALID_ID", "DUPLICATE_ID", "INVALID_AI_ID", "OVER_LIMIT", "AT_LIMIT"})
    void validatesAllCachedItemsBeforeAnyInventoryWrite(String scenario) {
        List<AnalysisCompletionItem> originals = switch (scenario) {
            case "INVALID_ID" -> List.of(original(" "));
            case "DUPLICATE_ID" -> List.of(original("item"), original("item"));
            default -> java.util.stream.IntStream.range(0, scenario.equals("AT_LIMIT") ? 100 : 101).mapToObj(i -> original("item" + i)).toList();
        };
        AnalysisImage omitted = withItems(image("product", AnalysisDocumentType.PRODUCT), originals);
        if (scenario.equals("INVALID_AI_ID")) { omitted = new AnalysisImage("product", "hash", "request", " ", omitted.completion()); }
        when(store.findOwned("job", 7L)).thenReturn(job(image("receipt", AnalysisDocumentType.RECEIPT), omitted));
        if (scenario.equals("AT_LIMIT")) {
            when(creator.create(eq(7L), eq(1L), any())).thenReturn(result);
            service.create(7L, 1L, request(List.of(count)));
            verify(queue).enqueue(eq("ai-product"), argThat(feedback -> feedback.items().size() == 100));
        } else {
            assertThatThrownBy(() -> service.create(7L, 1L, request(List.of(count)))).isInstanceOf(CustomException.class);
            verifyNoInteractions(creator, queue);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {99, 100})
    void countsAddedItemsTogetherWithAllOriginalItemsForTheAiLimit(int originalCount) {
        List<AnalysisCompletionItem> originals = java.util.stream.IntStream.range(0, originalCount)
                .mapToObj(index -> original(index == 0 ? "item" : "item" + index)).toList();
        when(store.findOwned("job", 7L)).thenReturn(job(withItems(image("receipt", AnalysisDocumentType.RECEIPT), originals)));
        AnalysisIngredientCommand.Item added = new AnalysisIngredientCommand.Item(null, count.name(), count.category(), count.storageType(),
                count.measureType(), count.quantity(), null, count.expiration());
        if (originalCount == 99) {
            IngredientWriteResult result = writeResult(2);
            when(creator.create(eq(7L), eq(1L), any())).thenReturn(result);
            service.create(7L, 1L, request(List.of(count, added)));
            verify(queue).enqueue(eq("ai-receipt"), argThat(feedback -> feedback.items().size() == 100));
        } else {
            assertThatThrownBy(() -> service.create(7L, 1L, request(List.of(count, added)))).isInstanceOf(CustomException.class);
            verifyNoInteractions(creator, queue);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "FAILED", "OTHER"})
    void excludesImagesWithoutRegistrableCompletedItemsFromDeletion(String status) {
        AnalysisImage ignored = image("product", AnalysisDocumentType.OTHER);
        if (status.equals("PENDING")) { ignored = new AnalysisImage("product", "hash", "request", null, null); }
        if (status.equals("FAILED")) { ignored = new AnalysisImage("product", "hash", "request", "ai-product",
                new AnalysisCompletion(AnalysisStatus.FAILED, OffsetDateTime.now(), null, new AnalysisCompletionFailure("FAIL", "실패", true, 1000))); }
        when(store.findOwned("job", 7L)).thenReturn(job(image("receipt", AnalysisDocumentType.RECEIPT), ignored));
        when(creator.create(eq(7L), eq(1L), any())).thenReturn(result);
        service.create(7L, 1L, request(List.of(count)));
        verify(queue).enqueue(eq("ai-receipt"), any());
        verifyNoMoreInteractions(queue);
    }

    private AnalysisCompletionItem original(String id) {
        return new AnalysisCompletionItem(id, RecognitionStatus.RECOGNIZED, "우유", IngredientCategory.DAIRY, StorageType.REFRIGERATED, MeasureType.COUNT, 2,
                null, new AnalysisCompletionItem.Expiration(count.expiration(), AnalysisExpirationDateType.USE_BY, null), List.of());
    }

    private AnalysisImage withItems(AnalysisImage image, List<AnalysisCompletionItem> items) {
        return new AnalysisImage(image.objectKey(), image.sha256(), image.requestId(), image.aiAnalysisId(),
                new AnalysisCompletion(AnalysisStatus.COMPLETED, OffsetDateTime.now(), new AnalysisCompletionSuccess(AnalysisDocumentType.RECEIPT, items), null));
    }

    private IngredientWriteResult writeResult(int size) {
        Ingredient ingredient = mock(Ingredient.class);
        when(ingredient.getName()).thenReturn("우유");
        List<IngredientWriteItem> items = java.util.stream.IntStream.range(0, size).mapToObj(index -> IngredientWriteItem.created(ingredient)).toList();
        return new IngredientWriteResult(items, size, 0, size, 100);
    }

    @SafeVarargs
    private AnalysisIngredientCommand request(List<AnalysisIngredientCommand.Item>... items) {
        List<AnalysisIngredientCommand.Image> images = java.util.stream.IntStream.range(0, items.length)
                .mapToObj(i -> new AnalysisIngredientCommand.Image(i == 0 ? "receipt" : "product", items[i])).toList();
        return new AnalysisIngredientCommand("job", images);
    }

    private AnalysisImage image(String key, AnalysisDocumentType type) {
        List<AnalysisCompletionItem> items = List.of(new AnalysisCompletionItem("item", RecognitionStatus.RECOGNIZED, "우유", IngredientCategory.DAIRY,
                StorageType.REFRIGERATED, MeasureType.COUNT, 2, null, null, List.of()));
        return new AnalysisImage(key, "hash", "request-" + key, "ai-" + key,
                new AnalysisCompletion(AnalysisStatus.COMPLETED, OffsetDateTime.now(), new AnalysisCompletionSuccess(type, items), null));
    }

    private AnalysisJob job(AnalysisImage... images) {
        return new AnalysisJob("job", 7L, "AUTO", List.of(images), OffsetDateTime.now(), null, false);
    }
}
