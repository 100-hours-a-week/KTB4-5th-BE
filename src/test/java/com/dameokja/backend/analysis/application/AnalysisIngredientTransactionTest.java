package com.dameokja.backend.analysis.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;

import com.dameokja.backend.analysis.domain.*;
import com.dameokja.backend.analysis.infrastructure.AnalysisJobStore;
import com.dameokja.backend.global.util.BusinessTime;
import com.dameokja.backend.ingredient.domain.*;
import com.dameokja.backend.support.ServiceIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AnalysisIngredientTransactionTest extends ServiceIntegrationTest {
    @Autowired AnalysisIngredientService service;
    @Autowired AnalysisJobStore store;
    @MockitoSpyBean AnalysisFeedbackQueue queue;

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
            assertThat(jdbcTemplate.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(feedback_body, '$.confirmed.name')) FROM analysis_feedbacks", String.class))
                    .isEqualTo("확정우유");
        }
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ingredients", Integer.class)).isEqualTo(queueFails ? 0 : 1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM analysis_feedbacks", Integer.class)).isEqualTo(queueFails ? 0 : 1);
    }

    private AnalysisJob completedJob(Long userId) {
        AnalysisCompletionItem item = new AnalysisCompletionItem("item", RecognitionStatus.RECOGNIZED, "원본우유", IngredientCategory.DAIRY,
                StorageType.REFRIGERATED, MeasureType.COUNT, 1, null, null, List.of());
        AnalysisCompletion completion = new AnalysisCompletion(AnalysisStatus.COMPLETED, clock.instant().atZone(BusinessTime.ZONE).toOffsetDateTime(),
                new AnalysisCompletionSuccess(AnalysisDocumentType.RECEIPT, List.of(item)), null);
        return store.create(userId, "RECEIPT", List.of(new AnalysisImage("image", "hash", "request", "ai-success", completion)));
    }
}
