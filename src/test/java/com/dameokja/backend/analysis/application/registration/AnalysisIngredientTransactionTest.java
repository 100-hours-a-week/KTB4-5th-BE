package com.dameokja.backend.analysis.application.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.dameokja.backend.analysis.application.feedback.AnalysisFeedbackOutbox;
import com.dameokja.backend.analysis.application.registration.AnalysisIngredientCommand;
import com.dameokja.backend.analysis.application.registration.AnalysisIngredientService;
import com.dameokja.backend.analysis.domain.*;
import com.dameokja.backend.analysis.application.job.AnalysisJobStateAccess;
import com.dameokja.backend.analysis.application.job.AnalysisJobAcceptance;
import com.dameokja.backend.analysis.application.completion.AnalysisCompletionPersistence;
import com.dameokja.backend.analysis.infrastructure.kafka.AnalysisCompletedEvent;
import com.dameokja.backend.analysis.exception.AnalysisExceptionCode;
import com.dameokja.backend.global.exception.CustomException;
import com.dameokja.backend.analysis.support.AnalysisDatabaseContractTestSupport;
import com.dameokja.backend.analysis.support.AnalysisDatabaseJobFixture;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.global.security.JwtProvider;
import com.dameokja.backend.user.domain.UserRole;
import jakarta.servlet.http.Cookie;
import com.dameokja.backend.ingredient.domain.*;
import com.dameokja.backend.ingredient.application.create.IngredientCreateService;
import com.dameokja.backend.ingredient.application.create.IngredientCreateCommand;
import com.dameokja.backend.ingredient.application.create.IngredientWriteResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AnalysisIngredientTransactionTest extends AnalysisDatabaseContractTestSupport {
    @Autowired AnalysisIngredientService service;
    @Autowired JwtProvider jwtProvider;
    @Autowired IngredientCreateService ingredients;
    @Autowired AnalysisDatabaseJobFixture analysisDatabaseJobFixture;
    @Autowired AnalysisJobStateAccess analysisJobStateAccess;
    @Autowired AnalysisJobAcceptance analysisJobAcceptance;
    @Autowired AnalysisCompletionPersistence analysisCompletionPersistence;
    @MockitoSpyBean AnalysisFeedbackOutbox queue;

    @AfterEach
    void cleanFeedbacks() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_analysis_registration");
        jdbcTemplate.update("DELETE FROM analysis_feedbacks");
    }

    @ParameterizedTest
    @ValueSource(strings = {"NONE", "ingredients", "analysis_feedbacks", "analysis_job_states"})
    void commitsInventoryFeedbackAndResultDeletionTogetherOrRollsEverythingBack(String failureTable) {
        Fixture owner = ownerFixture("등록자");
        AnalysisJob job = completedJob(owner.userId());
        boolean shouldFail = !failureTable.equals("NONE");
        if (shouldFail) {
            String failureOperation = failureTable.equals("analysis_job_states") ? "DELETE" : "INSERT";
            jdbcTemplate.execute("CREATE TRIGGER fail_analysis_registration BEFORE " + failureOperation + " ON " + failureTable
                    + " FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'registration-rollback'");
        }
        assertFeedbackUsesTheTransaction();
        AnalysisIngredientCommand command = new AnalysisIngredientCommand(job.id(), List.of(new AnalysisIngredientCommand.Image("image", List.of(
                new AnalysisIngredientCommand.Item("item", "확정우유", IngredientCategory.DAIRY, StorageType.REFRIGERATED, MeasureType.COUNT,
                        BigDecimal.TWO, null, BusinessTime.today(clock).plusDays(3))))));
        if (shouldFail) {
            assertThatThrownBy(() -> service.create(owner.userId(), owner.refrigeratorId(), command)).hasStackTraceContaining("registration-rollback");
            assertThat(analysisJobStateAccess.find(job.id())).isPresent();
        } else {
            assertThat(service.create(owner.userId(), owner.refrigeratorId(), command).createdCount()).isEqualTo(1);
            assertThat(analysisJobStateAccess.find(job.id())).isEmpty();
            assertThat(jdbcTemplate.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(feedback_body, '$.payload.items[0].confirmed.name')) FROM analysis_feedbacks", String.class))
                    .isEqualTo("확정우유");
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM analysis_feedbacks", String.class)).isEqualTo("PENDING");
        }
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ingredients", Integer.class)).isEqualTo(shouldFail ? 0 : 1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM analysis_feedbacks", Integer.class)).isEqualTo(shouldFail ? 0 : 1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM analysis_job_states", Integer.class)).isEqualTo(shouldFail ? 1 : 0);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM analysis_job_requests", Integer.class)).isEqualTo(shouldFail ? 1 : 0);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM analysis_outbox", Integer.class)).isEqualTo(shouldFail ? 1 : 0);
        verifyNoInteractions(analysisKafkaPublisher);
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
                BusinessTime.today(clock).plusDays(3), RegistrationSource.DIRECT)));
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

    @Test
    void storesAllActionsBeforeRemovingTheOriginalResult() {
        Fixture owner = ownerFixture("등록자");
        List<AnalysisCompletionItem> originals = List.of(feedbackOriginal("same", 2), feedbackOriginal("changed", 1), feedbackOriginal("deleted", 1));
        AnalysisCompletion completion = new AnalysisCompletion(AnalysisStatus.COMPLETED, clock.instant().atZone(BusinessTime.ZONE).toOffsetDateTime(),
                new AnalysisCompletionSuccess(AnalysisDocumentType.RECEIPT, originals), null);
        AnalysisJob job = analysisDatabaseJobFixture.create(owner.userId(), "RECEIPT", List.of(new AnalysisImage("image", "hash", "request", "ai-actions", completion)));
        service.create(owner.userId(), owner.refrigeratorId(), new AnalysisIngredientCommand(job.id(), List.of(new AnalysisIngredientCommand.Image("image",
                List.of(item("same", "우유", 2), item("changed", "우유", 3), item(null, "우유", 4))))));
        assertThat(analysisJobStateAccess.find(job.id())).isEmpty();
        JsonNode body = JsonMapper.builder().build().readTree(jdbcTemplate.queryForObject("SELECT feedback_body FROM analysis_feedbacks", String.class));
        List<JsonNode> feedbacks = StreamSupport.stream(body.path("payload").path("items").spliterator(), false).toList();
        assertThat(feedbacks).extracting(feedback -> feedback.path("action").asText()).containsExactly("CONFIRMED", "CORRECTED", "ADDED", "DELETED");
        assertThat(feedbacks.getFirst().has("confirmed")).isFalse();
        assertThat(feedbacks.get(1).path("confirmed").path("quantity").asInt()).isEqualTo(3);
        assertThat(feedbacks.get(2).has("itemId")).isFalse();
        assertThat(feedbacks.get(2).path("confirmed").path("quantity").asInt()).isEqualTo(4);
        assertThat(feedbacks.getLast().has("confirmed")).isFalse();
        assertThat(body.path("payload").path("consentForModelImprovement").asBoolean()).isTrue();
        assertThat(body.path("payload").path("userId").asLong()).isEqualTo(owner.userId());
        assertThat(body.path("payload").path("requestId").asString()).isEqualTo("request");
        assertThat(body.path("messageId").asString()).isNotBlank();
        assertThat(jdbcTemplate.queryForObject("SELECT quantity FROM ingredients", Integer.class)).isEqualTo(9);
        verifyNoInteractions(analysisKafkaPublisher);
    }

    private void assertFeedbackUsesTheTransaction() {
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return invocation.callRealMethod();
        }).when(queue).enqueue(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void registersTheDatabaseResultThroughTheExistingHttpContract() throws Exception {
        Fixture owner = ownerFixture("HTTP등록자");
        AnalysisJob analysisJob = completedJob(owner.userId());
        String body = """
                {"analysisId":"%s","images":[{"imageObjectKey":"image","items":[
                  {"itemId":"item","name":"우유","category":"DAIRY","storageType":"REFRIGERATED",
                   "measureType":"COUNT","quantity":2,"expiration":"%s"}]}]}
                """.formatted(analysisJob.id(), BusinessTime.today(clock).plusDays(3));
        mvc.perform(post("/api/v1/refrigerators/{refrigeratorId}/ingredients/from-analysis", owner.refrigeratorId()).with(csrf())
                        .cookie(new Cookie("accessToken", jwtProvider.createAccessToken(owner.userId(), UserRole.USER)))
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.code").value("INGREDIENT-201-001"))
                .andExpect(jsonPath("$.data.createdCount").value(1)).andExpect(jsonPath("$.data.mergedCount").value(0));
        assertThat(analysisJobStateAccess.find(analysisJob.id())).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM analysis_feedbacks", Integer.class)).isEqualTo(1);
        verifyNoInteractions(analysisKafkaPublisher);
    }

    @Test
    void consumesTheResultOnlyOnceWhenTwoInstancesRegisterItConcurrently() throws Exception {
        Fixture owner = ownerFixture("동시등록자");
        AnalysisJob analysisJob = completedJob(owner.userId());
        AnalysisIngredientCommand command = new AnalysisIngredientCommand(analysisJob.id(), List.of(new AnalysisIngredientCommand.Image("image", List.of(item("item", "우유", 2)))));
        CyclicBarrier startRegistrations = new CyclicBarrier(2);
        try (ExecutorService executorService = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Boolean> registration = () -> {
                startRegistrations.await(5, TimeUnit.SECONDS);
                try {
                    service.create(owner.userId(), owner.refrigeratorId(), command);
                    return true;
                } catch (CustomException exception) {
                    assertThat(exception.getExceptionCode()).isEqualTo(AnalysisExceptionCode.NOT_FOUND);
                    return false;
                }
            };
            Future<Boolean> firstRegistration = executorService.submit(registration);
            Future<Boolean> secondRegistration = executorService.submit(registration);
            assertThat(List.of(firstRegistration.get(5, TimeUnit.SECONDS), secondRegistration.get(5, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
        }
        assertThat(jdbcTemplate.queryForObject("SELECT quantity FROM ingredients", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM analysis_feedbacks", Integer.class)).isEqualTo(1);
        assertThat(analysisJobStateAccess.find(analysisJob.id())).isEmpty();
    }

    private AnalysisCompletionItem feedbackOriginal(String id, int quantity) {
        return new AnalysisCompletionItem(id, RecognitionStatus.RECOGNIZED, "우유", IngredientCategory.DAIRY, StorageType.REFRIGERATED,
                MeasureType.COUNT, quantity, null, new AnalysisCompletionItem.Expiration(BusinessTime.today(clock).plusDays(3), AnalysisExpirationDateType.USE_BY, null), List.of());
    }

    private IngredientWriteResult register(Fixture owner, List<AnalysisIngredientCommand.Item> items) {
        List<AnalysisCompletionItem> originals = items.stream().map(item -> new AnalysisCompletionItem(item.itemId(), RecognitionStatus.RECOGNIZED,
                "원본명", item.category(), item.storageType(), item.measureType(), 1, null, null, List.of())).toList();
        AnalysisCompletion completion = new AnalysisCompletion(AnalysisStatus.COMPLETED, clock.instant().atZone(BusinessTime.ZONE).toOffsetDateTime(),
                new AnalysisCompletionSuccess(AnalysisDocumentType.RECEIPT, originals), null);
        AnalysisJob job = analysisDatabaseJobFixture.create(owner.userId(), "RECEIPT", List.of(new AnalysisImage("image", "hash", "request", "ai-success", completion)));
        AnalysisIngredientCommand command = new AnalysisIngredientCommand(job.id(), List.of(new AnalysisIngredientCommand.Image("image", items)));
        return service.create(owner.userId(), owner.refrigeratorId(), command);
    }

    private AnalysisIngredientCommand.Item item(String itemId, String name, int quantity) {
        return new AnalysisIngredientCommand.Item(itemId, name, IngredientCategory.DAIRY, StorageType.REFRIGERATED, MeasureType.COUNT,
                BigDecimal.valueOf(quantity), null, BusinessTime.today(clock).plusDays(3));
    }

    private void assertFeedback(String itemId, String storedName, int requestedQuantity) {
        assertThat(jdbcTemplate.queryForObject("SELECT name FROM ingredients WHERE name=?", String.class, storedName)).isEqualTo(storedName);
        String body = jdbcTemplate.queryForObject("SELECT feedback_body FROM analysis_feedbacks", String.class);
        JsonNode items = JsonMapper.builder().build().readTree(body).path("payload").path("items");
        List<JsonNode> matching = StreamSupport.stream(items.spliterator(), false)
                .filter(item -> item.path("itemId").asText().equals(itemId)).toList();
        assertThat(matching).hasSize(1);
        assertThat(matching.getFirst().path("confirmed").path("name").asText()).isEqualTo(storedName);
        assertThat(matching.getFirst().path("confirmed").path("quantity").asInt()).isEqualTo(requestedQuantity);
    }

    private AnalysisJob completedJob(Long userId) {
        AnalysisCompletionItem item = new AnalysisCompletionItem("item", RecognitionStatus.RECOGNIZED, "원본우유", IngredientCategory.DAIRY,
                StorageType.REFRIGERATED, MeasureType.COUNT, 1, new AnalysisCompletionItem.Weight(null, WeightUnit.NONE),
                new AnalysisCompletionItem.Expiration(null, AnalysisExpirationDateType.UNKNOWN, null), List.of());
        AnalysisJob analysisJob = analysisJobAcceptance.accept(userId, "RECEIPT", List.of(AnalysisImage.pending("image", "ab".repeat(32))));
        return analysisCompletionPersistence.apply(new AnalysisCompletedEvent(UUID.randomUUID().toString(), new AnalysisCompletedEvent.Payload("AnalysisCompleted",
                clock.instant(), analysisJob.analysisImages().getFirst().requestId(), userId, "ai-success", AnalysisStatus.COMPLETED, clock.instant(),
                new AnalysisCompletionSuccess(AnalysisDocumentType.RECEIPT, List.of(item)), null))).getFirst();
    }
}
