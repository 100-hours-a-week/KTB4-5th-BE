package com.dameokja.backend.analysis.application.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;

import com.dameokja.backend.analysis.application.feedback.AnalysisFeedbackQueue;
import com.dameokja.backend.analysis.application.registration.AnalysisIngredientCommand;
import com.dameokja.backend.analysis.application.registration.AnalysisIngredientService;
import com.dameokja.backend.analysis.domain.*;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStore;
import com.dameokja.backend.analysis.infrastructure.ai.AiAnalysisClient;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.ingredient.domain.*;
import com.dameokja.backend.ingredient.application.create.IngredientCreateService;
import com.dameokja.backend.ingredient.application.create.IngredientCreateCommand;
import com.dameokja.backend.ingredient.application.create.IngredientWriteResult;
import com.dameokja.backend.support.ServiceIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.StreamSupport;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AnalysisIngredientTransactionTest extends ServiceIntegrationTest {
    @Autowired AnalysisIngredientService service;
    @Autowired IngredientCreateService ingredients;
    @Autowired AnalysisJobStore store;
    @MockitoSpyBean AnalysisFeedbackQueue queue;
    @MockitoBean AiAnalysisClient ai;

    @AfterEach
    void cleanFeedbacks() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_feedback_insert");
        jdbcTemplate.update("DELETE FROM analysis_feedbacks");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void commitsInventoryAndFeedbackTogetherOrRollsBothBack(boolean queueFails) {
        Fixture owner = ownerFixture("등록자");
        AnalysisJob job = completedJob(owner.userId());
        if (queueFails) {
            jdbcTemplate.execute("CREATE TRIGGER fail_feedback_insert BEFORE INSERT ON analysis_feedbacks FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'test failure'");
        }
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return invocation.callRealMethod();
        }).when(queue).enqueue(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        AnalysisIngredientCommand command = new AnalysisIngredientCommand(job.id(), List.of(new AnalysisIngredientCommand.Image("image", List.of(
                new AnalysisIngredientCommand.Item("item", "확정우유", IngredientCategory.DAIRY, StorageType.REFRIGERATED, MeasureType.COUNT,
                        BigDecimal.TWO, null, LocalDate.parse("2026-10-20"))))));
        if (queueFails) {
            assertThatThrownBy(() -> service.create(owner.userId(), owner.refrigeratorId(), command)).isInstanceOf(RuntimeException.class);
            assertThat(store.find(job.id())).isPresent();
        } else {
            assertThat(service.create(owner.userId(), owner.refrigeratorId(), command).createdCount()).isEqualTo(1);
            assertThat(store.find(job.id())).isEmpty();
            assertThat(jdbcTemplate.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(feedback_body, '$.items[0].confirmed.name')) FROM analysis_feedbacks", String.class))
                    .isEqualTo("확정우유");
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM analysis_feedbacks", String.class)).isEqualTo("PENDING");
        }
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ingredients", Integer.class)).isEqualTo(queueFails ? 0 : 1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM analysis_feedbacks", Integer.class)).isEqualTo(queueFails ? 0 : 1);
        verifyNoInteractions(ai);
    }

    @Test
    void queuesTheNormalizedStoredNameForEachNewItem() {
        Fixture owner = ownerFixture("등록자");
        IngredientWriteResult result = register(owner, List.of(item("milk", "  우유  ", 2), item("egg", " 달걀 ", 3)));
        assertThat(result.createdCount()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM analysis_feedbacks", Integer.class)).isEqualTo(1);
        assertThat(result.items()).extracting(write -> write.ingredient().getName()).containsExactly("우유", "달걀");
        assertFeedback("milk", "우유", 2);
        assertFeedback("egg", "달걀", 3);
    }

    @Test
    void queuesTheExistingNameWhenMergingIntoLegacyInventory() {
        Fixture owner = ownerFixture("등록자");
        IngredientWriteResult existing = ingredients.create(owner.userId(), owner.refrigeratorId(), List.of(new IngredientCreateCommand(
                "우유", IngredientCategory.DAIRY, StorageType.REFRIGERATED, MeasureType.COUNT, 1, null, WeightUnit.NONE,
                LocalDate.parse("2026-10-20"), RegistrationSource.DIRECT)));
        Long ingredientId = existing.items().getFirst().ingredient().getId();
        String legacyName = "우유";
        jdbcTemplate.update("UPDATE ingredients SET name=? WHERE ingredient_id=?", legacyName, ingredientId);
        IngredientWriteResult result = register(owner, List.of(item("milk", " 우유 ", 2)));
        assertThat(result.createdCount()).isZero();
        assertThat(result.mergedCount()).isEqualTo(1);
        assertThat(result.items().getFirst().ingredient().getId()).isEqualTo(ingredientId);
        assertThat(result.items().getFirst().ingredient().getName()).isEqualTo(legacyName);
        assertFeedback("milk", legacyName, 2);
        assertThat(jdbcTemplate.queryForObject("SELECT quantity FROM ingredients WHERE ingredient_id=?", Integer.class, ingredientId)).isEqualTo(3);
    }

    @Test
    void mapsRepeatedItemsToTheirStoredNameWithoutChangingTheirSubmittedQuantity() {
        Fixture owner = ownerFixture("등록자");
        IngredientWriteResult result = register(owner, List.of(item("milk-a", " 우유 ", 2), item("egg", " 달걀 ", 3), item("milk-b", "우유", 4)));
        assertThat(result.createdCount()).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM analysis_feedbacks", Integer.class)).isEqualTo(1);
        assertThat(result.mergedCount()).isEqualTo(1);
        assertThat(result.items().getFirst().ingredient().getId()).isEqualTo(result.items().getLast().ingredient().getId());
        assertFeedback("milk-a", "우유", 2);
        assertFeedback("egg", "달걀", 3);
        assertFeedback("milk-b", "우유", 4);
        assertThat(jdbcTemplate.queryForObject("SELECT quantity FROM ingredients WHERE name='우유'", Integer.class)).isEqualTo(6);
    }

    private IngredientWriteResult register(Fixture owner, List<AnalysisIngredientCommand.Item> items) {
        List<AnalysisCompletionItem> originals = items.stream().map(item -> new AnalysisCompletionItem(item.itemId(), RecognitionStatus.RECOGNIZED,
                "원본명", item.category(), item.storageType(), item.measureType(), 1, null, null, List.of())).toList();
        AnalysisCompletion completion = new AnalysisCompletion(AnalysisStatus.COMPLETED, clock.instant().atZone(BusinessTime.ZONE).toOffsetDateTime(),
                new AnalysisCompletionSuccess(AnalysisDocumentType.RECEIPT, originals), null);
        AnalysisJob job = store.create(owner.userId(), "RECEIPT", List.of(new AnalysisImage("image", "hash", "request", "ai-success", completion)));
        AnalysisIngredientCommand command = new AnalysisIngredientCommand(job.id(), List.of(new AnalysisIngredientCommand.Image("image", items)));
        return service.create(owner.userId(), owner.refrigeratorId(), command);
    }

    private AnalysisIngredientCommand.Item item(String itemId, String name, int quantity) {
        return new AnalysisIngredientCommand.Item(itemId, name, IngredientCategory.DAIRY, StorageType.REFRIGERATED, MeasureType.COUNT,
                BigDecimal.valueOf(quantity), null, LocalDate.parse("2026-10-20"));
    }

    private void assertFeedback(String itemId, String storedName, int requestedQuantity) {
        assertThat(jdbcTemplate.queryForObject("SELECT name FROM ingredients WHERE name=?", String.class, storedName)).isEqualTo(storedName);
        String body = jdbcTemplate.queryForObject("SELECT feedback_body FROM analysis_feedbacks", String.class);
        JsonNode items = JsonMapper.builder().build().readTree(body).path("items");
        List<JsonNode> matching = StreamSupport.stream(items.spliterator(), false)
                .filter(item -> item.path("itemId").asText().equals(itemId)).toList();
        assertThat(matching).hasSize(1);
        assertThat(matching.getFirst().path("confirmed").path("name").asText()).isEqualTo(storedName);
        assertThat(matching.getFirst().path("confirmed").path("quantity").asInt()).isEqualTo(requestedQuantity);
    }

    private AnalysisJob completedJob(Long userId) {
        AnalysisCompletionItem item = new AnalysisCompletionItem("item", RecognitionStatus.RECOGNIZED, "원본우유", IngredientCategory.DAIRY,
                StorageType.REFRIGERATED, MeasureType.COUNT, 1, null, null, List.of());
        AnalysisCompletion completion = new AnalysisCompletion(AnalysisStatus.COMPLETED, clock.instant().atZone(BusinessTime.ZONE).toOffsetDateTime(),
                new AnalysisCompletionSuccess(AnalysisDocumentType.RECEIPT, List.of(item)), null);
        return store.create(userId, "RECEIPT", List.of(new AnalysisImage("image", "hash", "request", "ai-success", completion)));
    }
}
